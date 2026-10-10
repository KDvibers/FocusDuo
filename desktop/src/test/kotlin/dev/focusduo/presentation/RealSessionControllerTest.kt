package dev.focusduo.presentation

import dev.focusduo.data.DesktopPreferences
import dev.focusduo.data.DesktopPreferencesStore
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RealSessionControllerTest {
    @TempDir lateinit var directory: Path

    @Test fun `initialize loads nonsecret preferences and does not automatically authorize`() = runBlocking<Unit> {
        val store = store()
        store.save(DesktopPreferences("http://localhost:8091", "anna"))
        val engines = AtomicInteger()
        val controller = RealSessionController(store, engineFactory = { engines.incrementAndGet(); healthyEngine() }, realtimeEnabled = false)
        try {
            controller.initialize()
            assertFalse(controller.state.value.loading)
            assertFalse(controller.state.value.busy)
            assertEquals(DesktopPreferences("http://localhost:8091", "anna"), controller.state.value.preferences)
            assertNull(controller.state.value.repository)
            assertEquals(0, engines.get())
        } finally { controller.close() }
    }

    @Test fun `login restores me and current room using the authenticated repository`() = runBlocking<Unit> {
        val paths = CopyOnWriteArrayList<String>()
        val controller = controller(MockEngine { request ->
            paths += request.url.encodedPath
            when (request.url.encodedPath) {
                "/api/v1/auth/login" -> {
                    assertNull(request.headers[HttpHeaders.Authorization])
                    respond(AUTH, headers = JSON)
                }
                "/api/v1/me" -> {
                    assertEquals("Bearer test-controller-token", request.headers[HttpHeaders.Authorization])
                    respond(UPDATED_USER, headers = JSON)
                }
                "/api/v1/rooms/current" -> {
                    assertEquals("Bearer test-controller-token", request.headers[HttpHeaders.Authorization])
                    respond(javaClass.getResource("/contracts/room-current.json")!!.readText(), headers = JSON)
                }
                else -> error("Unexpected route")
            }
        })
        try {
            controller.initialize()
            controller.login(" ANNA ", PASSWORD)
            val repository = assertNotNull(controller.state.value.repository)
            assertEquals("Анна после /me", repository.state.value.currentUser.displayName)
            assertEquals("FD-7K9M2Q", repository.state.value.room!!.code)
            assertEquals(listOf("/api/v1/auth/login", "/api/v1/me", "/api/v1/rooms/current"), paths)
            assertNull(controller.state.value.authError)
            assertNull(controller.state.value.restoreError)
            assertFalse(controller.state.value.busy)
        } finally { controller.close() }
    }

    @Test fun `invalid form never opens a network client`() = runBlocking<Unit> {
        val engines = AtomicInteger()
        val controller = RealSessionController(store(), engineFactory = { engines.incrementAndGet(); healthyEngine() }, realtimeEnabled = false)
        try {
            controller.initialize()
            controller.login("!", "short")
            assertEquals("VALIDATION_ERROR", controller.state.value.authError!!.code)
            assertEquals(setOf("username", "password"), controller.state.value.authError!!.fieldErrors.keys)
            controller.register("anna", "А", PASSWORD)
            assertEquals(setOf("displayName"), controller.state.value.authError!!.fieldErrors.keys)
            assertEquals(0, engines.get())
            assertNull(controller.state.value.repository)
        } finally { controller.close() }
    }

    @Test fun `register bootstrap outage retains session and retry does not repeat registration`() = runBlocking<Unit> {
        val registrations = AtomicInteger()
        val meRequests = AtomicInteger()
        val controller = controller(MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/v1/auth/register" -> {
                    registrations.incrementAndGet()
                    respond(AUTH, HttpStatusCode.Created, JSON)
                }
                "/api/v1/me" -> {
                    if (meRequests.incrementAndGet() == 1) throw IOException("private transport detail")
                    respond(USER, headers = JSON)
                }
                "/api/v1/rooms/current" -> respond("""{"room":null}""", headers = JSON)
                else -> error("Unexpected route")
            }
        })
        try {
            controller.initialize()
            controller.register("anna", "Анна", PASSWORD)
            val repository = assertNotNull(controller.state.value.repository)
            assertNotNull(controller.state.value.restoreError)
            assertNull(controller.state.value.authError)
            assertFalse(controller.state.value.busy)
            controller.retryRestore()
            assertSame(repository, controller.state.value.repository)
            assertNull(controller.state.value.restoreError)
            assertEquals(1, registrations.get())
            assertEquals(2, meRequests.get())
        } finally { controller.close() }
    }

    @Test fun `unauthorized bootstrap clears session and returns a safe login error`() = runBlocking<Unit> {
        val controller = controller(MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/v1/auth/login" -> respond(AUTH, headers = JSON)
                "/api/v1/me" -> respond(UNAUTHORIZED, HttpStatusCode.Unauthorized, JSON)
                else -> error("Unauthorized bootstrap must stop before current room")
            }
        })
        try {
            controller.initialize()
            controller.login("anna", PASSWORD)
            assertNull(controller.state.value.repository)
            assertNull(controller.state.value.restoreError)
            assertEquals("UNAUTHORIZED", controller.state.value.authError!!.code)
            assertFalse(controller.state.value.authError!!.message.contains("test-controller-token"))
            assertFalse(controller.state.value.busy)
        } finally { controller.close() }
    }

    @Test fun `automatic identity and membership recovery clears restoration error without repeating login`() = runBlocking<Unit> {
        val recoveryRead = CompletableDeferred<Unit>()
        val releaseRecovery = CompletableDeferred<Unit>()
        val authRequests = AtomicInteger()
        val meRequests = AtomicInteger()
        val controller = RealSessionController(store(), engineFactory = { MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/v1/auth/login" -> { authRequests.incrementAndGet(); respond(AUTH, headers = JSON) }
                "/api/v1/me" -> {
                    if (meRequests.incrementAndGet() == 1) throw IOException("outage")
                    recoveryRead.complete(Unit)
                    releaseRecovery.await()
                    respond(UPDATED_USER, headers = JSON)
                }
                "/api/v1/rooms/current" -> respond("""{"room":null}""", headers = JSON)
                else -> error("Unexpected recovery request")
            }
        } })
        try {
            controller.initialize()
            controller.login("anna", PASSWORD)
            val repository = assertNotNull(controller.state.value.repository)
            assertNotNull(controller.state.value.restoreError)
            withTimeout(5_000) { recoveryRead.await() }
            releaseRecovery.complete(Unit)
            withTimeout(5_000) { controller.state.first { it.restoreError == null } }
            assertSame(repository, controller.state.value.repository)
            assertEquals("Анна после /me", repository.state.value.currentUser.displayName)
            assertNull(repository.state.value.room)
            assertEquals(1, authRequests.get())
            assertTrue(repository.recoveryVersion.value > 0L)
        } finally { releaseRecovery.complete(Unit); controller.close() }
    }

    @Test fun `duplicate login is ignored while a request is pending`() = runBlocking<Unit> {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val authRequests = AtomicInteger()
        val controller = controller(MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/v1/auth/login" -> {
                    authRequests.incrementAndGet()
                    entered.complete(Unit)
                    release.await()
                    respond(AUTH, headers = JSON)
                }
                "/api/v1/me" -> respond(USER, headers = JSON)
                "/api/v1/rooms/current" -> respond("""{"room":null}""", headers = JSON)
                else -> error("Unexpected route")
            }
        })
        try {
            controller.initialize()
            val login = launch(start = CoroutineStart.UNDISPATCHED) { controller.login("anna", PASSWORD) }
            withTimeout(5000) { entered.await() }
            assertTrue(controller.state.value.busy)
            controller.login("anna", PASSWORD)
            assertEquals(1, authRequests.get())
            release.complete(Unit)
            withTimeout(5000) { login.join() }
            assertNotNull(controller.state.value.repository)
            assertFalse(controller.state.value.busy)
        } finally { release.complete(Unit); controller.close() }
    }

    @Test fun `server change cancels pending auth and prevents a stale session`() = runBlocking<Unit> {
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val requests = CopyOnWriteArrayList<String>()
        val engines = AtomicInteger()
        val controller = RealSessionController(store(), realtimeEnabled = false, engineFactory = {
            val firstEngine = engines.incrementAndGet() == 1
            MockEngine { request ->
                requests += request.url.toString()
                when (request.url.encodedPath) {
                    "/api/v1/auth/login" -> {
                        if (firstEngine) {
                            entered.complete(Unit)
                            try { CompletableDeferred<Unit>().await() }
                            finally { cancelled.complete(Unit) }
                        }
                        respond(AUTH, headers = JSON)
                    }
                    "/api/v1/me" -> respond(USER, headers = JSON)
                    "/api/v1/rooms/current" -> respond("""{"room":null}""", headers = JSON)
                    else -> error("Unexpected route")
                }
            }
        })
        try {
            controller.initialize()
            val login = launch(start = CoroutineStart.UNDISPATCHED) { controller.login("anna", PASSWORD) }
            withTimeout(5000) { entered.await() }
            controller.savePreferences(DesktopPreferences("http://localhost:8091", "anna"))
            withTimeout(5000) { cancelled.await(); login.join() }
            assertNull(controller.state.value.repository)
            assertNull(controller.state.value.authError)
            assertFalse(controller.state.value.busy)
            controller.login("anna", PASSWORD)
            assertNotNull(controller.state.value.repository)
            assertTrue(requests.drop(1).all { it.startsWith("http://localhost:8091/") })
            assertEquals(4, requests.size)
        } finally { controller.close() }
    }

    @Test fun `invalid server preserves session and username-only save does not reconnect`() = runBlocking<Unit> {
        val count = AtomicInteger()
        val controller = controller(healthyEngine { count.incrementAndGet() })
        try {
            controller.initialize()
            controller.login("anna", PASSWORD)
            val repository = assertNotNull(controller.state.value.repository)
            val before = count.get()
            controller.savePreferences(DesktopPreferences("bad-address", "anna"))
            assertSame(repository, controller.state.value.repository)
            assertEquals("VALIDATION_ERROR", controller.state.value.settingsError!!.code)
            controller.savePreferences(DesktopPreferences(rememberedUsername = " ANNA "))
            assertSame(repository, controller.state.value.repository)
            assertNull(controller.state.value.settingsError)
            assertEquals("anna", controller.state.value.preferences.rememberedUsername)
            assertEquals(before, count.get())
            val saved = Files.readString(directory.resolve("preferences.properties"))
            assertFalse(saved.contains(PASSWORD))
            assertFalse(saved.contains("test-controller-token"))
        } finally { controller.close() }
    }

    @Test fun `logout transport failure still clears local session without closing the room`() = runBlocking<Unit> {
        val paths = CopyOnWriteArrayList<String>()
        val controller = controller(MockEngine { request ->
            paths += request.url.encodedPath
            when (request.url.encodedPath) {
                "/api/v1/auth/login" -> respond(AUTH, headers = JSON)
                "/api/v1/me" -> respond(USER, headers = JSON)
                "/api/v1/rooms/current" -> respond("""{"room":null}""", headers = JSON)
                "/api/v1/auth/logout" -> throw IOException("private transport detail")
                else -> error("Logout must not close a room")
            }
        })
        try {
            controller.initialize()
            controller.login("anna", PASSWORD)
            controller.logout()
            assertNull(controller.state.value.repository)
            assertNotNull(controller.state.value.authError)
            assertTrue(controller.state.value.notice!!.contains("Локальная сессия очищена"))
            assertEquals("/api/v1/auth/logout", paths.last())
            assertFalse(paths.any { it.endsWith("/close") })
            assertFalse(controller.state.value.busy)
        } finally { controller.close() }
    }

    @Test fun `401 from an already open repository is observed and clears mounted session`() = runBlocking<Unit> {
        val meRequests = AtomicInteger()
        val controller = controller(MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/v1/auth/login" -> respond(AUTH, headers = JSON)
                "/api/v1/me" -> if (meRequests.incrementAndGet() == 1) respond(USER, headers = JSON)
                    else respond(UNAUTHORIZED, HttpStatusCode.Unauthorized, JSON)
                "/api/v1/rooms/current" -> respond("""{"room":null}""", headers = JSON)
                else -> error("Unexpected route")
            }
        })
        try {
            controller.initialize()
            controller.login("anna", PASSWORD)
            val repository = assertNotNull(controller.state.value.repository)
            try { repository.refreshMe() } catch (_: Exception) { /* Expected server rejection. */ }
            withTimeout(5000) { controller.state.first { it.repository == null && it.authError?.code == "UNAUTHORIZED" } }
            assertNull(controller.state.value.restoreError)
        } finally { controller.close() }
    }

    @Test fun `close cancels a pending request and is safe to call twice`() = runBlocking<Unit> {
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val controller = controller(MockEngine {
            entered.complete(Unit)
            try { CompletableDeferred<Unit>().await() }
            finally { cancelled.complete(Unit) }
            error("Cancelled auth must not complete")
        })
        controller.initialize()
        val login = launch(start = CoroutineStart.UNDISPATCHED) { controller.login("anna", PASSWORD) }
        withTimeout(5000) { entered.await() }
        controller.close()
        controller.close()
        withTimeout(5000) { cancelled.await(); login.join() }
        assertNull(controller.state.value.repository)
        assertFalse(controller.state.value.busy)
        controller.login("anna", PASSWORD)
        assertNull(controller.state.value.repository)
    }

    private fun store() = DesktopPreferencesStore(directory.resolve("preferences.properties"), emptyMap())
    private fun controller(engine: MockEngine) = RealSessionController(store(), engineFactory = { engine }, realtimeEnabled = false)
    private fun healthyEngine(onRequest: () -> Unit = {}) = MockEngine { request ->
        onRequest()
        when (request.url.encodedPath) {
            "/api/v1/auth/login" -> respond(AUTH, headers = JSON)
            "/api/v1/me" -> respond(USER, headers = JSON)
            "/api/v1/rooms/current" -> respond("""{"room":null}""", headers = JSON)
            else -> error("Unexpected route")
        }
    }

    companion object {
        private const val PASSWORD = "ControllerPassword1!"
        private const val USER = """{"id":"10000000-0000-4000-8000-000000000001","username":"anna","displayName":"Анна"}"""
        private const val UPDATED_USER = """{"id":"10000000-0000-4000-8000-000000000001","username":"anna","displayName":"Анна после /me"}"""
        private const val AUTH = """{"user":$USER,"accessToken":"test-controller-token","expiresAt":"2099-10-10T12:00:00Z"}"""
        private const val UNAUTHORIZED = """{"code":"UNAUTHORIZED","message":"Сессия недействительна","requestId":"50000000-0000-4000-8000-000000000001","fieldErrors":{}}"""
        private val JSON = headersOf(HttpHeaders.ContentType, "application/json")
    }
}
