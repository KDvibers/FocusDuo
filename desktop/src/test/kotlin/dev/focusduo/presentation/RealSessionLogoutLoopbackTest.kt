package dev.focusduo.presentation

import dev.focusduo.data.DesktopPreferences
import dev.focusduo.data.DesktopPreferencesStore
import dev.focusduo.data.RealSessionStatus
import dev.focusduo.data.remote.FocusDuoJson
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.*
import io.ktor.server.response.respondText
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

/** Production CIO/WS reproduces a backend revoking the socket before replying to logout. */
class RealSessionLogoutLoopbackTest {
    @TempDir lateinit var directory: Path

    @Test fun `explicit logout stays neutral when server closes websocket before HTTP success`() = runBlocking<Unit> {
        Backend().use { backend ->
            val controller = controller(backend.origin())
            try {
                controller.initialize()
                controller.login("daniyar", "Password123!")
                val repository = assertNotNull(controller.state.value.repository)
                withTimeout(5_000) { repository.connectionState.first { repository.canMutate } }

                val logout = launch { controller.logout() }
                withTimeout(5_000) {
                    backend.logoutCloseSent.await()
                    repository.sessionStatus.first { it != RealSessionStatus.ACTIVE }
                }
                // HTTP is still withheld: an expected WS revocation must not close its client
                // or replace the user's explicit action with an unexpected-expiry error.
                assertTrue(controller.state.value.busy)
                assertSame(repository, controller.state.value.repository)
                assertNull(controller.state.value.authError)

                backend.releaseLogoutResponse.complete(Unit)
                withTimeout(5_000) { logout.join() }
                assertNull(controller.state.value.repository)
                assertNull(controller.state.value.authError)
                assertNull(controller.state.value.restoreError)
                assertEquals("Ты вышел из аккаунта.", controller.state.value.notice)
                assertFalse(controller.state.value.busy)
                assertEquals(RealSessionStatus.SIGNED_OUT, repository.sessionStatus.value)
                assertEquals(1, backend.paths.count { it == "/api/v1/auth/logout" })
                assertFalse(backend.paths.any { it.endsWith("/close") })
            } finally {
                backend.releaseLogoutResponse.complete(Unit)
                controller.close()
            }
        }
    }

    @Test fun `unsolicited websocket expiry still returns to login with an authorization error`() = runBlocking<Unit> {
        Backend().use { backend ->
            val controller = controller(backend.origin())
            try {
                controller.initialize()
                controller.login("daniyar", "Password123!")
                val repository = assertNotNull(controller.state.value.repository)
                withTimeout(5_000) { repository.connectionState.first { repository.canMutate } }
                backend.connection.await().close(CloseReason(4401, "expired"))
                val expired = withTimeout(5_000) {
                    controller.state.first { it.repository == null && it.authError?.code == "UNAUTHORIZED" }
                }
                assertEquals("Сессия истекла или отозвана. Войди снова.", expired.authError?.message)
                assertNull(expired.notice)
                assertFalse(expired.busy)
                assertFalse(backend.paths.any { it == "/api/v1/auth/logout" || it.endsWith("/close") })
            } finally { controller.close() }
        }
    }

    private suspend fun controller(origin: String): RealSessionController {
        val store = DesktopPreferencesStore(directory.resolve("preferences.properties"), emptyMap())
        store.save(DesktopPreferences(origin))
        // Run the session observer immediately on publication, making the close-before-response
        // ordering deterministic instead of relying on scheduling delays or arbitrary sleeps.
        return RealSessionController(store, coroutineContext = Dispatchers.Unconfined)
    }

    private class Backend : AutoCloseable {
        val connection = CompletableDeferred<DefaultWebSocketServerSession>()
        val logoutCloseSent = CompletableDeferred<Unit>()
        val releaseLogoutResponse = CompletableDeferred<Unit>()
        val paths = CopyOnWriteArrayList<String>()
        private val auth = fixture("auth-session").replace("2026-10-09T16:00:00Z", "2099-10-09T16:00:00Z")
        private val user = FocusDuoJson.parseToJsonElement(auth).jsonObject.getValue("user").toString()
        private val current = fixture("room-current")
        private val room = FocusDuoJson.parseToJsonElement(current).jsonObject.getValue("room").toString()
        private val server = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
            install(WebSockets)
            routing {
                route("/api/v1/{rest...}") {
                    handle {
                        val path = call.request.path()
                        paths += path
                        val body = when (path) {
                            "/api/v1/auth/login" -> auth
                            "/api/v1/me" -> user
                            "/api/v1/rooms/current" -> current
                            "/api/v1/auth/logout" -> {
                                assertEquals("Bearer example-token-for-contract-tests-only", call.request.headers[HttpHeaders.Authorization])
                                connection.await().close(CloseReason(4401, "revoked by logout"))
                                logoutCloseSent.complete(Unit)
                                releaseLogoutResponse.await()
                                ""
                            }
                            else -> error("Unexpected route: $path")
                        }
                        call.respondText(body, ContentType.Application.Json,
                            if (path == "/api/v1/auth/logout") HttpStatusCode.NoContent else HttpStatusCode.OK)
                    }
                }
                webSocket("/ws/rooms/{roomId}") {
                    connection.complete(this)
                    send(Frame.Text("""{"type":"ROOM_SNAPSHOT","data":$room}"""))
                    incoming.consumeEach { }
                }
            }
        }.start(wait = false)

        suspend fun origin(): String = "http://127.0.0.1:${server.engine.resolvedConnectors().first().port}"
        override fun close() {
            releaseLogoutResponse.complete(Unit)
            server.stop(0, 1_000)
        }
    }

    companion object {
        private fun fixture(name: String): String = RealSessionLogoutLoopbackTest::class.java
            .getResource("/contracts/$name.json")!!.readText()
    }
}
