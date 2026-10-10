package dev.focusduo.presentation

import dev.focusduo.data.RealConnectionStatus
import dev.focusduo.data.RealRepository
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResumeRecoveryRepositoryTest {
    @Test fun `calendar sleep gap reaches repository despite a recent monotonic recovery`() = runBlocking<Unit> {
        var wallMillis = 100_000L
        var nanos = 1_000_000_000L
        val requests = CopyOnWriteArrayList<String>()
        val loginRequests = AtomicInteger()
        val meRequests = AtomicInteger()
        val currentRequests = AtomicInteger()
        val wakeIdentityRead = CompletableDeferred<Unit>()
        val releaseWakeIdentity = CompletableDeferred<Unit>()
        val repository = RealRepository.login("http://127.0.0.1:8090", "anna", "WakeRecoveryPassword1!",
            engine = MockEngine { request ->
                requests += request.url.encodedPath
                when (request.url.encodedPath) {
                    "/api/v1/auth/login" -> {
                        loginRequests.incrementAndGet()
                        respond(AUTH, headers = JSON)
                    }
                    "/api/v1/me" -> {
                        assertEquals("Bearer wake-recovery-test-token", request.headers[HttpHeaders.Authorization])
                        if (meRequests.incrementAndGet() == 3) {
                            wakeIdentityRead.complete(Unit)
                            releaseWakeIdentity.await()
                            respond(WAKE_USER, headers = JSON)
                        } else respond(USER, headers = JSON)
                    }
                    "/api/v1/rooms/current" -> {
                        assertEquals("Bearer wake-recovery-test-token", request.headers[HttpHeaders.Authorization])
                        currentRequests.incrementAndGet()
                        respond("""{"room":null}""", headers = JSON)
                    }
                    else -> error("Wake recovery must only reread identity and current membership")
                }
            }, realtimeEnabled = true)
        try {
            repository.bootstrap()
            assertTrue(repository.canEnterRoom)
            val coordinator = ResumeRecoveryCoordinator(
                currentTimeMillis = { wallMillis }, nanoTime = { nanos },
                recover = repository::recoverAfterResume,
            )

            // Establish the heartbeat baseline and a recovery immediately before suspend.
            coordinator.onHeartbeat()
            coordinator.onForegroundHint()
            withTimeout(5_000) {
                repository.recoveryVersion.first { it == 1L }
                repository.connectionState.first { it.status == RealConnectionStatus.NONE }
            }
            assertTrue(repository.canEnterRoom)

            // The detector sees a minute-long calendar gap while nanoTime advances by only 1 s.
            // A nanoTime-only two-second admission gate would discard this qualified signal.
            wallMillis += 61_000L
            nanos += 1_000_000_000L
            coordinator.onHeartbeat()
            assertFalse(repository.canEnterRoom, "Wake must immediately gate writes before authoritative reads finish")
            withTimeout(5_000) { wakeIdentityRead.await() }
            assertEquals(1L, repository.recoveryVersion.value)
            assertEquals(2, currentRequests.get(), "Membership cannot be accepted before /me completes")

            releaseWakeIdentity.complete(Unit)
            withTimeout(5_000) {
                repository.recoveryVersion.first { it == 2L }
                repository.connectionState.first { it.status == RealConnectionStatus.NONE }
            }
            assertTrue(repository.canEnterRoom)
            assertNull(repository.state.value.room)
            assertEquals("Анна после пробуждения", repository.state.value.currentUser.displayName)
            assertEquals(1, loginRequests.get(), "Wake must not replay login POST")
            assertEquals(3, meRequests.get())
            assertEquals(3, currentRequests.get())
            assertEquals(listOf(
                "/api/v1/auth/login",
                "/api/v1/me", "/api/v1/rooms/current",
                "/api/v1/me", "/api/v1/rooms/current",
                "/api/v1/me", "/api/v1/rooms/current",
            ), requests)
        } finally {
            releaseWakeIdentity.complete(Unit)
            repository.close()
        }
    }

    companion object {
        private const val USER = """{"id":"10000000-0000-4000-8000-000000000001","username":"anna","displayName":"Анна"}"""
        private const val WAKE_USER = """{"id":"10000000-0000-4000-8000-000000000001","username":"anna","displayName":"Анна после пробуждения"}"""
        private const val AUTH = """{"user":$USER,"accessToken":"wake-recovery-test-token","expiresAt":"2099-10-10T12:00:00Z"}"""
        private val JSON = headersOf(HttpHeaders.ContentType, "application/json")
    }
}
