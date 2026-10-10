package dev.focusduo.data

import dev.focusduo.data.remote.*
import dev.focusduo.domain.RepositoryException
import dev.focusduo.domain.RoomStatus
import dev.focusduo.domain.RoundKind
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
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
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

/** Production CIO REST and raw WebSocket against a loopback contract server, not the Java backend. */
class RealRoomLoopbackTest {
    @Test fun `all room commands use exact v1 routes bodies and fresh idempotency headers`() = runBlocking<Unit> {
        Backend().use { backend ->
            RealRepository.login(backend.origin(), "daniyar", "Password123!").use { repository ->
                repository.bootstrap()
                assertNull(repository.state.value.room)
                repository.createRoom(1500, 300)
                await { repository.canMutate }
                repository.updateGoal("Цель с пробелами ")
                repository.addTask("  Длинная русская задача  ")
                repository.updateTask(TASK_ID, completed = true)
                repository.updateTask(TASK_ID, title = " Переименована ")
                repository.deleteTask(TASK_ID)
                repository.updateSettings(1800, 600)
                repository.startRound(RoundKind.FOCUS)
                repository.pauseRound(ROUND_ID)
                repository.resumeRound(ROUND_ID)
                repository.finishRound(ROUND_ID)
                repository.startRound(RoundKind.BREAK)
                repository.loadHistory()
                repository.loadHistoryItem(ROUND_ID)
                repository.closeRoom()
                assertEquals(RoomStatus.CLOSED, repository.state.value.room!!.status)
                assertFalse(repository.canMutate)
                assertEquals(RealConnectionStatus.NONE, repository.connectionState.value.status)
                repository.joinRoom(" fd-opaque ")
                await { repository.canMutate }

                val writes = backend.requests.filter { it.key != null }
                assertEquals(14, writes.size)
                assertEquals(writes.size, writes.map { it.key }.distinct().size)
                writes.forEach {
                    assertEquals("Bearer $TOKEN", it.authorization)
                    assertEquals(it.key, UUID.fromString(it.key).toString())
                    assertNull(it.headers["If-Match"])
                    assertFalse(it.body.contains("expectedRevision"))
                    if (it.path in listOf("/api/v1/rooms", "/api/v1/rooms/join")) assertNull(it.revision)
                    else assertNotNull(it.revision)
                }
                val expected = listOf(
                    HttpMethod.Post to "/api/v1/rooms", HttpMethod.Patch to "/api/v1/rooms/$ROOM_ID/me",
                    HttpMethod.Post to "/api/v1/rooms/$ROOM_ID/tasks",
                    HttpMethod.Patch to "/api/v1/rooms/$ROOM_ID/tasks/$TASK_ID",
                    HttpMethod.Patch to "/api/v1/rooms/$ROOM_ID/tasks/$TASK_ID",
                    HttpMethod.Delete to "/api/v1/rooms/$ROOM_ID/tasks/$TASK_ID",
                    HttpMethod.Patch to "/api/v1/rooms/$ROOM_ID/settings",
                    HttpMethod.Post to "/api/v1/rooms/$ROOM_ID/rounds",
                    HttpMethod.Post to "/api/v1/rooms/$ROOM_ID/rounds/$ROUND_ID/pause",
                    HttpMethod.Post to "/api/v1/rooms/$ROOM_ID/rounds/$ROUND_ID/resume",
                    HttpMethod.Post to "/api/v1/rooms/$ROOM_ID/rounds/$ROUND_ID/finish",
                    HttpMethod.Post to "/api/v1/rooms/$ROOM_ID/rounds",
                    HttpMethod.Post to "/api/v1/rooms/$ROOM_ID/close", HttpMethod.Post to "/api/v1/rooms/join",
                )
                assertEquals(expected, writes.map { it.method to it.path })
                assertEquals(setOf("completed"), json(writes[3].body).keys)
                assertEquals(setOf("title"), json(writes[4].body).keys)
                assertEquals("{\"code\":\"FD-OPAQUE\"}", writes.last().body)
                assertEquals("{\"title\":\"Длинная русская задача\"}", writes[2].body)
                writes.filter { it.method == HttpMethod.Delete || it.path.endsWith("/pause") ||
                    it.path.endsWith("/resume") || it.path.endsWith("/finish") || it.path.endsWith("/close") }.forEach {
                    assertEquals("", it.body)
                    assertNull(it.headers[HttpHeaders.ContentType])
                }
                backend.requests.filter { it.method == HttpMethod.Get || it.path.contains("/auth/") }.forEach {
                    assertNull(it.key); assertNull(it.revision)
                }
                assertEquals(listOf("/ws/rooms/$ROOM_ID", "/ws/rooms/$OTHER_ROOM_ID"), backend.handshakes.map { it.path })
                backend.handshakes.forEach {
                    assertEquals("Bearer $TOKEN", it.authorization)
                    assertEquals("", it.query)
                }
            }
        }
    }

    @Test fun `REST restoration remains read only until first full websocket snapshot`() = runBlocking<Unit> {
        Backend(room = sampleRoom(), firstSnapshotGate = CompletableDeferred()).use { backend ->
            RealRepository.login(backend.origin(), "daniyar", "Password123!").use { repository ->
                repository.bootstrap()
                await { repository.connectionState.value.status == RealConnectionStatus.SYNCHRONIZING }
                assertNotNull(repository.state.value.room)
                assertFalse(repository.canMutate)
                expectError("CONNECTION_NOT_READY") { repository.updateGoal("не отправлять") }
                assertTrue(backend.requests.none { it.key != null })
                backend.firstSnapshotGate.complete(Unit)
                await { repository.canMutate }
                assertTrue(repository.state.value.history.isEmpty(), "Snapshot must not invent history")
            }
        }
    }

    @Test fun `repeat join of current room is sent without room revision and keeps two participants`() = runBlocking<Unit> {
        Backend(room = sampleRoom()).use { backend ->
            RealRepository.login(backend.origin(), "daniyar", "Password123!").use { repository ->
                repository.bootstrap(); await { repository.canMutate }
                repository.joinRoom(" fd-7k9m2q ")
                assertEquals(2, repository.state.value.room!!.participants.size)
                val request = backend.requests.last { it.key != null }
                assertEquals("/api/v1/rooms/join", request.path)
                assertNull(request.revision)
                assertEquals("{\"code\":\"FD-7K9M2Q\"}", request.body)
            }
        }
    }

    @Test fun `reading historical room cannot replace active membership or its websocket`() = runBlocking<Unit> {
        Backend(room = sampleRoom()).use { backend ->
            RealRepository.login(backend.origin(), "daniyar", "Password123!").use { repository ->
                repository.bootstrap(); await { repository.canMutate }
                val old = repository.refreshRoom(OTHER_ROOM_ID)
                assertEquals(OTHER_ROOM_ID, old.roomId)
                assertEquals(RoomStatus.CLOSED, old.status)
                assertEquals(ROOM_ID, repository.state.value.room!!.roomId)
                assertTrue(repository.canMutate)
                assertEquals(1, backend.handshakes.size)
            }
        }
    }

    @Test fun `revision conflict applies snapshot and explicit next action uses a new key and revision`() = runBlocking<Unit> {
        Backend(room = sampleRoom()).use { backend ->
            RealRepository.login(backend.origin(), "daniyar", "Password123!").use { repository ->
                repository.bootstrap(); await { repository.canMutate }
                backend.conflictNext = true
                val error = expectError("REVISION_CONFLICT") { repository.addTask("конфликт") }
                assertEquals(18L, repository.state.value.room!!.revision)
                assertNotNull(error.snapshot)
                assertFalse(error.message.contains(TOKEN))
                val first = backend.requests.filter { it.key != null }.single()
                assertEquals("17", first.revision)
                repository.addTask("повтор по решению пользователя")
                val next = backend.requests.filter { it.key != null }.last()
                assertEquals("18", next.revision)
                assertNotEquals(first.key, next.key)
                assertEquals(2, backend.requests.count { it.key != null }, "No automatic command retry")
            }
        }
    }

    @Test fun `stream revisions preserve business data and manual reconnect requires a new snapshot`() = runBlocking<Unit> {
        Backend(room = sampleRoom()).use { backend ->
            RealRepository.login(backend.origin(), "daniyar", "Password123!").use { repository ->
                repository.bootstrap(); await { repository.canMutate }
                val original = repository.state.value.room!!
                backend.send(sampleRoom().copy(revision = 16, code = "OLD"))
                backend.send(sampleRoom().copy(serverNow = "2026-10-08T16:06:19Z", code = "CHANGED"))
                await { repository.state.value.room!!.serverNow > original.serverNow }
                assertEquals(original.code, repository.state.value.room!!.code)
                backend.connections.last().close(CloseReason(CloseReason.Codes.GOING_AWAY, "disconnect"))
                await { repository.connectionState.value.status == RealConnectionStatus.RECONNECTING }
                assertFalse(repository.canMutate)
                expectError("CONNECTION_NOT_READY") { repository.finishRound(ROUND_ID) }
                backend.firstSnapshotGate = CompletableDeferred()
                repository.reconnect()
                await { repository.connectionState.value.status == RealConnectionStatus.SYNCHRONIZING }
                assertFalse(repository.canMutate)
                backend.firstSnapshotGate.complete(Unit)
                await { repository.canMutate }
                assertEquals(2, backend.handshakes.size)
            }
        }
    }

    @Test fun `4401 expires session and clears room history and mutation ability`() = runBlocking<Unit> {
        Backend(room = sampleRoom()).use { backend ->
            RealRepository.login(backend.origin(), "daniyar", "Password123!").use { repository ->
                repository.bootstrap(); await { repository.canMutate }
                repository.loadHistory()
                assertTrue(repository.state.value.history.isNotEmpty())
                backend.connections.last().close(CloseReason(4401, "expired"))
                await { repository.sessionStatus.value == RealSessionStatus.EXPIRED }
                assertNull(repository.state.value.room)
                assertTrue(repository.state.value.history.isEmpty())
                assertFalse(repository.canMutate)
                expectError("UNAUTHORIZED") { repository.loadHistory() }
            }
        }
    }

    @Test fun `older first snapshot after reconnect cannot unlock mutations`() = runBlocking<Unit> {
        Backend(room = sampleRoom()).use { backend ->
            RealRepository.login(backend.origin(), "daniyar", "Password123!").use { repository ->
                repository.bootstrap(); await { repository.canMutate }
                backend.connections.last().close(CloseReason(CloseReason.Codes.GOING_AWAY, "disconnect"))
                await { repository.connectionState.value.status == RealConnectionStatus.RECONNECTING }
                backend.room = sampleRoom().copy(revision = 16)
                repository.reconnect()
                await { backend.handshakes.size == 2 }
                delay(100)
                assertEquals(RealConnectionStatus.SYNCHRONIZING, repository.connectionState.value.status)
                assertFalse(repository.canMutate)
                expectError("CONNECTION_NOT_READY") { repository.addTask("ждём свежий снимок") }
                backend.send(sampleRoom())
                await { repository.canMutate }
            }
        }
    }

    @Test fun `ambiguous room creation keeps pending intent and blocks all new writes after synchronization`() = runBlocking<Unit> {
        Backend().use { backend ->
            RealRepository.login(backend.origin(), "daniyar", "Password123!").use { repository ->
                repository.bootstrap()
                backend.malformedNext = true
                expectError("PROTOCOL_ERROR") { repository.createRoom() }
                expectError("PENDING_COMMAND") { repository.createRoom() }
                assertEquals(1, backend.requests.count { it.key != null })
                await { repository.pendingCommand.value?.retryAvailable == true }
                assertFalse(repository.canMutate)
                expectError("PENDING_COMMAND") { repository.addTask("не повторять автоматически") }
                assertEquals(1, backend.requests.count { it.key != null }, "Recovery must only perform GETs and WS handshake")
                assertEquals("Создание комнаты", repository.pendingCommand.value!!.description)
            }
        }
    }

    @Test fun `unauthorized handshake expires authenticated session`() = runBlocking<Unit> {
        Backend(room = sampleRoom(), rejectHandshake = true).use { backend ->
            RealRepository.login(backend.origin(), "daniyar", "Password123!").use { repository ->
                repository.bootstrap()
                await { repository.sessionStatus.value == RealSessionStatus.EXPIRED }
                assertNull(repository.state.value.room)
                assertFalse(repository.canMutate)
            }
        }
    }

    @Test fun `closed final snapshot stays readable and normal closure offers no reconnect`() = runBlocking<Unit> {
        Backend(room = sampleRoom()).use { backend ->
            RealRepository.login(backend.origin(), "daniyar", "Password123!").use { repository ->
                repository.bootstrap(); await { repository.canMutate }
                val closed = sampleRoom().copy(status = "CLOSED", revision = 18)
                backend.room = null
                backend.send(closed)
                await { repository.state.value.room?.status == RoomStatus.CLOSED }
                assertEquals(RealConnectionStatus.NONE, repository.connectionState.value.status)
                assertFalse(repository.canMutate)
                repository.refreshCurrentRoom()
                assertEquals(RoomStatus.CLOSED, repository.state.value.room?.status)
                repository.reconnect()
                assertEquals(1, backend.handshakes.size)
                assertTrue(repository.state.value.history.isEmpty())
            }
        }
    }

    @Test fun `snapshot for foreign room is rejected without replacing current room`() = runBlocking<Unit> {
        Backend(room = sampleRoom()).use { backend ->
            RealRepository.login(backend.origin(), "daniyar", "Password123!").use { repository ->
                repository.bootstrap(); await { repository.canMutate }
                backend.send(sampleRoom().copy(roomId = "20000000-0000-4000-8000-000000000002", revision = 999))
                await { repository.connectionState.value.status == RealConnectionStatus.DISCONNECTED }
                assertEquals(ROOM_ID, repository.state.value.room!!.roomId)
                assertEquals(17L, repository.state.value.room!!.revision)
                assertFalse(repository.canMutate)
            }
        }
    }

    @Test fun `invalid command fields are rejected before network and no key is manufactured`() = runBlocking<Unit> {
        Backend().use { backend ->
            RealRepository.login(backend.origin(), "daniyar", "Password123!").use { repository ->
                expectError("VALIDATION_ERROR") { repository.createRoom(301, 300) }
                expectError("VALIDATION_ERROR") { repository.joinRoom(" ") }
                expectError("VALIDATION_ERROR") { repository.updateGoal("x".repeat(241)) }
                expectError("VALIDATION_ERROR") { repository.addTask(" ") }
                expectError("VALIDATION_ERROR") { repository.updateTask(TASK_ID) }
                assertTrue(backend.requests.none { it.key != null })
            }
        }
    }

    private suspend fun expectError(code: String, action: suspend () -> Any?): RepositoryException {
        try { action(); fail("Expected $code") } catch (error: RepositoryException) {
            assertEquals(code, error.code)
            return error
        }
    }

    private suspend fun await(predicate: () -> Boolean) = withTimeout(5_000) {
        while (!predicate()) delay(10)
    }

    private fun json(value: String): JsonObject = FocusDuoJson.parseToJsonElement(value).jsonObject

    private class Backend(
        @Volatile var room: RoomSnapshotDto? = null,
        @Volatile var firstSnapshotGate: CompletableDeferred<Unit> = CompletableDeferred(Unit),
        rejectHandshake: Boolean = false,
    ) : AutoCloseable {
        @Volatile var conflictNext = false
        @Volatile var malformedNext = false
        val requests = CopyOnWriteArrayList<Request>()
        val handshakes = CopyOnWriteArrayList<Handshake>()
        val connections = CopyOnWriteArrayList<DefaultWebSocketServerSession>()
        private val server = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
            install(WebSockets)
            routing {
                route("/api/v1/{rest...}") {
                    handle {
                        val request = Request(call.request.path(), call.request.httpMethod,
                            call.request.headers[HttpHeaders.Authorization], call.request.headers["Idempotency-Key"],
                            call.request.headers["X-Room-Revision"], call.receiveText(), call.request.headers)
                        requests += request
                        var status = HttpStatusCode.OK
                        val body = when (request.path) {
                            "/api/v1/auth/login" -> fixture("auth-session")
                            "/api/v1/me" -> FocusDuoJson.encodeToString(AuthSessionDto.serializer(),
                                FocusDuoJson.decodeFromString(fixture("auth-session"))).let {
                                FocusDuoJson.encodeToString(UserDto.serializer(), FocusDuoJson.decodeFromString<AuthSessionDto>(it).user)
                            }
                            "/api/v1/rooms/current" -> FocusDuoJson.encodeToString(CurrentRoomDto.serializer(), CurrentRoomDto(room))
                            "/api/v1/history" -> fixture("history")
                            "/api/v1/history/$ROUND_ID" -> fixture("history-item")
                            "/api/v1/rooms/$OTHER_ROOM_ID" -> FocusDuoJson.encodeToString(RoomSnapshotDto.serializer(),
                                sampleRoom().copy(roomId = OTHER_ROOM_ID, status = "CLOSED", revision = 30))
                            else -> {
                                if (request.key == null) {
                                    FocusDuoJson.encodeToString(RoomSnapshotDto.serializer(), room ?: sampleRoom().copy(status = "CLOSED"))
                                } else if (malformedNext) {
                                    malformedNext = false
                                    room = (room ?: sampleRoom()).copy(revision = (room?.revision ?: 16) + 1)
                                    "{\"unexpected\":true}"
                                } else if (conflictNext) {
                                    conflictNext = false
                                    room = (room ?: sampleRoom()).copy(revision = (room?.revision ?: 17) + 1)
                                    status = HttpStatusCode.Conflict
                                    FocusDuoJson.encodeToString(ApiErrorDto.serializer(), ApiErrorDto("REVISION_CONFLICT",
                                        "secret $TOKEN must not appear", "request-test", emptyMap(), room))
                                } else {
                                    room = when {
                                        request.path == "/api/v1/rooms" -> sampleRoom()
                                        request.path == "/api/v1/rooms/join" -> if (room?.status == "CLOSED")
                                            sampleRoom().copy(roomId = OTHER_ROOM_ID, revision = 1) else sampleRoom()
                                        request.path.endsWith("/close") -> room!!.copy(status = "CLOSED", revision = room!!.revision + 1)
                                        else -> room!!.copy(revision = room!!.revision + 1)
                                    }
                                    FocusDuoJson.encodeToString(RoomSnapshotDto.serializer(), room!!)
                                }
                            }
                        }
                        call.respondText(body, ContentType.Application.Json, status)
                    }
                }
                if (rejectHandshake) {
                    get("/ws/rooms/{roomId}") { call.respondText("unauthorized", status = HttpStatusCode.Unauthorized) }
                } else {
                    webSocket("/ws/rooms/{roomId}") {
                        handshakes += Handshake(call.request.path(), call.request.queryString(), call.request.headers[HttpHeaders.Authorization])
                        connections += this
                        firstSnapshotGate.await()
                        room?.let { send(Frame.Text(FocusDuoJson.encodeToString(RoomEventDto.serializer(), RoomEventDto("ROOM_SNAPSHOT", it)))) }
                        incoming.consumeEach { }
                    }
                }
            }
        }.start(wait = false)

        suspend fun origin(): String = "http://127.0.0.1:${server.engine.resolvedConnectors().first().port}"
        suspend fun send(snapshot: RoomSnapshotDto) {
            connections.last().send(Frame.Text(FocusDuoJson.encodeToString(RoomEventDto.serializer(), RoomEventDto("ROOM_SNAPSHOT", snapshot))))
        }
        override fun close() { server.stop(0, 1_000) }
    }

    private data class Request(val path: String, val method: HttpMethod, val authorization: String?, val key: String?,
                               val revision: String?, val body: String, val headers: io.ktor.http.Headers)
    private data class Handshake(val path: String, val query: String, val authorization: String?)

    companion object {
        private const val ROOM_ID = "20000000-0000-4000-8000-000000000001"
        private const val OTHER_ROOM_ID = "20000000-0000-4000-8000-000000000002"
        private const val TASK_ID = "40000000-0000-4000-8000-000000000002"
        private const val ROUND_ID = "30000000-0000-4000-8000-000000000001"
        private const val TOKEN = "example-token-for-contract-tests-only"
        private fun fixture(name: String) = RealRoomLoopbackTest::class.java.getResource("/contracts/$name.json")!!.readText()
        private fun sampleRoom(): RoomSnapshotDto = FocusDuoJson.decodeFromString(fixture("room-running"))
    }
}
