package dev.focusduo.data

import dev.focusduo.data.remote.*
import dev.focusduo.domain.*
import io.ktor.client.engine.HttpClientEngine
import io.ktor.http.HttpMethod
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.DateTimeException
import java.time.Instant
import java.util.Locale
import java.util.UUID

enum class RealSessionStatus { ACTIVE, EXPIRED, SIGNED_OUT }
enum class RealConnectionStatus { NONE, CONNECTING, SYNCHRONIZING, CONNECTED, DISCONNECTED }
data class RealConnectionState(val status: RealConnectionStatus = RealConnectionStatus.NONE, val message: String? = null)

/** Authoritative REST commands and full room snapshots; never manufactures demo data. */
class RealRepository private constructor(
    private val http: BackendHttpClient,
    user: User,
    val expiresAt: Instant,
    private val realtimeEnabled: Boolean,
) : FocusDuoRepository, AutoCloseable {
    val baseUrl: String get() = http.baseUrl
    private val mutableState = MutableStateFlow(RepositoryState(user))
    override val state: StateFlow<RepositoryState> = mutableState.asStateFlow()
    private val mutableSessionStatus = MutableStateFlow(RealSessionStatus.ACTIVE)
    val sessionStatus: StateFlow<RealSessionStatus> = mutableSessionStatus.asStateFlow()
    private val mutableConnection = MutableStateFlow(RealConnectionState())
    val connectionState: StateFlow<RealConnectionState> = mutableConnection.asStateFlow()
    val serverTimer = ServerTimer()
    val canMutate: Boolean get() = mutableConnection.value.status == RealConnectionStatus.CONNECTED &&
        mutableState.value.room?.status == RoomStatus.OPEN && mutableSessionStatus.value == RealSessionStatus.ACTIVE
    private val gate = Mutex()
    private val stateLock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var streamJob: Job? = null
    private var streamClient: RoomRealtimeClient? = null
    private var streamRoomId: String? = null
    private var generation = 0L
    private var membershipUncertain = false

    fun projectedRoom(): RoomSnapshot? = mutableState.value.room?.let(serverTimer::displayRoom)

    /** Separate restoration from authentication so retry never repeats login/register POST. */
    suspend fun bootstrap() = gate.withLock {
        authorized {
            val user = mapResponse { http.me().toDomain() }
            requireSameUser(user)
            val room = readCurrentRoom()
            applyCurrentRoom(room, user)
            membershipUncertain = false
            connectCurrentRoom()
        }
    }

    suspend fun refreshMe(): User = gate.withLock {
        authorized {
            val user = mapResponse { http.me().toDomain() }
            requireSameUser(user)
            synchronized(stateLock) {
                ensureActive()
                mutableState.value = mutableState.value.copy(currentUser = user)
            }
            user
        }
    }

    suspend fun refreshCurrentRoom(): RoomSnapshot? = gate.withLock {
        authorized {
            val room = readCurrentRoom()
            val applied = applyCurrentRoom(room)
            membershipUncertain = false
            connectCurrentRoom()
            applied
        }
    }

    /** Closed snapshots remain readable for former participants; GET does not rejoin a room. */
    suspend fun refreshRoom(roomId: String): RoomSnapshot = gate.withLock {
        authorized {
            val room = mapResponse { http.room(roomId).toDomain() }
            validateRoom(room, roomId)
            val current = mutableState.value.room
            if (current?.status == RoomStatus.OPEN && current.roomId != roomId) {
                // Reading another former room must not replace active membership or its stream.
                return@authorized room
            }
            val applied = applyCurrentRoom(room)!!
            connectCurrentRoom()
            applied
        }
    }

    /** Manual resubscription. Mutations stay disabled until a new full snapshot arrives. */
    suspend fun reconnect() = gate.withLock {
        authorized { connectCurrentRoom(force = true) }
    }

    suspend fun loadHistory(): List<HistoryItem> = gate.withLock {
        authorized {
            val items = mapResponse { http.history().items.map(HistoryItemDto::toDomain) }
            if (items.size > 50 || items.map { it.roundId }.distinct().size != items.size ||
                items.any { item -> item.participants.none { it.userId == mutableState.value.currentUser.id } }) {
                throw BackendHttpClient.protocolError()
            }
            synchronized(stateLock) {
                ensureActive()
                val known = mutableState.value.history.associateBy { it.roundId }
                if (items.any { known[it.roundId]?.let { saved -> saved != it } == true }) {
                    throw BackendHttpClient.protocolError()
                }
                // The authoritative endpoint's stable order is preserved.
                mutableState.value = mutableState.value.copy(history = items)
            }
            items
        }
    }

    suspend fun loadHistoryItem(roundId: String): HistoryItem = gate.withLock {
        authorized {
            val item = mapResponse { http.historyItem(roundId).toDomain() }
            if (item.roundId != roundId || item.participants.none { it.userId == mutableState.value.currentUser.id }) {
                throw BackendHttpClient.protocolError()
            }
            synchronized(stateLock) {
                ensureActive()
                val previous = mutableState.value.history
                val saved = previous.firstOrNull { it.roundId == roundId }
                if (saved != null && saved != item) throw BackendHttpClient.protocolError()
                mutableState.value = mutableState.value.copy(history =
                    (previous.filterNot { it.roundId == roundId } + item)
                        .sortedWith(compareByDescending<HistoryItem> { it.endedAt }.thenByDescending { it.roundId }).take(50))
            }
            item
        }
    }

    /** Clears local credentials even when remote logout fails; never closes the room. */
    suspend fun logout() {
        try {
            gate.withLock {
                if (mutableSessionStatus.value == RealSessionStatus.ACTIVE) authorized { http.logout() }
            }
        } finally {
            clearSession(RealSessionStatus.SIGNED_OUT)
            http.close()
        }
    }

    override suspend fun createRoom(focusDurationSeconds: Int, breakDurationSeconds: Int): RoomSnapshot {
        validateDurations(focusDurationSeconds, breakDurationSeconds)
        return enterRoom("/rooms", FocusDuoJson.encodeToString(CreateRoomRequest(focusDurationSeconds, breakDurationSeconds)))
    }

    override suspend fun joinRoom(code: String): RoomSnapshot {
        val normalized = code.trim().uppercase(Locale.ROOT)
        if (normalized.isEmpty()) invalid("code", "Введите код комнаты.")
        return enterRoom("/rooms/join", FocusDuoJson.encodeToString(JoinRoomRequest(normalized)))
    }

    override suspend fun updateGoal(goal: String): RoomSnapshot {
        if (AuthValidation.characterCount(goal) > 240) invalid("goal", "Цель — не более 240 символов.")
        return roomCommand(HttpMethod.Patch, "/me", FocusDuoJson.encodeToString(GoalRequest(goal)))
    }

    override suspend fun addTask(title: String): RoomSnapshot =
        roomCommand(HttpMethod.Post, "/tasks", FocusDuoJson.encodeToString(TaskRequest(validateTitle(title))))

    override suspend fun updateTask(taskId: String, title: String?, completed: Boolean?): RoomSnapshot {
        BackendHttpClient.requireUuid(taskId)
        if (title == null && completed == null) invalid("task", "Измените название или статус задачи.")
        return roomCommand(HttpMethod.Patch, "/tasks/$taskId",
            FocusDuoJson.encodeToString(TaskPatchRequest(title?.let(::validateTitle), completed)))
    }

    override suspend fun deleteTask(taskId: String): RoomSnapshot {
        BackendHttpClient.requireUuid(taskId)
        return roomCommand(HttpMethod.Delete, "/tasks/$taskId")
    }

    override suspend fun updateSettings(focusDurationSeconds: Int, breakDurationSeconds: Int): RoomSnapshot {
        validateDurations(focusDurationSeconds, breakDurationSeconds)
        return roomCommand(HttpMethod.Patch, "/settings",
            FocusDuoJson.encodeToString(CreateRoomRequest(focusDurationSeconds, breakDurationSeconds)))
    }

    override suspend fun startRound(kind: RoundKind): RoomSnapshot =
        roomCommand(HttpMethod.Post, "/rounds", FocusDuoJson.encodeToString(StartRoundRequest(kind.name)))

    override suspend fun pauseRound(roundId: String): RoomSnapshot = roundCommand(roundId, "pause")
    override suspend fun resumeRound(roundId: String): RoomSnapshot = roundCommand(roundId, "resume")
    override suspend fun finishRound(roundId: String): RoomSnapshot = roundCommand(roundId, "finish")
    override suspend fun closeRoom(): RoomSnapshot = roomCommand(HttpMethod.Post, "/close")

    private suspend fun roundCommand(roundId: String, action: String): RoomSnapshot {
        BackendHttpClient.requireUuid(roundId)
        return roomCommand(HttpMethod.Post, "/rounds/$roundId/$action")
    }

    private suspend fun enterRoom(path: String, body: String): RoomSnapshot = gate.withLock {
        authorized {
            if (membershipUncertain) {
                throw RepositoryException("ROOM_RESTORE_REQUIRED", "Сначала обновите текущую комнату: результат предыдущего запроса неизвестен.")
            }
            if (path == "/rooms" && mutableState.value.room?.status == RoomStatus.OPEN) {
                throw RepositoryException("ALREADY_IN_ROOM", "Вы уже состоите в открытой комнате.")
            }
            try {
                val room = mapResponse { http.command(HttpMethod.Post, path, body, UUID.randomUUID().toString()).toDomain() }
                validateCurrentRoom(room)
                val applied = applyCurrentRoom(room)!!
                connectCurrentRoom()
                applied
            } catch (error: RepositoryException) {
                if (error.code in AMBIGUOUS_FAILURES) {
                    membershipUncertain = true
                    markDisconnected("Результат запроса неизвестен. Обновите текущую комнату перед новым действием.")
                }
                throw error
            }
        }
    }

    private suspend fun roomCommand(method: HttpMethod, suffix: String, body: String? = null): RoomSnapshot = gate.withLock {
        authorized {
            val current = synchronized(stateLock) {
                ensureActive()
                if (!canMutate) throw RepositoryException("CONNECTION_NOT_READY",
                    "Дождитесь полного снимка комнаты после подключения. При обрыве восстановите соединение.")
                mutableState.value.room!!
            }
            try {
                // One UUID per explicit action. No automatic replay or retry with a new revision/key.
                val room = mapResponse {
                    http.command(method, "/rooms/${current.roomId}$suffix", body,
                        UUID.randomUUID().toString(), current.revision).toDomain()
                }
                validateRoom(room, current.roomId)
                val applied = applyCurrentRoom(room)!!
                if (applied.status == RoomStatus.CLOSED) stopStream()
                applied
            } catch (error: RepositoryException) {
                if (error.code == "REVISION_CONFLICT" && error.snapshot != null) {
                    validateRoom(error.snapshot, current.roomId)
                    val applied = applyCurrentRoom(error.snapshot)
                    if (applied?.status == RoomStatus.CLOSED) stopStream()
                }
                if (error.code in AMBIGUOUS_FAILURES) {
                    markDisconnected("Результат команды неизвестен. Восстановите соединение для получения полного снимка.")
                }
                throw error
            }
        }
    }

    private fun requireSameUser(user: User) {
        if (user.id != mutableState.value.currentUser.id) throw BackendHttpClient.protocolError()
    }

    private fun validateCurrentRoom(room: RoomSnapshot?) {
        if (room != null) {
            if (room.status != RoomStatus.OPEN) throw BackendHttpClient.protocolError()
            validateRoom(room, room.roomId)
        }
    }

    private suspend fun readCurrentRoom(): RoomSnapshot? {
        val room = mapResponse { http.currentRoom().room?.toDomain() }
        validateCurrentRoom(room)
        if (room != null) return room
        // A room closed by the partner has no current membership, but remains readable.
        val previous = mutableState.value.room ?: return null
        return if (previous.status == RoomStatus.CLOSED) previous
            else mapResponse { http.room(previous.roomId).toDomain() }.also { validateRoom(it, previous.roomId) }
    }

    private fun validateRoom(room: RoomSnapshot, roomId: String) {
        if (room.roomId != roomId || room.participants.none { it.userId == mutableState.value.currentUser.id }) {
            throw BackendHttpClient.protocolError()
        }
    }

    private fun applyCurrentRoom(room: RoomSnapshot?, user: User? = null): RoomSnapshot? = synchronized(stateLock) {
        ensureActive()
        val previous = mutableState.value.room
        val applied = room?.let { RoomSnapshotReducer.reduce(previous, it) }
        if (applied !== previous) {
            if (applied == null) serverTimer.clear() else serverTimer.onSnapshot(applied)
        }
        mutableState.value = mutableState.value.copy(currentUser = user ?: mutableState.value.currentUser, room = applied)
        applied
    }

    private fun connectCurrentRoom(force: Boolean = false) = synchronized(stateLock) {
        ensureActive()
        val room = mutableState.value.room
        if (room == null || room.status == RoomStatus.CLOSED || !realtimeEnabled) {
            stopStream()
            return@synchronized
        }
        if (!force && streamRoomId == room.roomId && streamJob?.isActive == true) return@synchronized
        stopStream()
        val connectionGeneration = generation
        streamRoomId = room.roomId
        mutableConnection.value = RealConnectionState(RealConnectionStatus.CONNECTING)
        val transport = RoomRealtimeClient(baseUrl, http::tokenForHandshake)
        streamClient = transport
        streamJob = scope.launch {
            try {
                val reason = transport.listen(room.roomId,
                    onHandshake = {
                        synchronized(stateLock) {
                            if (isCurrentStream(connectionGeneration, room.roomId)) {
                                mutableConnection.value = RealConnectionState(RealConnectionStatus.SYNCHRONIZING)
                            }
                        }
                    },
                    onSnapshot = { dto ->
                        val incoming = try { dto.toDomain() } catch (_: Exception) { throw BackendHttpClient.protocolError() }
                        synchronized(stateLock) {
                            if (!isCurrentStream(connectionGeneration, room.roomId)) false else {
                                validateRoom(incoming, room.roomId)
                                val previous = mutableState.value.room!!
                                val applied = applyCurrentRoom(incoming)!!
                                mutableConnection.value = if (applied.status == RoomStatus.CLOSED) RealConnectionState()
                                    else if (incoming.revision >= previous.revision) RealConnectionState(RealConnectionStatus.CONNECTED)
                                    else mutableConnection.value
                                applied.status == RoomStatus.OPEN
                            }
                        }
                    },
                    isSynchronized = { mutableConnection.value.status == RealConnectionStatus.CONNECTED },
                )
                synchronized(stateLock) {
                    if (isCurrentStream(connectionGeneration, room.roomId)) {
                        if (reason?.code == 4401.toShort()) clearSession(RealSessionStatus.EXPIRED)
                        else if (mutableState.value.room?.status == RoomStatus.OPEN) {
                            mutableConnection.value = RealConnectionState(RealConnectionStatus.DISCONNECTED,
                                "Соединение с комнатой прервано. Восстановите соединение.")
                        }
                    }
                }
            } catch (_: TimeoutCancellationException) {
                synchronized(stateLock) {
                    if (isCurrentStream(connectionGeneration, room.roomId)) {
                        mutableConnection.value = RealConnectionState(RealConnectionStatus.DISCONNECTED,
                            "Сервер не прислал полный снимок комнаты вовремя. Восстановите соединение.")
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                synchronized(stateLock) {
                    if (isCurrentStream(connectionGeneration, room.roomId)) {
                        if (error is RepositoryException && error.code == "UNAUTHORIZED") clearSession(RealSessionStatus.EXPIRED)
                        else mutableConnection.value = RealConnectionState(RealConnectionStatus.DISCONNECTED,
                            if (error is RepositoryException && error.code == "PROTOCOL_ERROR") error.message
                            else "Не удалось подключиться к комнате. Проверьте сервер и восстановите соединение.")
                    }
                }
            } finally {
                transport.close()
            }
        }
    }

    private fun isCurrentStream(expectedGeneration: Long, roomId: String) =
        generation == expectedGeneration && streamRoomId == roomId && mutableState.value.room?.roomId == roomId &&
            mutableSessionStatus.value == RealSessionStatus.ACTIVE

    private fun markDisconnected(message: String) = synchronized(stateLock) {
        stopStream()
        mutableConnection.value = RealConnectionState(RealConnectionStatus.DISCONNECTED, message)
    }

    private fun stopStream() = synchronized(stateLock) {
        generation++
        streamRoomId = null
        streamJob?.cancel()
        streamJob = null
        streamClient?.close()
        streamClient = null
        mutableConnection.value = RealConnectionState()
    }

    private suspend inline fun <T> authorized(block: () -> T): T {
        ensureActive()
        return try { block() } catch (error: RepositoryException) {
            if (error.code == "UNAUTHORIZED") clearSession(RealSessionStatus.EXPIRED)
            throw error
        }
    }

    private fun ensureActive() {
        if (mutableSessionStatus.value != RealSessionStatus.ACTIVE) throw BackendHttpClient.unauthorized()
    }

    private fun clearSession(status: RealSessionStatus) = synchronized(stateLock) {
        stopStream()
        scope.cancel()
        http.clearToken()
        membershipUncertain = false
        serverTimer.clear()
        mutableState.value = RepositoryState(mutableState.value.currentUser)
        if (mutableSessionStatus.value != RealSessionStatus.SIGNED_OUT) mutableSessionStatus.value = status
    }

    override fun close() {
        clearSession(RealSessionStatus.SIGNED_OUT)
        http.close()
    }

    companion object {
        private val AMBIGUOUS_FAILURES = setOf("NETWORK_ERROR", "NETWORK_TIMEOUT", "PROTOCOL_ERROR")
        suspend fun login(baseUrl: String, username: String, password: String, engine: HttpClientEngine? = null,
                          realtimeEnabled: Boolean = true): RealRepository {
            val credentials = AuthValidation.login(username, password)
            return authenticate(baseUrl, engine, realtimeEnabled) { login(LoginRequestDto(credentials.username, credentials.password)) }
        }

        suspend fun register(baseUrl: String, username: String, displayName: String, password: String,
                             engine: HttpClientEngine? = null, realtimeEnabled: Boolean = true): RealRepository {
            val credentials = AuthValidation.registration(username, displayName, password)
            return authenticate(baseUrl, engine, realtimeEnabled) {
                register(RegisterRequestDto(credentials.username, credentials.displayName, credentials.password))
            }
        }

        private suspend fun authenticate(baseUrl: String, engine: HttpClientEngine?, realtimeEnabled: Boolean,
                                         request: suspend BackendHttpClient.() -> AuthSessionDto): RealRepository {
            val http = BackendHttpClient(baseUrl, engine)
            try {
                val session = http.request()
                val user = mapResponse { session.user.toDomain() }
                val expiresAt = mapResponse { Instant.parse(session.expiresAt) }
                http.installToken(session.accessToken)
                return RealRepository(http, user, expiresAt, realtimeEnabled)
            } catch (error: Throwable) {
                http.close()
                throw error
            }
        }

        private suspend inline fun <T> mapResponse(block: () -> T): T = try { block() } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (expected: RepositoryException) { throw expected
        } catch (_: IllegalArgumentException) { throw BackendHttpClient.protocolError()
        } catch (_: DateTimeException) { throw BackendHttpClient.protocolError() }

        private fun invalid(field: String, message: String): Nothing =
            throw RepositoryException("VALIDATION_ERROR", "Проверьте заполненные поля.", mapOf(field to message))

        private fun validateTitle(title: String): String = title.trim().also {
            if (AuthValidation.characterCount(it) !in 1..160) invalid("title", "Задача — от 1 до 160 символов.")
        }

        private fun validateDurations(focus: Int, rest: Int) {
            if (focus !in 300..10800 || focus % 60 != 0) invalid("focusDurationSeconds", "Фокус: от 5 до 180 минут.")
            if (rest !in 60..3600 || rest % 60 != 0) invalid("breakDurationSeconds", "Перерыв: от 1 до 60 минут.")
        }
    }
}
