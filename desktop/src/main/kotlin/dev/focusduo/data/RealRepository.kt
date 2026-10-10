package dev.focusduo.data

import dev.focusduo.data.remote.AuthSessionDto
import dev.focusduo.data.remote.BackendHttpClient
import dev.focusduo.data.remote.LoginRequestDto
import dev.focusduo.data.remote.RegisterRequestDto
import dev.focusduo.data.remote.toDomain
import dev.focusduo.domain.AuthValidation
import dev.focusduo.domain.FocusDuoRepository
import dev.focusduo.domain.RepositoryException
import dev.focusduo.domain.RepositoryState
import dev.focusduo.domain.RoomSnapshot
import dev.focusduo.domain.RoomStatus
import dev.focusduo.domain.RoundKind
import dev.focusduo.domain.User
import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.DateTimeException

enum class RealSessionStatus { ACTIVE, EXPIRED, SIGNED_OUT }

/** Backend authentication and authoritative room reads. Room commands arrive in the next stage. */
class RealRepository private constructor(
    private val http: BackendHttpClient,
    user: User,
    val expiresAt: Instant,
) : FocusDuoRepository, AutoCloseable {
    val baseUrl: String get() = http.baseUrl
    private val mutableState = MutableStateFlow(RepositoryState(user))
    override val state: StateFlow<RepositoryState> = mutableState.asStateFlow()
    private val mutableSessionStatus = MutableStateFlow(RealSessionStatus.ACTIVE)
    val sessionStatus: StateFlow<RealSessionStatus> = mutableSessionStatus.asStateFlow()
    private val gate = Mutex()
    private val stateLock = Any()

    /** Separate from login/register so a failed restoration never repeats the auth POST. */
    suspend fun bootstrap() {
        gate.withLock {
            authorized {
                val user = mapResponse { http.me().toDomain() }
                requireSameUser(user)
                val room = mapResponse { http.currentRoom().room?.toDomain() }
                validateCurrentRoom(room)
                applyCurrentRoom(room, user)
            }
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
            val room = mapResponse { http.currentRoom().room?.toDomain() }
            validateCurrentRoom(room)
            ensureActive()
            applyCurrentRoom(room)
        }
    }

    /** Reads a previously known room for refresh; it never joins or changes membership. */
    suspend fun refreshRoom(roomId: String): RoomSnapshot = gate.withLock {
        authorized {
            val room = mapResponse { http.room(roomId).toDomain() }
            if (room.roomId != roomId || room.participants.none { it.userId == mutableState.value.currentUser.id }) {
                throw BackendHttpClient.protocolError()
            }
            ensureActive()
            applyCurrentRoom(room)!!
        }
    }

    /** Always removes local credentials. A network failure may leave the remote token valid until expiry. */
    suspend fun logout() {
        try {
            gate.withLock {
                if (mutableSessionStatus.value == RealSessionStatus.ACTIVE) authorized { http.logout() }
            }
        } finally {
            // This also runs when cancellation happens while waiting to acquire the request mutex.
            clearSession(RealSessionStatus.SIGNED_OUT)
            http.close()
        }
    }

    private fun requireSameUser(user: User) {
        if (user.id != mutableState.value.currentUser.id) throw BackendHttpClient.protocolError()
    }

    private fun validateCurrentRoom(room: RoomSnapshot?) {
        if (room != null && (room.status != RoomStatus.OPEN || room.participants.none { it.userId == mutableState.value.currentUser.id })) {
            throw BackendHttpClient.protocolError()
        }
    }

    private fun applyCurrentRoom(room: RoomSnapshot?, user: User? = null) = synchronized(stateLock) {
        ensureActive()
        val previous = mutableState.value.room
        // A late or replayed HTTP snapshot must not roll room data back.
        val applied = when {
            room == null || previous == null || room.roomId != previous.roomId -> room
            room.revision < previous.revision -> previous
            room.revision == previous.revision -> if (room.serverNow <= previous.serverNow) previous else previous.copy(
                serverNow = room.serverNow,
                round = previous.round?.let { old ->
                    room.round?.takeIf { it.id == old.id && it.status == old.status }?.let { fresh ->
                        old.copy(remainingMs = fresh.remainingMs, activeElapsedMs = fresh.activeElapsedMs)
                    } ?: old
                },
            )
            else -> room
        }
        mutableState.value = mutableState.value.copy(currentUser = user ?: mutableState.value.currentUser, room = applied)
        applied
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
        http.clearToken()
        mutableState.value = RepositoryState(mutableState.value.currentUser)
        if (mutableSessionStatus.value != RealSessionStatus.SIGNED_OUT) mutableSessionStatus.value = status
    }

    override fun close() {
        clearSession(RealSessionStatus.SIGNED_OUT)
        http.close()
    }

    override suspend fun createRoom(focusDurationSeconds: Int, breakDurationSeconds: Int): RoomSnapshot = unavailable()
    override suspend fun joinRoom(code: String): RoomSnapshot = unavailable()
    override suspend fun updateGoal(goal: String): RoomSnapshot = unavailable()
    override suspend fun addTask(title: String): RoomSnapshot = unavailable()
    override suspend fun updateTask(taskId: String, title: String?, completed: Boolean?): RoomSnapshot = unavailable()
    override suspend fun deleteTask(taskId: String): RoomSnapshot = unavailable()
    override suspend fun updateSettings(focusDurationSeconds: Int, breakDurationSeconds: Int): RoomSnapshot = unavailable()
    override suspend fun startRound(kind: RoundKind): RoomSnapshot = unavailable()
    override suspend fun pauseRound(roundId: String): RoomSnapshot = unavailable()
    override suspend fun resumeRound(roundId: String): RoomSnapshot = unavailable()
    override suspend fun finishRound(roundId: String): RoomSnapshot = unavailable()
    override suspend fun closeRoom(): RoomSnapshot = unavailable()

    private fun unavailable(): Nothing {
        ensureActive()
        throw RepositoryException("FEATURE_NOT_AVAILABLE", "Команды комнаты будут доступны на следующем этапе подключения backend.")
    }

    companion object {
        suspend fun login(baseUrl: String, username: String, password: String, engine: HttpClientEngine? = null): RealRepository {
            val credentials = AuthValidation.login(username, password)
            return authenticate(baseUrl, engine) { login(LoginRequestDto(credentials.username, credentials.password)) }
        }

        suspend fun register(
            baseUrl: String,
            username: String,
            displayName: String,
            password: String,
            engine: HttpClientEngine? = null,
        ): RealRepository {
            val credentials = AuthValidation.registration(username, displayName, password)
            return authenticate(baseUrl, engine) {
                register(RegisterRequestDto(credentials.username, credentials.displayName, credentials.password))
            }
        }

        private suspend fun authenticate(
            baseUrl: String,
            engine: HttpClientEngine?,
            request: suspend BackendHttpClient.() -> AuthSessionDto,
        ): RealRepository {
            val http = BackendHttpClient(baseUrl, engine)
            try {
                val session = http.request()
                val user = mapResponse { session.user.toDomain() }
                val expiresAt = mapResponse { Instant.parse(session.expiresAt) }
                http.installToken(session.accessToken)
                return RealRepository(http, user, expiresAt)
            } catch (error: Throwable) {
                http.close()
                throw error
            }
        }

        private suspend inline fun <T> mapResponse(block: () -> T): T = try { block() } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (expected: RepositoryException) {
            throw expected
        } catch (_: IllegalArgumentException) {
            throw BackendHttpClient.protocolError()
        } catch (_: DateTimeException) {
            throw BackendHttpClient.protocolError()
        }
    }
}
