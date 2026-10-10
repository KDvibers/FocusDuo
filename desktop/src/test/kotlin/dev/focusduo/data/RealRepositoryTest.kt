package dev.focusduo.data

import dev.focusduo.data.remote.AuthSessionDto
import dev.focusduo.data.remote.CurrentRoomDto
import dev.focusduo.data.remote.FocusDuoJson
import dev.focusduo.data.remote.LoginRequestDto
import dev.focusduo.data.remote.ParticipantDto
import dev.focusduo.data.remote.RegisterRequestDto
import dev.focusduo.data.remote.RoomSnapshotDto
import dev.focusduo.data.remote.UserDto
import dev.focusduo.domain.RepositoryException
import dev.focusduo.domain.RoundKind
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import java.io.IOException
import java.net.ServerSocket
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class RealRepositoryTest {
    @Test fun `register normalizes public fields keeps exact password and sends no secrets in URL`() = runBlocking<Unit> {
        val password = " Exact Pass1! "
        var calls = 0
        val engine = MockEngine { request ->
            calls++
            assertEquals("http://127.0.0.1:8090/api/v1/auth/register", request.url.toString())
            assertEquals(HttpMethod.Post, request.method)
            assertNull(request.headers[HttpHeaders.Authorization])
            assertNull(request.headers["Idempotency-Key"])
            assertNull(request.headers["X-Room-Revision"])
            val body = FocusDuoJson.decodeFromString<RegisterRequestDto>(request.body.toByteArray().decodeToString())
            assertEquals("alice_1", body.username)
            assertEquals("Алиса", body.displayName)
            assertEquals(password, body.password)
            respond(jsonSession(), HttpStatusCode.Created, JSON_HEADERS)
        }
        RealRepository.register(ORIGIN, "  ALICE_1 ", " Алиса ", password, engine).use { repository ->
            assertEquals(1, calls, "Authentication must not be repeated or coupled to restoration")
            assertEquals("Алиса", repository.state.value.currentUser.displayName)
            assertEquals(RealSessionStatus.ACTIVE, repository.sessionStatus.value)
            assertEquals(Instant.parse(EXPIRES), repository.expiresAt)
        }
    }

    @Test fun `login restores authenticated identity and complete current room with bearer headers`() = runBlocking<Unit> {
        val paths = mutableListOf<String>()
        val engine = MockEngine { request ->
            paths += request.url.encodedPath
            assertNull(request.url.encodedQuery.takeIf { it.isNotEmpty() })
            assertNull(request.headers["Idempotency-Key"])
            assertNull(request.headers["X-Room-Revision"])
            when (request.url.encodedPath) {
                "/api/v1/auth/login" -> {
                    assertNull(request.headers[HttpHeaders.Authorization])
                    assertEquals("alice_1", FocusDuoJson.decodeFromString<LoginRequestDto>(request.body.toByteArray().decodeToString()).username)
                    respond(jsonSession(), headers = JSON_HEADERS)
                }
                "/api/v1/me" -> {
                    assertEquals("Bearer $TOKEN", request.headers[HttpHeaders.Authorization])
                    respond(FocusDuoJson.encodeToString(USER), headers = JSON_HEADERS)
                }
                "/api/v1/rooms/current" -> {
                    assertEquals("Bearer $TOKEN", request.headers[HttpHeaders.Authorization])
                    respond(FocusDuoJson.encodeToString(CurrentRoomDto(room())), headers = JSON_HEADERS)
                }
                else -> fail("Unexpected path")
            }
        }
        RealRepository.login(ORIGIN, " ALICE_1 ", PASSWORD, engine).use { repository ->
            assertNull(repository.state.value.room)
            repository.bootstrap()
            assertEquals(ROOM_ID, repository.state.value.room!!.roomId)
            assertEquals("Серверная цель", repository.state.value.room!!.participants.single().goal)
            assertTrue(repository.state.value.history.isEmpty())
            assertEquals(listOf("/api/v1/auth/login", "/api/v1/me", "/api/v1/rooms/current"), paths)
        }
    }

    @Test fun `restoration network failure keeps session and retries GET without another login`() = runBlocking<Unit> {
        var authCalls = 0
        var currentCalls = 0
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/v1/auth/login" -> { authCalls++; respond(jsonSession(), headers = JSON_HEADERS) }
                "/api/v1/me" -> respond(FocusDuoJson.encodeToString(USER), headers = JSON_HEADERS)
                "/api/v1/rooms/current" -> {
                    if (++currentCalls == 1) throw IOException("$TOKEN $PASSWORD low-level trace")
                    respond("{\"room\":null}", headers = JSON_HEADERS)
                }
                else -> fail("Unexpected path")
            }
        }
        RealRepository.login(ORIGIN, "alice_1", PASSWORD, engine).use { repository ->
            val error = expectError("NETWORK_ERROR") { repository.bootstrap() }
            assertSafe(error)
            assertEquals(RealSessionStatus.ACTIVE, repository.sessionStatus.value)
            repository.bootstrap()
            assertNull(repository.state.value.room)
            assertEquals(1, authCalls)
            assertEquals(2, currentCalls)
        }
    }

    @Test fun `unauthorized response clears room token and marks expired even with malformed body`() = runBlocking<Unit> {
        var reads = 0
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/v1/auth/login" -> respond(jsonSession(), headers = JSON_HEADERS)
                "/api/v1/rooms/current" -> if (++reads == 1) {
                    respond(FocusDuoJson.encodeToString(CurrentRoomDto(room())), headers = JSON_HEADERS)
                } else respond("broken $TOKEN", HttpStatusCode.Unauthorized)
                else -> fail("Unexpected path")
            }
        }
        RealRepository.login(ORIGIN, "alice_1", PASSWORD, engine).use { repository ->
            repository.refreshCurrentRoom()
            assertNotNull(repository.state.value.room)
            assertSafe(expectError("UNAUTHORIZED") { repository.refreshCurrentRoom() })
            assertNull(repository.state.value.room)
            assertTrue(repository.state.value.history.isEmpty())
            assertEquals(RealSessionStatus.EXPIRED, repository.sessionStatus.value)
            expectError("UNAUTHORIZED") { repository.refreshMe() }
            assertEquals(2, reads, "Expired sessions must not make another authorized request")
        }
    }

    @Test fun `logout posts no body and clears local state without closing room`() = runBlocking<Unit> {
        val paths = mutableListOf<String>()
        val engine = MockEngine { request ->
            paths += request.url.encodedPath
            when (request.url.encodedPath) {
                "/api/v1/auth/login" -> respond(jsonSession(), headers = JSON_HEADERS)
                "/api/v1/rooms/current" -> respond(FocusDuoJson.encodeToString(CurrentRoomDto(room())), headers = JSON_HEADERS)
                "/api/v1/auth/logout" -> {
                    assertEquals(HttpMethod.Post, request.method)
                    assertEquals("Bearer $TOKEN", request.headers[HttpHeaders.Authorization])
                    assertTrue(request.body.toByteArray().isEmpty())
                    respond("", HttpStatusCode.NoContent)
                }
                else -> fail("Unexpected path")
            }
        }
        RealRepository.login(ORIGIN, "alice_1", PASSWORD, engine).use { repository ->
            repository.refreshCurrentRoom()
            repository.logout()
            assertEquals(RealSessionStatus.SIGNED_OUT, repository.sessionStatus.value)
            assertNull(repository.state.value.room)
            expectError("UNAUTHORIZED") { repository.refreshCurrentRoom() }
            assertTrue(paths.none { it.endsWith("/close") })
        }
    }

    @Test fun `failed logout still signs out locally and exposes only safe error`() = runBlocking<Unit> {
        val engine = MockEngine { request ->
            if (request.url.encodedPath.endsWith("/login")) respond(jsonSession(), headers = JSON_HEADERS)
            else throw IOException("Authorization Bearer $TOKEN password=$PASSWORD")
        }
        RealRepository.login(ORIGIN, "alice_1", PASSWORD, engine).use { repository ->
            assertSafe(expectError("NETWORK_ERROR") { repository.logout() })
            assertEquals(RealSessionStatus.SIGNED_OUT, repository.sessionStatus.value)
            assertNull(repository.state.value.room)
            expectError("UNAUTHORIZED") { repository.refreshMe() }
        }
    }

    @Test fun `validation and conflict responses use safe local messages and field whitelist`() = runBlocking<Unit> {
        val engine = MockEngine {
            respond("""{"code":"VALIDATION_ERROR","message":"$PASSWORD $TOKEN trace","requestId":"request-1","fieldErrors":{"username":"$PASSWORD","password":"$TOKEN","sql":"trace"},"snapshot":null}""", HttpStatusCode.BadRequest, JSON_HEADERS)
        }
        val error = expectError("VALIDATION_ERROR") { RealRepository.login(ORIGIN, "alice_1", PASSWORD, engine) }
        assertEquals(setOf("username", "password"), error.fieldErrors.keys)
        assertSafe(error)
        val duplicate = MockEngine {
            respond("""{"code":"USERNAME_TAKEN","message":"$PASSWORD","requestId":"request-2","fieldErrors":{},"snapshot":null}""", HttpStatusCode.Conflict, JSON_HEADERS)
        }
        assertSafe(expectError("USERNAME_TAKEN") { RealRepository.register(ORIGIN, "alice_1", "Алиса", PASSWORD, duplicate) })
    }

    @Test fun `HTML error is never presented and unexpected successful JSON is protocol error`() = runBlocking<Unit> {
        val unavailable = MockEngine { respond("<html>Stack trace $TOKEN $PASSWORD</html>", HttpStatusCode.InternalServerError) }
        assertSafe(expectError("INTERNAL_ERROR") { RealRepository.login(ORIGIN, "alice_1", PASSWORD, unavailable) })
        val forbidden = MockEngine { respond("<html>$TOKEN $PASSWORD</html>", HttpStatusCode.Forbidden) }
        assertSafe(expectError("FORBIDDEN") { RealRepository.login(ORIGIN, "alice_1", PASSWORD, forbidden) })
        val malformed = MockEngine { respond("{\"unexpected\":true}", headers = JSON_HEADERS) }
        assertSafe(expectError("PROTOCOL_ERROR") { RealRepository.login(ORIGIN, "alice_1", PASSWORD, malformed) })
    }

    @Test fun `redirect is not followed and bearer cannot be forwarded to another origin`() = runBlocking<Unit> {
        var calls = 0
        val engine = MockEngine { request ->
            calls++
            if (request.url.encodedPath.endsWith("/login")) respond(jsonSession(), headers = JSON_HEADERS)
            else respond("", HttpStatusCode.TemporaryRedirect, headersOf(HttpHeaders.Location, "https://other.invalid/collect"))
        }
        RealRepository.login(ORIGIN, "alice_1", PASSWORD, engine).use { repository ->
            expectError("HTTP_ERROR") { repository.refreshMe() }
            assertEquals(2, calls)
            assertEquals(RealSessionStatus.ACTIVE, repository.sessionStatus.value)
        }
    }

    @Test fun `cancelled call propagates cancellation without invalidating session or retrying`() = runBlocking<Unit> {
        var calls = 0
        val engine = MockEngine { request ->
            calls++
            if (request.url.encodedPath.endsWith("/login")) respond(jsonSession(), headers = JSON_HEADERS)
            else throw CancellationException("cancelled")
        }
        RealRepository.login(ORIGIN, "alice_1", PASSWORD, engine).use { repository ->
            try { repository.refreshMe(); fail("Cancellation expected") } catch (_: CancellationException) { }
            assertEquals(RealSessionStatus.ACTIVE, repository.sessionStatus.value)
            assertEquals(2, calls)
        }
    }

    @Test fun `invalid expiration token and mismatched identity are rejected safely`() = runBlocking<Unit> {
        for (session in listOf(AuthSessionDto(USER, TOKEN, "invalid-date"), AuthSessionDto(USER, "bad\r\ntoken", EXPIRES))) {
            val engine = MockEngine { respond(FocusDuoJson.encodeToString(session), headers = JSON_HEADERS) }
            assertSafe(expectError("PROTOCOL_ERROR") { RealRepository.login(ORIGIN, "alice_1", PASSWORD, engine) })
        }
        val engine = MockEngine { request ->
            if (request.url.encodedPath.endsWith("/login")) respond(jsonSession(), headers = JSON_HEADERS)
            else respond(FocusDuoJson.encodeToString(USER.copy(id = "10000000-0000-4000-8000-000000000002")), headers = JSON_HEADERS)
        }
        RealRepository.login(ORIGIN, "alice_1", PASSWORD, engine).use { repository ->
            expectError("PROTOCOL_ERROR") { repository.refreshMe() }
            assertEquals(USER.id, repository.state.value.currentUser.id)
        }
    }

    @Test fun `unsupported writes make no HTTP calls and never manufacture fake data`() = runBlocking<Unit> {
        var calls = 0
        val engine = MockEngine { calls++; respond(jsonSession(), headers = JSON_HEADERS) }
        RealRepository.login(ORIGIN, "alice_1", PASSWORD, engine).use { repository ->
            expectError("FEATURE_NOT_AVAILABLE") { repository.createRoom() }
            expectError("FEATURE_NOT_AVAILABLE") { repository.joinRoom("FD-EXAMPLE") }
            expectError("FEATURE_NOT_AVAILABLE") { repository.startRound(RoundKind.FOCUS) }
            expectError("FEATURE_NOT_AVAILABLE") { repository.addTask("Задача") }
            assertEquals(1, calls)
            assertNull(repository.state.value.room)
            assertTrue(repository.state.value.history.isEmpty())
        }
    }

    @Test fun `current room must be open and include authenticated user`() = runBlocking<Unit> {
        for (invalid in listOf(room().copy(status = "CLOSED"), room().copy(participants = emptyList()), room().copy(status = "BROKEN"),
            room().copy(serverNow = "malformed-date"))) {
            val engine = MockEngine { request ->
                if (request.url.encodedPath.endsWith("/login")) respond(jsonSession(), headers = JSON_HEADERS)
                else respond(FocusDuoJson.encodeToString(CurrentRoomDto(invalid)), headers = JSON_HEADERS)
            }
            RealRepository.login(ORIGIN, "alice_1", PASSWORD, engine).use { repository ->
                expectError("PROTOCOL_ERROR") { repository.refreshCurrentRoom() }
                assertNull(repository.state.value.room)
            }
        }
    }

    @Test fun `older revision cannot roll current room back and fresher equal revision preserves data`() = runBlocking<Unit> {
        val replies = ArrayDeque(listOf(
            room(revision = 5),
            room(revision = 4).copy(code = "OLD-CODE"),
            room(revision = 5, now = "2026-10-10T12:00:05Z").copy(code = "CHANGED-WITHOUT-REVISION"),
        ))
        val engine = MockEngine { request ->
            if (request.url.encodedPath.endsWith("/login")) respond(jsonSession(), headers = JSON_HEADERS)
            else respond(FocusDuoJson.encodeToString(CurrentRoomDto(replies.removeFirst())), headers = JSON_HEADERS)
        }
        RealRepository.login(ORIGIN, "alice_1", PASSWORD, engine).use { repository ->
            repository.refreshCurrentRoom()
            val first = repository.state.value.room!!
            repository.refreshCurrentRoom()
            assertEquals(first, repository.state.value.room)
            repository.refreshCurrentRoom()
            assertEquals(first.code, repository.state.value.room!!.code)
            assertEquals(Instant.parse("2026-10-10T12:00:05Z"), repository.state.value.room!!.serverNow)
        }
    }

    @Test fun `CIO connection refused reports network error without demo fallback`() = runBlocking<Unit> {
        val unusedPort = ServerSocket(0).use { it.localPort }
        assertSafe(expectError("NETWORK_ERROR") { RealRepository.login("http://127.0.0.1:$unusedPort", "alice_1", PASSWORD) })
    }

    @Test fun `invalid input and secret bearing base URL are rejected before a request`() = runBlocking<Unit> {
        var calls = 0
        val engine = MockEngine { calls++; respond(jsonSession(), headers = JSON_HEADERS) }
        expectError("VALIDATION_ERROR") { RealRepository.login(ORIGIN, "!", PASSWORD, engine) }
        expectError("VALIDATION_ERROR") { RealRepository.login("http://alice:$PASSWORD@127.0.0.1:8090", "alice_1", PASSWORD, engine) }
        assertEquals(0, calls)
        engine.close()
    }

    @Test fun `late room response cannot repopulate state after close`() = runBlocking<Unit> {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val engine = MockEngine { request ->
            if (request.url.encodedPath.endsWith("/login")) respond(jsonSession(), headers = JSON_HEADERS)
            else withContext(NonCancellable) {
                started.complete(Unit)
                release.await()
                respond(FocusDuoJson.encodeToString(CurrentRoomDto(room())), headers = JSON_HEADERS)
            }
        }
        val repository = RealRepository.login(ORIGIN, "alice_1", PASSWORD, engine)
        val pending = async {
            try { repository.refreshCurrentRoom() } catch (_: CancellationException) { null } catch (error: RepositoryException) {
                assertEquals("UNAUTHORIZED", error.code)
                null
            }
        }
        started.await()
        repository.close()
        release.complete(Unit)
        pending.await()
        assertEquals(RealSessionStatus.SIGNED_OUT, repository.sessionStatus.value)
        assertNull(repository.state.value.room)
        assertTrue(repository.state.value.history.isEmpty())
    }

    @Test fun `owned engine is released after close and failed authentication`() = runBlocking<Unit> {
        val successful = MockEngine { respond(jsonSession(), headers = JSON_HEADERS) }
        val repository = RealRepository.login(ORIGIN, "alice_1", PASSWORD, successful)
        repository.close()
        withTimeout(5_000) { successful.coroutineContext[Job]!!.join() }
        assertTrue(successful.coroutineContext[Job]!!.isCompleted)
        val rejected = MockEngine { respond("", HttpStatusCode.Unauthorized) }
        expectError("UNAUTHORIZED") { RealRepository.login(ORIGIN, "alice_1", PASSWORD, rejected) }
        withTimeout(5_000) { rejected.coroutineContext[Job]!!.join() }
        assertTrue(rejected.coroutineContext[Job]!!.isCompleted)
    }

    private suspend fun expectError(code: String, block: suspend () -> Any?): RepositoryException {
        try { block(); fail("Expected $code") } catch (error: RepositoryException) { assertEquals(code, error.code); return error }
    }

    private fun assertSafe(error: RepositoryException) {
        val rendered = error.toString() + error.fieldErrors.toString()
        assertFalse(rendered.contains(TOKEN))
        assertFalse(rendered.contains(PASSWORD))
        assertFalse(rendered.contains("trace"))
        assertNull(error.cause)
    }

    companion object {
        private const val ORIGIN = "http://127.0.0.1:8090"
        private const val PASSWORD = "SecretPass1!"
        private const val TOKEN = "opaque_token_for_test_only"
        private const val EXPIRES = "2026-10-11T12:00:00Z"
        private const val ROOM_ID = "20000000-0000-4000-8000-000000000001"
        private val USER = UserDto("10000000-0000-4000-8000-000000000001", "alice_1", "Алиса")
        private val JSON_HEADERS = headersOf(HttpHeaders.ContentType, "application/json")
        private fun jsonSession() = FocusDuoJson.encodeToString(AuthSessionDto(USER, TOKEN, EXPIRES))
        private fun room(revision: Long = 1, now: String = "2026-10-10T12:00:00Z") = RoomSnapshotDto(
            ROOM_ID, "FD-ABC123", USER.id, "OPEN", revision, now, 1500, 300,
            listOf(ParticipantDto(USER.id, USER.displayName, "Серверная цель", emptyList())), null,
        )
    }
}
