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
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
import kotlin.random.Random

enum class RealSessionStatus { ACTIVE, EXPIRED, SIGNED_OUT }
enum class RealConnectionStatus { NONE, CONNECTING, SYNCHRONIZING, CONNECTED, DISCONNECTED, RECONNECTING }
data class RealConnectionState(val status: RealConnectionStatus = RealConnectionStatus.NONE, val message: String? = null,
                               val retryAttempt: Int = 0, val retryInMillis: Long? = null)

/** Authoritative REST commands and full room snapshots; never manufactures demo data. */
class RealRepository private constructor(
    private val http: BackendHttpClient,
    user: User,
    val expiresAt: Instant,
    private val realtimeEnabled: Boolean,
    private val reconnectDelay: (Int) -> Long,
) : FocusDuoRepository, AutoCloseable {
    val baseUrl: String get() = http.baseUrl
    private val mutableState = MutableStateFlow(RepositoryState(user))
    override val state: StateFlow<RepositoryState> = mutableState.asStateFlow()
    private val mutableSessionStatus = MutableStateFlow(RealSessionStatus.ACTIVE)
    val sessionStatus: StateFlow<RealSessionStatus> = mutableSessionStatus.asStateFlow()
    private val mutableConnection = MutableStateFlow(RealConnectionState())
    val connectionState: StateFlow<RealConnectionState> = mutableConnection.asStateFlow()
    private val mutablePending = MutableStateFlow<PendingCommandInfo?>(null)
    val pendingCommand: StateFlow<PendingCommandInfo?> = mutablePending.asStateFlow()
    private val mutableRecoveryVersion = MutableStateFlow(0L)
    /** Advances only after recovery has revalidated identity and authoritative membership. */
    val recoveryVersion: StateFlow<Long> = mutableRecoveryVersion.asStateFlow()
    val serverTimer = ServerTimer()
    val canMutate: Boolean get() = mutableConnection.value.status == RealConnectionStatus.CONNECTED &&
        mutableState.value.room?.status == RoomStatus.OPEN && mutableSessionStatus.value == RealSessionStatus.ACTIVE &&
        mutablePending.value == null
    val canEnterRoom: Boolean get() = membershipReady && mutableSessionStatus.value == RealSessionStatus.ACTIVE &&
        mutablePending.value == null && mutableConnection.value.status in setOf(RealConnectionStatus.NONE, RealConnectionStatus.CONNECTED)
    private val gate = Mutex()
    private val stateLock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var streamJob: Job? = null
    private var streamClient: RoomRealtimeClient? = null
    private var streamRoomId: String? = null
    @Volatile private var generation = 0L
    @Volatile private var pendingRequest: PendingRequest? = null
    @Volatile private var membershipReady = false

    fun projectedRoom(): RoomSnapshot? = mutableState.value.room?.let(serverTimer::displayRoom)

    /** Separate restoration from authentication so retry never repeats login/register POST. */
    suspend fun bootstrap() = gate.withLock {
        try { authorized {
            val user = mapResponse { http.me().toDomain() }
            requireSameUser(user)
            val room = readCurrentRoom()
            applyCurrentRoom(room, user)
            markRestSynchronized(room)
            connectCurrentRoom()
        } } catch (error: RepositoryException) {
            recoverAfterReadFailure(error)
            throw error
        }
    }

    suspend fun refreshMe(): User = gate.withLock {
        try { authorized {
            val user = mapResponse { http.me().toDomain() }
            requireSameUser(user)
            synchronized(stateLock) {
                ensureActive()
                mutableState.value = mutableState.value.copy(currentUser = user)
            }
            user
        } } catch (error: RepositoryException) {
            recoverAfterReadFailure(error)
            throw error
        }
    }

    suspend fun refreshCurrentRoom(): RoomSnapshot? = gate.withLock {
        try { authorized {
            val room = readCurrentRoom()
            val applied = applyCurrentRoom(room)
            markRestSynchronized(room)
            connectCurrentRoom(force = mutablePending.value != null)
            applied
        } } catch (error: RepositoryException) {
            recoverAfterReadFailure(error)
            throw error
        }
    }

    private fun recoverAfterReadFailure(error: RepositoryException) {
        if (error.code in RECOVERABLE_READ_FAILURES && mutableSessionStatus.value == RealSessionStatus.ACTIVE) {
            connectCurrentRoom(force = true, syncFirst = true)
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

    /** The same recovery path serves manual retry and resume; no command is replayed automatically. */
    suspend fun reconnect() {
        // Gate immediately, even while an old HTTP request is waiting for its reply.
        ensureActive()
        connectCurrentRoom(force = true, syncFirst = true)
    }

    suspend fun recoverAfterResume() = reconnect()

    /** Replays precisely the retained intent, including its original key and revision. */
    suspend fun retryPendingCommand(): RoomSnapshot = gate.withLock {
        authorized {
            val request = pendingRequest ?: throw RepositoryException("NO_PENDING_COMMAND", "Нет запроса для повторной проверки.")
            if (mutablePending.value?.retryAvailable != true) throw RepositoryException("CONNECTION_NOT_READY",
                "Сначала дождитесь восстановления состояния с сервера.")
            executeCommand(request, replay = true)
        }
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

    private data class PendingRequest(
        val method: HttpMethod,
        val path: String,
        val body: String?,
        val key: String,
        val revision: Long?,
        val roomId: String?,
        val description: String,
    )

    private fun requireNoPending() {
        if (pendingRequest != null) throw RepositoryException("PENDING_COMMAND",
            "Результат предыдущей команды неизвестен. Сначала восстановите соединение и повторите исходный запрос.")
    }

    private suspend fun enterRoom(path: String, body: String): RoomSnapshot {
        // Do not queue an intent entered against membership that is still being restored.
        ensureActive()
        requireNoPending()
        requireMembershipReady()
        return gate.withLock { authorized {
            requireNoPending()
            requireMembershipReady()
            if (path == "/rooms" && mutableState.value.room?.status == RoomStatus.OPEN) {
                throw RepositoryException("ALREADY_IN_ROOM", "Вы уже состоите в открытой комнате.")
            }
            executeCommand(PendingRequest(HttpMethod.Post, path, body, UUID.randomUUID().toString(), null, null,
                if (path == "/rooms") "Создание комнаты" else "Вход в комнату"))
        } }
    }

    private fun requireMembershipReady() {
        if (!canEnterRoom) throw RepositoryException("CONNECTION_NOT_READY",
            "Дождитесь восстановления текущей комнаты с сервера перед новым действием.")
    }

    private suspend fun roomCommand(method: HttpMethod, suffix: String, body: String? = null): RoomSnapshot = gate.withLock {
        authorized {
            requireNoPending()
            val current = synchronized(stateLock) {
                ensureActive()
                if (!canMutate) throw RepositoryException("CONNECTION_NOT_READY",
                    "Дождитесь полного снимка комнаты после восстановления подключения.")
                mutableState.value.room!!
            }
            val description = when {
                suffix == "/me" -> "Изменение цели"
                suffix.startsWith("/tasks") -> "Изменение задачи"
                suffix == "/settings" -> "Изменение настроек комнаты"
                suffix == "/close" -> "Закрытие комнаты"
                else -> "Управление раундом"
            }
            executeCommand(PendingRequest(method, "/rooms/${current.roomId}$suffix", body,
                UUID.randomUUID().toString(), current.revision, current.roomId, description))
        }
    }

    private suspend fun executeCommand(request: PendingRequest, replay: Boolean = false): RoomSnapshot {
        try {
            val room = mapResponse {
                http.command(request.method, request.path, request.body, request.key, request.revision).toDomain()
            }
            if (request.roomId != null) validateRoom(room, request.roomId) else validateRoom(room, room.roomId)
            synchronized(stateLock) {
                ensureActive()
                val current = mutableState.value.room
                // An idempotent replay can return an older, now closed membership. Never replace a newer room.
                val applied = if (replay && current != null && current.roomId != room.roomId && current.status == RoomStatus.OPEN)
                    room else applyCurrentRoom(room)!!
                pendingRequest = null
                mutablePending.value = null
                if (replay) connectCurrentRoom(force = true, syncFirst = true)
                else if (mutableState.value.room?.status == RoomStatus.CLOSED) stopStream()
                else if (request.roomId == null && mutableState.value.room?.roomId == room.roomId) connectCurrentRoom()
                return applied
            }
        } catch (cancelled: CancellationException) {
            retainUncertain(request)
            throw cancelled
        } catch (error: RepositoryException) {
            if (error.code in AMBIGUOUS_FAILURES) {
                retainUncertain(request)
            } else {
                synchronized(stateLock) {
                    if (error.code == "REVISION_CONFLICT" && error.snapshot != null) {
                        try {
                            if (request.roomId != null) validateRoom(error.snapshot, request.roomId)
                            else validateRoom(error.snapshot, error.snapshot.roomId)
                        } catch (invalid: RepositoryException) {
                            retainUncertain(request)
                            throw invalid
                        }
                        val current = mutableState.value.room
                        if (current == null || current.roomId == error.snapshot.roomId) {
                            val applied = applyCurrentRoom(error.snapshot)
                            if (applied?.status == RoomStatus.CLOSED) stopStream()
                        }
                    }
                    pendingRequest = null
                    mutablePending.value = null
                    if (error.code == "REVISION_CONFLICT" && error.snapshot == null) {
                        connectCurrentRoom(force = true, syncFirst = true)
                    }
                }
            }
            throw error
        }
    }

    private fun retainUncertain(request: PendingRequest) = synchronized(stateLock) {
        if (mutableSessionStatus.value != RealSessionStatus.ACTIVE) return@synchronized
        pendingRequest = request
        mutablePending.value = PendingCommandInfo(request.description, request.roomId, request.revision)
        // Recovery reads authoritative state. It never silently repeats this mutation.
        connectCurrentRoom(force = true, syncFirst = true)
    }

    private fun markRestSynchronized(room: RoomSnapshot?) = synchronized(stateLock) {
        membershipReady = true
        mutablePending.value = mutablePending.value?.copy(retryAvailable = room == null || room.status == RoomStatus.CLOSED)
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

    private fun connectCurrentRoom(force: Boolean = false, syncFirst: Boolean = false) = synchronized(stateLock) {
        ensureActive()
        if (syncFirst) membershipReady = false
        val room = mutableState.value.room
        if (!realtimeEnabled) { stopStream(); return@synchronized }
        if (!syncFirst && (room == null || room.status == RoomStatus.CLOSED)) { stopStream(); return@synchronized }
        if (!force && streamRoomId == room?.roomId && streamJob?.isActive == true) return@synchronized
        stopStream()
        mutablePending.value = mutablePending.value?.copy(retryAvailable = false)
        val connectionGeneration = generation
        streamRoomId = room?.roomId
        mutableConnection.value = RealConnectionState(if (syncFirst) RealConnectionStatus.SYNCHRONIZING else RealConnectionStatus.CONNECTING)
        streamJob = scope.launch { runConnection(connectionGeneration, syncFirst) }
    }

    private suspend fun runConnection(connectionGeneration: Long, initialSync: Boolean) {
        var needsRest = initialSync
        var attempt = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            if (!isCurrentGeneration(connectionGeneration)) return
            var transport: RoomRealtimeClient? = null
            try {
                if (needsRest) {
                    gate.withLock {
                        if (!isCurrentGeneration(connectionGeneration)) return
                        synchronized(stateLock) {
                            mutableConnection.value = RealConnectionState(RealConnectionStatus.SYNCHRONIZING)
                        }
                        authorized {
                            val user = mapResponse { http.me().toDomain() }
                            requireSameUser(user)
                            val room = readCurrentRoom()
                            synchronized(stateLock) {
                                if (!isCurrentGeneration(connectionGeneration)) return
                                applyCurrentRoom(room, user)
                                streamRoomId = room?.roomId
                                markRestSynchronized(room)
                                mutableRecoveryVersion.value++
                            }
                        }
                    }
                }
                val room = synchronized(stateLock) {
                    if (!isCurrentGeneration(connectionGeneration)) return
                    mutableState.value.room
                }
                if (room == null || room.status == RoomStatus.CLOSED) {
                    synchronized(stateLock) {
                        if (isCurrentGeneration(connectionGeneration)) mutableConnection.value = RealConnectionState()
                    }
                    return
                }
                transport = RoomRealtimeClient(baseUrl, http::tokenForHandshake)
                synchronized(stateLock) {
                    if (!isCurrentGeneration(connectionGeneration)) return
                    streamClient = transport
                    streamRoomId = room.roomId
                }
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
                                if (applied.status == RoomStatus.CLOSED) {
                                    mutableConnection.value = RealConnectionState()
                                    mutablePending.value = mutablePending.value?.copy(retryAvailable = true)
                                } else if (incoming.revision >= previous.revision) {
                                    mutableConnection.value = RealConnectionState(RealConnectionStatus.CONNECTED)
                                    mutablePending.value = mutablePending.value?.copy(retryAvailable = true)
                                    attempt = 0
                                }
                                applied.status == RoomStatus.OPEN
                            }
                        }
                    },
                    isSynchronized = { mutableConnection.value.status == RealConnectionStatus.CONNECTED },
                )
                if (!isCurrentGeneration(connectionGeneration)) return
                if (reason?.code == 4401.toShort()) { clearSession(RealSessionStatus.EXPIRED); return }
                if (reason?.code == 4403.toShort() || reason?.code == 4404.toShort()) {
                    failConnection(connectionGeneration, "Доступ к комнате запрещён или комната недоступна.")
                    return
                }
                if (mutableState.value.room?.status != RoomStatus.OPEN) return
            } catch (cancelled: TimeoutCancellationException) {
                // A missing initial authoritative snapshot is a recoverable transport failure.
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (!isCurrentGeneration(connectionGeneration)) return
                if (error is RepositoryException && error.code == "UNAUTHORIZED") {
                    clearSession(RealSessionStatus.EXPIRED); return
                }
                if (error is RepositoryException && error.code !in RECOVERABLE_READ_FAILURES) {
                    failConnection(connectionGeneration, error.message ?: "Не удалось восстановить комнату.")
                    return
                }
            } finally {
                transport?.close()
                synchronized(stateLock) {
                    if (isCurrentGeneration(connectionGeneration)) streamClient = null
                }
            }
            if (!isCurrentGeneration(connectionGeneration)) return
            needsRest = true
            attempt = (attempt + 1).coerceAtMost(30)
            val waitMillis = reconnectDelay(attempt).coerceIn(1, 30_000)
            synchronized(stateLock) {
                if (!isCurrentGeneration(connectionGeneration)) return
                mutablePending.value = mutablePending.value?.copy(retryAvailable = false)
                mutableConnection.value = RealConnectionState(RealConnectionStatus.RECONNECTING,
                    "Связь прервалась. Восстанавливаем состояние комнаты…", attempt, waitMillis)
            }
            delay(waitMillis)
        }
    }

    private fun failConnection(expectedGeneration: Long, message: String) = synchronized(stateLock) {
        if (isCurrentGeneration(expectedGeneration)) {
            mutablePending.value = mutablePending.value?.copy(retryAvailable = false)
            mutableConnection.value = RealConnectionState(RealConnectionStatus.DISCONNECTED, message)
        }
    }

    private fun isCurrentGeneration(expectedGeneration: Long) = generation == expectedGeneration &&
        mutableSessionStatus.value == RealSessionStatus.ACTIVE

    private fun isCurrentStream(expectedGeneration: Long, roomId: String) =
        isCurrentGeneration(expectedGeneration) && streamRoomId == roomId && mutableState.value.room?.roomId == roomId

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
        pendingRequest = null
        mutablePending.value = null
        membershipReady = false
        serverTimer.clear()
        mutableState.value = RepositoryState(mutableState.value.currentUser)
        if (mutableSessionStatus.value != RealSessionStatus.SIGNED_OUT) mutableSessionStatus.value = status
    }

    override fun close() {
        clearSession(RealSessionStatus.SIGNED_OUT)
        http.close()
    }

    companion object {
        private val AMBIGUOUS_FAILURES = setOf("NETWORK_ERROR", "NETWORK_TIMEOUT", "PROTOCOL_ERROR", "INTERNAL_ERROR", "HTTP_ERROR")
        private val RECOVERABLE_READ_FAILURES = setOf("NETWORK_ERROR", "NETWORK_TIMEOUT", "INTERNAL_ERROR")
        suspend fun login(baseUrl: String, username: String, password: String, engine: HttpClientEngine? = null,
                          realtimeEnabled: Boolean = true, reconnectDelay: (Int) -> Long = ::defaultReconnectDelay): RealRepository {
            val credentials = AuthValidation.login(username, password)
            return authenticate(baseUrl, engine, realtimeEnabled, reconnectDelay) { login(LoginRequestDto(credentials.username, credentials.password)) }
        }

        suspend fun register(baseUrl: String, username: String, displayName: String, password: String,
                             engine: HttpClientEngine? = null, realtimeEnabled: Boolean = true, reconnectDelay: (Int) -> Long = ::defaultReconnectDelay): RealRepository {
            val credentials = AuthValidation.registration(username, displayName, password)
            return authenticate(baseUrl, engine, realtimeEnabled, reconnectDelay) {
                register(RegisterRequestDto(credentials.username, credentials.displayName, credentials.password))
            }
        }

        private suspend fun authenticate(baseUrl: String, engine: HttpClientEngine?, realtimeEnabled: Boolean, reconnectDelay: (Int) -> Long,
                                         request: suspend BackendHttpClient.() -> AuthSessionDto): RealRepository {
            val http = BackendHttpClient(baseUrl, engine)
            try {
                val session = http.request()
                val user = mapResponse { session.user.toDomain() }
                val expiresAt = mapResponse { Instant.parse(session.expiresAt) }
                http.installToken(session.accessToken)
                return RealRepository(http, user, expiresAt, realtimeEnabled, reconnectDelay)
            } catch (error: Throwable) {
                http.close()
                throw error
            }
        }

        internal fun defaultReconnectDelay(attempt: Int): Long {
            val base = (1_000L shl (attempt - 1).coerceIn(0, 5)).coerceAtMost(30_000)
            return (base * Random.nextDouble(0.8, 1.2)).toLong().coerceIn(1, 30_000)
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
