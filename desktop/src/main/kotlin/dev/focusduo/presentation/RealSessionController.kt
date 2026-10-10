package dev.focusduo.presentation

import dev.focusduo.data.DesktopPreferences
import dev.focusduo.data.DesktopPreferencesStore
import dev.focusduo.data.RealRepository
import dev.focusduo.data.RealSessionStatus
import dev.focusduo.domain.AuthValidation
import dev.focusduo.domain.RepositoryException
import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlin.coroutines.CoroutineContext

data class RealSessionState(
    val loading: Boolean = true,
    val busy: Boolean = false,
    val preferences: DesktopPreferences = DesktopPreferences(),
    val repository: RealRepository? = null,
    val authError: RepositoryException? = null,
    val settingsError: RepositoryException? = null,
    val restoreError: RepositoryException? = null,
    val notice: String? = null,
)

/** Owns an in-memory backend session. Preferences never contain a password or bearer token. */
class RealSessionController(
    private val preferencesStore: DesktopPreferencesStore = DesktopPreferencesStore(),
    private val engineFactory: () -> HttpClientEngine? = { null },
    coroutineContext: CoroutineContext = Dispatchers.Default,
) : AutoCloseable {
    private val scope = CoroutineScope(coroutineContext + SupervisorJob())
    private val mutableState = MutableStateFlow(RealSessionState())
    val state: StateFlow<RealSessionState> = mutableState.asStateFlow()
    private val operationGate = Mutex()
    private val settingsGate = Mutex()
    private val lock = Any()
    private var generation = 0L
    private var closed = false
    private var operationBusy = false
    private var settingsBusy = false
    private var activeRequest: Deferred<Unit>? = null
    private var sessionWatcher: Job? = null

    suspend fun initialize() = operation { expected ->
        val loaded = preferencesStore.load()
        synchronized(lock) {
            if (isCurrent(expected)) {
                mutableState.value = state.value.copy(loading = false, preferences = loaded.preferences, notice = loaded.warning)
            }
        }
    }

    suspend fun login(username: String, password: String) = operation { expected ->
        val credentials = AuthValidation.login(username, password)
        val baseUrl = synchronized(lock) { state.value.preferences.baseUrl }
        enter(expected, RealRepository.login(baseUrl, credentials.username, credentials.password, engineFactory()))
    }

    suspend fun register(username: String, displayName: String, password: String) = operation { expected ->
        val credentials = AuthValidation.registration(username, displayName, password)
        val baseUrl = synchronized(lock) { state.value.preferences.baseUrl }
        enter(expected, RealRepository.register(baseUrl, credentials.username, credentials.displayName, credentials.password, engineFactory()))
    }

    /** A successful auth response is kept if restoring /me or /rooms/current has a transport error. */
    suspend fun retryRestore() = operation { expected ->
        val repository = synchronized(lock) { state.value.repository } ?: return@operation
        restore(expected, repository)
    }

    suspend fun logout() = operation { expected ->
        val repository = synchronized(lock) { state.value.repository } ?: return@operation
        var failure: RepositoryException? = null
        try {
            repository.logout()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (exception: Exception) {
            failure = safeFailure(exception)
        } finally {
            clearSession(expected, repository, "Ты вышел из аккаунта.")
            repository.close()
        }
        synchronized(lock) {
            if (isCurrent(expected) && failure != null) {
                mutableState.value = state.value.copy(
                    notice = "Локальная сессия очищена. Сервер не подтвердил выход; серверная сессия останется действовать до истечения срока.",
                    authError = failure,
                )
            }
        }
    }

    /** Saving another server cancels old requests before a response can recreate the old session. */
    suspend fun savePreferences(preferences: DesktopPreferences) {
        if (!settingsGate.tryLock()) return
        synchronized(lock) {
            if (closed) {
                settingsGate.unlock()
                return
            }
            settingsBusy = true
            mutableState.value = state.value.copy(busy = true, settingsError = null)
        }
        try {
            val saved = preferencesStore.save(preferences)
            var oldRepository: RealRepository? = null
            synchronized(lock) {
                if (!closed) {
                    val serverChanged = saved.baseUrl != state.value.preferences.baseUrl
                    if (serverChanged) {
                        generation++
                        activeRequest?.cancel()
                        activeRequest = null
                        operationBusy = false
                        sessionWatcher?.cancel()
                        sessionWatcher = null
                        oldRepository = state.value.repository
                    }
                    mutableState.value = state.value.copy(
                        preferences = saved,
                        repository = if (serverChanged) null else state.value.repository,
                        authError = if (serverChanged) null else state.value.authError,
                        restoreError = if (serverChanged) null else state.value.restoreError,
                        notice = if (serverChanged) "Адрес сервера сохранён. Войди в аккаунт на выбранном сервере." else null,
                    )
                }
            }
            oldRepository?.close()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            synchronized(lock) {
                if (!closed) mutableState.value = state.value.copy(settingsError = safeFailure(failure))
            }
        } finally {
            synchronized(lock) {
                settingsBusy = false
                if (!closed) mutableState.value = state.value.copy(busy = operationBusy)
            }
            settingsGate.unlock()
        }
    }

    private suspend fun enter(expected: Long, repository: RealRepository) {
        val published = synchronized(lock) {
            if (!isCurrent(expected)) false
            else {
                sessionWatcher?.cancel()
                state.value.repository?.close()
                mutableState.value = state.value.copy(repository = repository, loading = false, authError = null, restoreError = null, notice = null)
                sessionWatcher = scope.launch {
                    repository.sessionStatus.collect { status ->
                        when (status) {
                            RealSessionStatus.ACTIVE -> Unit
                            RealSessionStatus.EXPIRED -> expire(expected, repository)
                            RealSessionStatus.SIGNED_OUT -> clearSession(expected, repository, "Ты вышел из аккаунта.")
                        }
                    }
                }
                true
            }
        }
        if (!published) {
            repository.close()
            return
        }
        restore(expected, repository)
    }

    private suspend fun restore(expected: Long, repository: RealRepository) {
        try {
            repository.refreshMe()
            repository.refreshCurrentRoom()
            synchronized(lock) {
                if (owns(expected, repository)) mutableState.value = state.value.copy(restoreError = null, notice = null)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            val safe = safeFailure(failure)
            if (safe.code == "UNAUTHORIZED" || repository.sessionStatus.value == RealSessionStatus.EXPIRED) {
                expire(expected, repository)
            } else {
                synchronized(lock) {
                    if (owns(expected, repository)) mutableState.value = state.value.copy(restoreError = safe)
                }
            }
        }
    }

    private fun expire(expected: Long, repository: RealRepository) {
        synchronized(lock) {
            if (owns(expected, repository)) {
                sessionWatcher?.cancel()
                sessionWatcher = null
                mutableState.value = state.value.copy(
                    repository = null,
                    restoreError = null,
                    authError = RepositoryException("UNAUTHORIZED", "Сессия истекла или отозвана. Войди снова."),
                    notice = null,
                )
                repository.close()
            }
        }
    }

    private fun clearSession(expected: Long, repository: RealRepository, notice: String) {
        synchronized(lock) {
            if (owns(expected, repository)) {
                sessionWatcher?.cancel()
                sessionWatcher = null
                mutableState.value = state.value.copy(repository = null, authError = null, restoreError = null, notice = notice)
            }
        }
    }

    /** Duplicate submissions are ignored; cancelling or changing server cannot publish stale results. */
    private suspend fun operation(block: suspend (Long) -> Unit) {
        if (!operationGate.tryLock()) return
        val expected: Long
        val request: Deferred<Unit>
        synchronized(lock) {
            if (closed) {
                operationGate.unlock()
                return
            }
            expected = generation
            operationBusy = true
            mutableState.value = state.value.copy(busy = true, authError = null, settingsError = null)
            request = scope.async { block(expected) }
            activeRequest = request
        }
        try {
            request.await()
        } catch (cancelled: CancellationException) {
            request.cancel()
            synchronized(lock) { if (isCurrent(expected)) throw cancelled }
        } catch (failure: Exception) {
            synchronized(lock) {
                if (isCurrent(expected)) mutableState.value = state.value.copy(authError = safeFailure(failure), loading = false)
            }
        } finally {
            synchronized(lock) {
                if (activeRequest === request) {
                    activeRequest = null
                    operationBusy = false
                    if (!closed) mutableState.value = state.value.copy(busy = settingsBusy)
                }
            }
            operationGate.unlock()
        }
    }

    private fun isCurrent(expected: Long): Boolean = !closed && generation == expected
    private fun owns(expected: Long, repository: RealRepository): Boolean = isCurrent(expected) && state.value.repository === repository

    private fun safeFailure(failure: Exception): RepositoryException = failure as? RepositoryException
        ?: RepositoryException("INTERNAL_ERROR", "Не удалось выполнить действие. Попробуй ещё раз.")

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            generation++
            activeRequest?.cancel()
            activeRequest = null
            sessionWatcher?.cancel()
            sessionWatcher = null
            state.value.repository?.close()
            mutableState.value = state.value.copy(repository = null, busy = false)
        }
        scope.cancel()
    }
}
