package dev.focusduo.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpResponseValidator
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerializationException

/** Raw JSON WebSocket. Its lifetime and reconnect policy belong to RealRepository. */
internal class RoomRealtimeClient(
    baseUrl: String,
    private val token: () -> String,
) : AutoCloseable {
    private val socketOrigin = baseUrl.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://")
    private val engine = CIO.create()
    private val client = HttpClient(engine) {
        followRedirects = false
        install(HttpTimeout) { connectTimeoutMillis = 5_000; requestTimeoutMillis = 15_000 }
        install(WebSockets) { maxFrameSize = 4L * 1024 * 1024 }
        HttpResponseValidator {
            validateResponse { response ->
                if (response.status.value == 401) throw BackendHttpClient.unauthorized()
            }
        }
    }

    suspend fun listen(
        roomId: String,
        onHandshake: () -> Unit,
        onSnapshot: (RoomSnapshotDto) -> Boolean,
        isSynchronized: () -> Boolean,
    ): CloseReason? {
        BackendHttpClient.requireUuid(roomId)
        val session = client.webSocketSession("$socketOrigin/ws/rooms/$roomId") {
            header(HttpHeaders.Authorization, "Bearer ${token()}")
        }
        try {
            onHandshake()
            var synchronized = false
            val synchronizationDeadline = System.nanoTime() + 10_000_000_000L
            while (true) {
                val received = if (!synchronized) withTimeout(
                    ((synchronizationDeadline - System.nanoTime()) / 1_000_000L).coerceAtLeast(1),
                ) { session.incoming.receiveCatching() }
                    else session.incoming.receiveCatching()
                val frame = received.getOrNull() ?: break
                if (frame !is Frame.Text) throw BackendHttpClient.protocolError()
                val event = try { FocusDuoJson.decodeFromString<RoomEventDto>(frame.readText()) }
                    catch (_: SerializationException) { throw BackendHttpClient.protocolError() }
                if (event.type != "ROOM_SNAPSHOT") throw BackendHttpClient.protocolError()
                if (!onSnapshot(event.data)) {
                    session.close(CloseReason(CloseReason.Codes.NORMAL, "Room closed"))
                    break
                }
                synchronized = synchronized || isSynchronized()
            }
            return session.closeReason.await()
        } finally {
            try { session.close() } catch (_: CancellationException) { /* parent cancellation closes transport below */ }
        }
    }

    override fun close() {
        client.close()
        client.coroutineContext.cancel()
        engine.close()
    }
}
