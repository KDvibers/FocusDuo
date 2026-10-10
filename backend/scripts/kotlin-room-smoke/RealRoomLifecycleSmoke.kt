@file:OptIn(io.ktor.utils.io.InternalAPI::class)

package dev.focusduo.smoke.rooms

import dev.focusduo.data.RealConnectionStatus
import dev.focusduo.data.RealRepository
import dev.focusduo.data.RealSessionStatus
import dev.focusduo.data.remote.FocusDuoJson
import dev.focusduo.data.remote.RoomSnapshotDto
import dev.focusduo.data.remote.toDomain
import dev.focusduo.domain.RepositoryException
import dev.focusduo.domain.RoomSnapshot
import dev.focusduo.domain.RoomStatus
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.HttpRequestPipeline
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import java.net.URI
import java.net.http.HttpClient as JdkHttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.exitProcess

/** Observes REST authorization only in memory; production CIO still sends every request. */
private class ObservedCio(private val cio: HttpClientEngine = CIO.create()) : HttpClientEngine by cio {
    @Volatile private var bearer: String? = null
    val joinRequests = AtomicInteger()
    fun token(): String = checkNotNull(bearer) { "Authenticated request was not observed" }
    override fun install(client: HttpClient) {
        client.requestPipeline.intercept(HttpRequestPipeline.Before) {
            if (context.url.build().encodedPath == "/api/v1/rooms/join") joinRequests.incrementAndGet()
            context.headers[HttpHeaders.Authorization]?.let { value ->
                check(value.startsWith("Bearer ")) { "Unexpected authorization scheme" }
                bearer = value.removePrefix("Bearer ")
            }
        }
        cio.install(client)
    }
    fun forget() { bearer = null }
}

private class Smoke(private val baseUrl: String) : AutoCloseable {
    private val http = JdkHttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
        .followRedirects(JdkHttpClient.Redirect.NEVER).build()
    private val repositories = mutableListOf<RealRepository>()
    private val engines = mutableListOf<ObservedCio>()
    private var roomId: String? = null
    private var roomOwnerId: String? = null
    private var roomCreationAttempted = false
    private var cleanupToken: String? = null

    suspend fun run() {
        val username = "kotlife_" + UUID.randomUUID().toString().replace("-", "").take(20)
        val password = UUID.randomUUID().toString() + "Я!"
        val ownerEngine = observe()
        val owner = keep(RealRepository.register(baseUrl, username, "Lifecycle owner", password, ownerEngine))
        owner.bootstrap()
        val ownerId = owner.state.value.currentUser.id
        roomOwnerId = ownerId
        val peerEngine = observe()
        val peer = keep(RealRepository.register(baseUrl, username + "b", "Lifecycle peer", password, peerEngine))
        peer.bootstrap()
        cleanupToken = ownerEngine.token()
        roomCreationAttempted = true
        val created = owner.createRoom(300, 60)
        roomId = created.roomId
        val joined = peer.joinRoom(created.code)
        cleanupToken = peerEngine.token()
        awaitReady(owner, joined)
        awaitReady(peer, joined)
        check(joined.participants.size == 2) { "Room does not have two participants" }

        coroutineScope {
            // Subscribe before reconnect; Unconfined observes the synchronous state transition
            // before a fast loopback handshake can replace it with CONNECTED.
            val synchronizationStarted = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                withTimeout(10_000) {
                    owner.connectionState.first {
                        it.status == RealConnectionStatus.CONNECTING || it.status == RealConnectionStatus.SYNCHRONIZING
                    }
                }
            }
            owner.reconnect()
            synchronizationStarted.await()
        }
        awaitReady(owner, joined)
        val afterReconnect = peer.updateGoal("Изменение после ручного переподключения")
        sameBusiness(awaitReady(owner, afterReconnect), afterReconnect)
        println("PASS reconnect: manual resubscription receives a full snapshot and the partner's next change")

        owner.logout()
        check(owner.sessionStatus.value == RealSessionStatus.SIGNED_OUT && owner.state.value.room == null) {
            "Logout did not clear owner state"
        }
        val editedWhileAway = peer.updateGoal("Изменение, пока партнёр вышел")
        val loginEngine = observe()
        val login = keep(RealRepository.login(baseUrl, username, password, loginEngine))
        login.bootstrap()
        check(login.state.value.currentUser.id == ownerId) { "Login changed identity" }
        sameBusiness(awaitReady(login, editedWhileAway), editedWhileAway)
        check(loginEngine.joinRequests.get() == 0) { "Restoring membership sent a join command" }
        println("PASS relogin: bootstrap restores the same two-member room and missed edit without joining again")

        val revokedToken = loginEngine.token()
        request("POST", "/auth/logout", revokedToken, expectedStatus = 204)
        // No repository REST call here: EXPIRED must be delivered through the existing real WebSocket.
        withTimeout(10_000) { login.sessionStatus.first { it == RealSessionStatus.EXPIRED } }
        check(login.state.value.room == null && login.state.value.history.isEmpty() && !login.canMutate &&
            login.connectionState.value.status == RealConnectionStatus.NONE) { "WebSocket revocation retained session state" }
        val rejected = request("GET", "/me", revokedToken, expectedStatus = 401)
        check(rejected.jsonObject["code"]?.jsonPrimitive?.content == "UNAUTHORIZED") { "Revoked token was accepted" }
        check(peer.refreshMe().id == peer.state.value.currentUser.id) { "Partner authorization was affected by revocation" }
        sameBusiness(awaitReady(peer, editedWhileAway), editedWhileAway)
        println("PASS revoke: active WebSocket expires the repository without REST refresh; token receives 401 and peer stays connected")

        val restoredEngine = observe()
        val restored = keep(RealRepository.login(baseUrl, username, password, restoredEngine))
        restored.bootstrap()
        sameBusiness(awaitReady(restored, editedWhileAway), editedWhileAway)
        check(restoredEngine.joinRequests.get() == 0) { "Relogin sent a join command" }
        val closed = peer.closeRoom()
        val finalSnapshot = withTimeout(10_000) {
            combine(restored.state, restored.connectionState) { state, connection ->
                state.room?.takeIf { it.status == RoomStatus.CLOSED && connection.status == RealConnectionStatus.NONE }
            }.filterNotNull().first()
        }
        sameBusiness(finalSnapshot, closed)
        check(!restored.canMutate && !peer.canMutate) { "Closed room still permits mutation" }
        check(request("GET", "/rooms/current", restoredEngine.token()).jsonObject["room"] == JsonNull) {
            "Closed room still has active membership"
        }
        sameBusiness(checkNotNull(restored.refreshCurrentRoom()), closed)
        restored.reconnect()
        check(restored.connectionState.value.status == RealConnectionStatus.NONE && !restored.canMutate) {
            "Reconnect reopened a closed room"
        }
        sameBusiness(checkNotNull(restored.state.value.room), closed)
        expectCode("CONNECTION_NOT_READY") { restored.updateGoal("Не должно сохраниться") }
        sameBusiness(restored.refreshRoom(closed.roomId), closed)
        roomCreationAttempted = false
        roomId = null
        println("PASS closed: final snapshot retained, membership released, reconnect stays NONE and mutations are rejected")

        restored.logout()
        peer.logout()
        println("PASS cleanup: own room closed and active sessions logged out")
    }

    private fun observe() = ObservedCio().also { engines += it }
    private fun keep(repository: RealRepository) = repository.also { repositories += it }

    private suspend fun awaitReady(repository: RealRepository, expected: RoomSnapshot): RoomSnapshot = withTimeout(10_000) {
        combine(repository.state, repository.connectionState, repository.sessionStatus) { state, connection, session ->
            state.room?.takeIf { it.roomId == expected.roomId && it.status == RoomStatus.OPEN && it.revision >= expected.revision &&
                connection.status == RealConnectionStatus.CONNECTED && session == RealSessionStatus.ACTIVE }
        }.filterNotNull().first()
    }

    private fun sameBusiness(actual: RoomSnapshot, expected: RoomSnapshot) {
        check(actual.copy(serverNow = expected.serverNow) == expected) { "Room contents or revision differ" }
    }

    private suspend fun expectCode(code: String, block: suspend () -> Any?) {
        try { block() } catch (failure: RepositoryException) {
            check(failure.code == code) { "Unexpected repository error code" }
            return
        }
        error("Expected repository error was not returned")
    }

    private fun request(method: String, path: String, token: String, expectedStatus: Int = 200,
                        revision: Long? = null, timeoutSeconds: Long = 10): JsonElement {
        val builder = HttpRequest.newBuilder(URI.create("$baseUrl/api/v1$path"))
            .timeout(Duration.ofSeconds(timeoutSeconds)).header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
        if (revision != null) builder.header("X-Room-Revision", revision.toString())
            .header("Idempotency-Key", UUID.randomUUID().toString())
        val response = http.send(builder.method(method, HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == expectedStatus) { "Unexpected HTTP status" }
        return if (response.body().isBlank()) JsonNull else FocusDuoJson.parseToJsonElement(response.body())
    }

    suspend fun cleanup(): Boolean {
        var clean = true
        val token = cleanupToken
        if (roomCreationAttempted && token != null) {
            try {
                // Recover even when create/join committed but the response was lost.
                val current = checkNotNull(request("GET", "/rooms/current", token, timeoutSeconds = 3).jsonObject["room"])
                if (current != JsonNull) {
                    val snapshot = FocusDuoJson.decodeFromJsonElement<RoomSnapshotDto>(current).toDomain()
                    check(snapshot.ownerId == roomOwnerId && (roomId == null || snapshot.roomId == roomId)) {
                        "Cleanup refused an unrelated room"
                    }
                    if (snapshot.status == RoomStatus.OPEN) request("POST", "/rooms/${snapshot.roomId}/close", token,
                        revision = snapshot.revision, timeoutSeconds = 3)
                }
            } catch (_: Exception) { clean = false; System.err.println("FAIL cleanup: own room could not be closed") }
        }
        repositories.forEach { repository ->
            try {
                if (repository.sessionStatus.value == RealSessionStatus.ACTIVE) withTimeout(5_000) { repository.logout() }
            } catch (_: Exception) { clean = false; System.err.println("FAIL cleanup: session logout was not confirmed") }
            finally { repository.close() }
        }
        engines.forEach { it.close(); it.forget() }
        cleanupToken = null
        return clean
    }

    override fun close() { http.shutdownNow() }
}

fun main() {
    var result = 0
    var smoke: Smoke? = null
    try {
        val base = System.getProperty("focusduo.smoke.baseUrl", "http://127.0.0.1:8090").trimEnd('/')
        val uri = URI.create(base)
        require(uri.scheme in setOf("http", "https") && uri.host in setOf("127.0.0.1", "localhost", "[::1]") &&
            uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null && uri.path.isEmpty())
        smoke = Smoke(base)
        runBlocking { withTimeout(120_000) { smoke.run() } }
    } catch (failure: Throwable) {
        // Never print request/response bodies, throwable messages, credentials, headers or stack traces.
        System.err.println("FAIL live Kotlin room lifecycle: ${failure.javaClass.simpleName}")
        result = if (failure is CancellationException) 130 else 1
    } finally {
        smoke?.let {
            try { if (!runBlocking { it.cleanup() }) result = 1 }
            finally { it.close() }
        }
    }
    if (result == 0) println("PASS FocusDuo RealRepository live room lifecycle")
    exitProcess(result)
}
