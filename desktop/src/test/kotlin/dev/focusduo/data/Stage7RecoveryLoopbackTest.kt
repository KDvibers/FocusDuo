package dev.focusduo.data

import dev.focusduo.data.remote.*
import dev.focusduo.domain.RepositoryException
import dev.focusduo.domain.RoomStatus
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.*
import io.ktor.server.response.respondText
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.consumeEach
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/** Real CIO sockets and a transactional, idempotent loopback contract double. */
class Stage7RecoveryLoopbackTest {
    @Test fun `lost successful response replays identical payload key and old revision exactly once`() = runBlocking<Unit> {
        Backend().use { backend ->
            backend.login().use { repository ->
                repository.bootstrap(); await { repository.canMutate }
                backend.malformedReplyNext = true
                expectError("PROTOCOL_ERROR") { repository.addTask("Секретный черновик задачи") }
                val first = backend.writes.single()
                await { repository.pendingCommand.value?.retryAvailable == true }
                assertEquals(18L, repository.state.value.room!!.revision)
                assertFalse(repository.canMutate)
                assertEquals(17L, repository.pendingCommand.value!!.originalRevision)
                val safeInfo = repository.pendingCommand.value.toString()
                assertFalse(safeInfo.contains("Секретный")); assertFalse(safeInfo.contains(TOKEN)); assertFalse(safeInfo.contains(first.key!!))
                expectError("PENDING_COMMAND") { repository.updateGoal("другое действие") }
                assertEquals(1, backend.writes.size)
                // Partner changes the room before replay; cached result must not roll it back.
                backend.room = backend.room!!.copy(revision = 19, code = "NEWER")
                backend.send(backend.room!!); await { repository.state.value.room!!.revision == 19L }
                repository.retryPendingCommand()
                assertNull(repository.pendingCommand.value)
                await { repository.canMutate }
                assertEquals(19L, repository.state.value.room!!.revision)
                assertEquals("NEWER", repository.state.value.room!!.code)
                assertEquals(first, backend.writes.last())
                assertEquals(1, backend.commits.get())
            }
        }
    }

    @Test fun `ambiguous create and join keep revision absent and recover before explicit replay`() = runBlocking<Unit> {
        for (create in listOf(true, false)) Backend(initialRoom = null).use { backend ->
            backend.login().use { repository ->
                repository.bootstrap()
                backend.firstSnapshotGate = CompletableDeferred()
                backend.malformedReplyNext = true
                expectError("PROTOCOL_ERROR") { if (create) repository.createRoom() else repository.joinRoom(" fd-example ") }
                assertFalse(repository.pendingCommand.value!!.retryAvailable)
                expectError("CONNECTION_NOT_READY") { repository.retryPendingCommand() }
                backend.firstSnapshotGate.complete(Unit)
                await { repository.pendingCommand.value?.retryAvailable == true }
                expectError("PENDING_COMMAND") { repository.joinRoom("OTHER") }
                repository.retryPendingCommand()
                assertNull(repository.pendingCommand.value)
                val writes = backend.writes
                assertEquals(2, writes.size); assertEquals(writes.first(), writes.last())
                assertNull(writes.first().revision)
                assertEquals(1, backend.commits.get())
            }
        }
    }

    @Test fun `server 500 after commit remains pending and is never retried automatically`() = runBlocking<Unit> {
        Backend().use { backend ->
            backend.login().use { repository ->
                repository.bootstrap(); await { repository.canMutate }
                backend.errorAfterCommitNext = true
                expectError("INTERNAL_ERROR") { repository.updateGoal("Цель") }
                await { repository.pendingCommand.value?.retryAvailable == true }
                delay(120)
                assertEquals(1, backend.writes.size)
                repository.retryPendingCommand()
                assertEquals(2, backend.writes.size)
                assertEquals(backend.writes.first(), backend.writes.last())
                assertEquals(1, backend.commits.get())
            }
        }
    }

    @Test fun `replay of lost create after partner closes room cannot resurrect cached open membership`() = runBlocking<Unit> {
        Backend(initialRoom = null).use { backend ->
            backend.login().use { repository ->
                repository.bootstrap()
                backend.malformedReplyNext = true
                expectError("PROTOCOL_ERROR") { repository.createRoom() }
                await { repository.pendingCommand.value?.retryAvailable == true }
                backend.room = backend.room!!.copy(status = "CLOSED", revision = 2)
                // No CLOSED event is delivered: the replay's cached OPEN reply is not current truth.
                assertEquals(RoomStatus.OPEN, repository.state.value.room!!.status)
                repository.retryPendingCommand()
                await { repository.state.value.room?.status == RoomStatus.CLOSED &&
                    repository.connectionState.value.status == RealConnectionStatus.NONE }
                assertEquals(RoomStatus.CLOSED, repository.state.value.room!!.status)
                assertFalse(repository.canMutate)
                assertNull(repository.pendingCommand.value)
                assertEquals(1, backend.commits.get())
            }
        }
    }

    @Test fun `cancelled dispatched command retains original intent and cancellation before gate dispatch does not replace it`() = runBlocking<Unit> {
        Backend().use { backend ->
            backend.login().use { repository ->
                repository.bootstrap(); await { repository.canMutate }
                val hold = CompletableDeferred<Unit>()
                backend.holdCommand = hold
                val command = launch { repository.addTask("Отменённое ожидание ответа") }
                backend.commandCommitted.await()
                val queued = launch { repository.addTask("Отмена до отправки") }
                delay(20)
                queued.cancelAndJoin()
                assertEquals(1, backend.writes.size)
                assertNull(repository.pendingCommand.value, "A coroutine cancelled at the mutex never dispatched another intent")
                command.cancelAndJoin()
                hold.complete(Unit)
                await { repository.pendingCommand.value?.retryAvailable == true }
                val pending = repository.pendingCommand.value
                expectError("PENDING_COMMAND") { repository.addTask("новый запрос") }
                assertEquals(pending, repository.pendingCommand.value)
                repository.retryPendingCommand()
                assertEquals(backend.writes.first(), backend.writes.last())
                assertEquals(1, backend.commits.get())
            }
        }
    }

    @Test fun `retry conflict applies authoritative snapshot clears pending and next explicit action gets a new key`() = runBlocking<Unit> {
        Backend().use { backend ->
            backend.login().use { repository ->
                repository.bootstrap(); await { repository.canMutate }
                backend.uncommittedFailureNext = true
                expectError("INTERNAL_ERROR") { repository.addTask("Не был применён") }
                await { repository.pendingCommand.value?.retryAvailable == true }
                backend.conflictNext = true
                expectError("REVISION_CONFLICT") { repository.retryPendingCommand() }
                assertNull(repository.pendingCommand.value)
                assertEquals(18L, repository.state.value.room!!.revision)
                val old = backend.writes.first()
                assertEquals(old, backend.writes[1])
                repository.addTask("Новое действие после проверки")
                assertNotEquals(old.key, backend.writes.last().key)
                assertEquals("18", backend.writes.last().revision)
            }
        }
    }

    @Test fun `automatic reconnect retries safe GETs with bounded delays and gates writes until full new snapshot`() = runBlocking<Unit> {
        Backend().use { backend ->
            val attempts = CopyOnWriteArrayList<Int>()
            backend.login { attempt -> attempts += attempt; 30 }.use { repository ->
                repository.bootstrap(); await { repository.canMutate }
                backend.firstSnapshotGate = CompletableDeferred()
                backend.currentReadFailures.set(2)
                backend.disconnect()
                await { attempts.size >= 3 }
                await { backend.connections.size == 2 }
                assertEquals(listOf(1, 2, 3), attempts.take(3))
                assertEquals(RealConnectionStatus.SYNCHRONIZING, repository.connectionState.value.status)
                assertFalse(repository.canMutate)
                expectError("CONNECTION_NOT_READY") { repository.addTask("Не отправлять до WS") }
                assertTrue(backend.writes.isEmpty())
                backend.firstSnapshotGate.complete(Unit)
                await { repository.canMutate }
                assertTrue(repository.recoveryVersion.value > 0)
                assertEquals(1, backend.requests.count { it.path.endsWith("/auth/login") })
                assertTrue(backend.requests.filter { it.method == HttpMethod.Get }.all { it.key == null && it.revision == null })
            }
        }
    }

    @Test fun `resume discards previous stream immediately and refreshes REST before full WS unlock`() = runBlocking<Unit> {
        Backend().use { backend ->
            backend.login().use { repository ->
                repository.bootstrap(); await { repository.canMutate }
                val original = backend.connections.last()
                backend.room = backend.room!!.copy(revision = 25, code = "AFTER-WAKE")
                backend.firstSnapshotGate = CompletableDeferred()
                repository.recoverAfterResume()
                assertFalse(repository.canMutate)
                await { backend.connections.size == 2 && repository.state.value.room!!.revision == 25L }
                assertFalse(repository.canMutate)
                withTimeout(2_000) { original.closeReason.await() }
                backend.firstSnapshotGate.complete(Unit)
                await { repository.canMutate }
                assertEquals("AFTER-WAKE", repository.state.value.room!!.code)
            }
        }
    }

    @Test fun `wake immediately gates mutations even while an earlier HTTP reply holds command mutex`() = runBlocking<Unit> {
        Backend().use { backend ->
            backend.login().use { repository ->
                repository.bootstrap(); await { repository.canMutate }
                val hold = CompletableDeferred<Unit>()
                backend.holdCommand = hold
                val command = async { repository.addTask("HTTP до сна") }
                backend.commandCommitted.await()
                backend.firstSnapshotGate = CompletableDeferred()
                withTimeout(500) { repository.recoverAfterResume() }
                assertFalse(repository.canMutate, "Resume must invalidate the old stream before waiting for HTTP")
                hold.complete(Unit)
                command.await()
                await { backend.connections.size == 2 }
                assertFalse(repository.canMutate)
                backend.firstSnapshotGate.complete(Unit)
                await { repository.canMutate }
            }
        }
    }

    @Test fun `wake with no known room gates create and join until authoritative membership GET completes`() = runBlocking<Unit> {
        Backend(initialRoom = null).use { backend ->
            backend.login().use { repository ->
                assertFalse(repository.canEnterRoom)
                repository.bootstrap()
                assertTrue(repository.canEnterRoom)
                val hold = CompletableDeferred<Unit>()
                backend.holdCurrentRead = hold
                repository.recoverAfterResume()
                assertFalse(repository.canEnterRoom)
                backend.currentReadStarted.await()
                withTimeout(500) {
                    expectError("CONNECTION_NOT_READY") { repository.createRoom() }
                    expectError("CONNECTION_NOT_READY") { repository.joinRoom("FD-EXAMPLE") }
                }
                assertTrue(backend.writes.isEmpty())
                hold.complete(Unit)
                await { repository.canEnterRoom }
                assertEquals(RealConnectionStatus.NONE, repository.connectionState.value.status)
                repository.createRoom()
                assertEquals(1, backend.writes.size)
            }
        }
    }

    @Test fun `closed room while offline is restored and expected closure stops reconnect attempts`() = runBlocking<Unit> {
        Backend().use { backend ->
            backend.login().use { repository ->
                repository.bootstrap(); await { repository.canMutate }
                backend.room = backend.room!!.copy(status = "CLOSED", revision = 18)
                backend.disconnect()
                await { repository.state.value.room?.status == RoomStatus.CLOSED }
                assertEquals(RealConnectionStatus.NONE, repository.connectionState.value.status)
                delay(120)
                assertEquals(1, backend.connections.size)
                assertFalse(repository.canMutate)
            }
        }
    }

    @Test fun `protocol errors pause automatic reconnect and explicit recovery starts a new stream`() = runBlocking<Unit> {
        Backend().use { backend ->
            backend.login().use { repository ->
                repository.bootstrap(); await { repository.canMutate }
                backend.connections.last().send(Frame.Text("{\"type\":\"UNKNOWN_EVENT\"}"))
                await { repository.connectionState.value.status == RealConnectionStatus.DISCONNECTED }
                delay(120)
                assertEquals(1, backend.connections.size)
                assertFalse(repository.canMutate)
                assertFalse(repository.connectionState.value.message.orEmpty().contains(TOKEN))
                repository.reconnect(); await { repository.canMutate }
                assertEquals(2, backend.connections.size)
            }
        }
    }

    @Test fun `forbidden websocket handshake pauses with readable error instead of endless retry`() = runBlocking<Unit> {
        Backend(rejectHandshake = HttpStatusCode.Forbidden).use { backend ->
            backend.login().use { repository ->
                repository.bootstrap()
                await { repository.connectionState.value.status == RealConnectionStatus.DISCONNECTED }
                delay(120)
                assertEquals(1, backend.handshakeAttempts.get())
                assertEquals(RealSessionStatus.ACTIVE, repository.sessionStatus.value)
                assertFalse(repository.canMutate)
                assertTrue(repository.connectionState.value.message!!.contains("прав"))
            }
        }
    }

    @Test fun `4401 and logout cancel reconnect and erase pending intents and credentials`() = runBlocking<Unit> {
        for (expire in listOf(true, false)) Backend().use { backend ->
            backend.login().use { repository ->
                repository.bootstrap(); await { repository.canMutate }
                backend.malformedReplyNext = true
                expectError("PROTOCOL_ERROR") { repository.addTask("Задача") }
                await { repository.pendingCommand.value?.retryAvailable == true }
                if (expire) backend.connections.last().close(CloseReason(4401, "expired")) else repository.logout()
                await { repository.sessionStatus.value != RealSessionStatus.ACTIVE }
                assertNull(repository.pendingCommand.value)
                assertNull(repository.state.value.room)
                val count = backend.requests.size
                delay(120)
                assertEquals(count, backend.requests.size)
                expectError("UNAUTHORIZED") { repository.retryPendingCommand() }
            }
        }
    }

    @Test fun `default backoff applies jitter and caps delays at thirty seconds`() {
        repeat(100) {
            assertTrue(RealRepository.defaultReconnectDelay(1) in 800..1200)
            assertTrue(RealRepository.defaultReconnectDelay(3) in 3200..4800)
            assertTrue(RealRepository.defaultReconnectDelay(30) in 24000..30000)
        }
    }

    private suspend fun expectError(code: String, action: suspend () -> Any?): RepositoryException {
        try { action(); fail("Expected $code") } catch (error: RepositoryException) { assertEquals(code, error.code); return error }
    }
    private suspend fun await(predicate: () -> Boolean) = withTimeout(7_000) { while (!predicate()) delay(10) }

    private data class Request(val path: String, val method: HttpMethod, val key: String?, val revision: String?, val body: String)
    private class Backend(initialRoom: RoomSnapshotDto? = sampleRoom(), rejectHandshake: HttpStatusCode? = null) : AutoCloseable {
        @Volatile var room = initialRoom
        @Volatile var malformedReplyNext = false
        @Volatile var errorAfterCommitNext = false
        @Volatile var uncommittedFailureNext = false
        @Volatile var conflictNext = false
        @Volatile var firstSnapshotGate = CompletableDeferred(Unit)
        @Volatile var holdCommand: CompletableDeferred<Unit>? = null
        @Volatile var holdCurrentRead: CompletableDeferred<Unit>? = null
        val currentReadStarted = CompletableDeferred<Unit>()
        val commandCommitted = CompletableDeferred<Unit>()
        val currentReadFailures = AtomicInteger()
        val commits = AtomicInteger()
        val handshakeAttempts = AtomicInteger()
        val requests = CopyOnWriteArrayList<Request>()
        val writes: List<Request> get() = requests.filter { it.key != null }
        val connections = CopyOnWriteArrayList<DefaultWebSocketServerSession>()
        private val saved = ConcurrentHashMap<String, Pair<Request, RoomSnapshotDto>>()
        private val server = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
            install(WebSockets)
            routing {
                route("/api/v1/{rest...}") {
                    handle {
                        val request = Request(call.request.path(), call.request.httpMethod, call.request.headers["Idempotency-Key"],
                            call.request.headers["X-Room-Revision"], call.receiveText())
                        requests += request
                        var status = HttpStatusCode.OK
                        val body = when {
                            request.path.endsWith("/auth/login") -> fixture("auth-session")
                            request.path.endsWith("/auth/logout") -> { status = HttpStatusCode.NoContent; "" }
                            request.path == "/api/v1/me" -> FocusDuoJson.encodeToString(UserDto.serializer(),
                                FocusDuoJson.decodeFromString<AuthSessionDto>(fixture("auth-session")).user)
                            request.path == "/api/v1/rooms/current" -> {
                                holdCurrentRead?.let { currentReadStarted.complete(Unit); it.await() }
                                if (currentReadFailures.getAndUpdate { (it - 1).coerceAtLeast(0) } > 0) {
                                    status = HttpStatusCode.ServiceUnavailable; "unavailable"
                                } else FocusDuoJson.encodeToString(CurrentRoomDto.serializer(), CurrentRoomDto(room?.takeIf { it.status == "OPEN" }))
                            }
                            request.key == null -> FocusDuoJson.encodeToString(RoomSnapshotDto.serializer(), room!!)
                            uncommittedFailureNext -> { uncommittedFailureNext = false; status = HttpStatusCode.InternalServerError; "failed" }
                            conflictNext -> {
                                conflictNext = false
                                room = room!!.copy(revision = room!!.revision + 1)
                                status = HttpStatusCode.Conflict
                                FocusDuoJson.encodeToString(ApiErrorDto.serializer(), ApiErrorDto("REVISION_CONFLICT", "unsafe $TOKEN", "request", emptyMap(), room))
                            }
                            else -> {
                                val cached = saved[request.key!!]
                                val result = if (cached != null) {
                                    check(cached.first == request) { "An idempotent replay changed the original request" }
                                    cached.second
                                } else {
                                    val updated = (room ?: sampleRoom().copy(revision = 0)).let {
                                        it.copy(revision = it.revision + 1, status = if (request.path.endsWith("/close")) "CLOSED" else it.status)
                                    }
                                    room = updated
                                    saved[request.key] = request to updated
                                    commits.incrementAndGet()
                                    commandCommitted.complete(Unit)
                                    holdCommand?.await()
                                    updated
                                }
                                when {
                                    malformedReplyNext -> { malformedReplyNext = false; "{\"unexpected\":true}" }
                                    errorAfterCommitNext -> { errorAfterCommitNext = false; status = HttpStatusCode.InternalServerError; "failed after commit" }
                                    else -> FocusDuoJson.encodeToString(RoomSnapshotDto.serializer(), result)
                                }
                            }
                        }
                        call.respondText(body, ContentType.Application.Json, status)
                    }
                }
                if (rejectHandshake != null) get("/ws/rooms/{roomId}") {
                    handshakeAttempts.incrementAndGet()
                    call.respondText("unsafe $TOKEN", status = rejectHandshake)
                } else webSocket("/ws/rooms/{roomId}") {
                    handshakeAttempts.incrementAndGet()
                    connections += this
                    firstSnapshotGate.await()
                    room?.let { send(Frame.Text(FocusDuoJson.encodeToString(RoomEventDto.serializer(), RoomEventDto("ROOM_SNAPSHOT", it)))) }
                    incoming.consumeEach { }
                }
            }
        }.start(wait = false)
        suspend fun login(backoff: (Int) -> Long = { 20 }): RealRepository = RealRepository.login(
            "http://127.0.0.1:${server.engine.resolvedConnectors().first().port}", "daniyar", "Password123!", reconnectDelay = backoff)
        suspend fun send(value: RoomSnapshotDto) = connections.last().send(Frame.Text(
            FocusDuoJson.encodeToString(RoomEventDto.serializer(), RoomEventDto("ROOM_SNAPSHOT", value))))
        suspend fun disconnect() = connections.last().close(CloseReason(CloseReason.Codes.GOING_AWAY, "network lost"))
        override fun close() { holdCommand?.complete(Unit); holdCurrentRead?.complete(Unit); firstSnapshotGate.complete(Unit); server.stop(0, 1_000) }
    }

    companion object {
        private const val TOKEN = "example-token-for-contract-tests-only"
        private fun fixture(name: String) = Stage7RecoveryLoopbackTest::class.java.getResource("/contracts/$name.json")!!.readText()
        private fun sampleRoom(): RoomSnapshotDto = FocusDuoJson.decodeFromString(fixture("room-running"))
    }
}
