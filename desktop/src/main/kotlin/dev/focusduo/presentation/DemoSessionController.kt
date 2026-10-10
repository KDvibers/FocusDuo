package dev.focusduo.presentation

import dev.focusduo.data.DemoAccounts
import dev.focusduo.data.DesktopPreferences
import dev.focusduo.data.DesktopPreferencesStore
import dev.focusduo.data.FakeRepository
import dev.focusduo.domain.RepositoryException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex

data class DemoSessionState(
    val loading: Boolean = true,
    val busy: Boolean = false,
    val preferences: DesktopPreferences = DesktopPreferences(),
    val repository: FakeRepository? = null,
    val authError: RepositoryException? = null,
    val settingsError: RepositoryException? = null,
    val notice: String? = null,
)

/** Local demo accounts are isolated by server setting. This controller never opens a connection. */
class DemoSessionController(
    private val preferencesStore: DesktopPreferencesStore = DesktopPreferencesStore(),
    private val accountsFactory: () -> DemoAccounts = { DemoAccounts() },
) {
    private val mutableState = MutableStateFlow(DemoSessionState())
    val state: StateFlow<DemoSessionState> = mutableState.asStateFlow()
    private val gate = Mutex()
    private val accountsByServer = mutableMapOf<String, DemoAccounts>()

    suspend fun initialize() = action(settings = false) {
        val loaded = preferencesStore.load()
        mutableState.value = state.value.copy(loading = false, preferences = loaded.preferences, notice = loaded.warning)
    }

    suspend fun login(username: String, password: String) = action(settings = false) {
        enter(accounts().authenticate(username, password))
    }

    suspend fun register(username: String, displayName: String, password: String) = action(settings = false) {
        enter(accounts().register(username, displayName, password))
    }

    suspend fun logout() = action(settings = false) {
        // The registry keeps the local room. Logout does not issue closeRoom or resetDemo.
        mutableState.value = state.value.copy(repository = null, notice = "Ты вышел из демоаккаунта. Комната сохранена до закрытия приложения.")
    }

    suspend fun savePreferences(preferences: DesktopPreferences) = action(settings = true) {
        val saved = preferencesStore.save(preferences)
        val changedServer = saved.baseUrl != state.value.preferences.baseUrl
        mutableState.value = state.value.copy(
            preferences = saved,
            repository = if (changedServer) null else state.value.repository,
            notice = if (changedServer) "Адрес сохранён. Войди снова; деморежим по-прежнему работает локально." else null,
        )
    }

    private fun accounts(): DemoAccounts = accountsByServer.getOrPut(state.value.preferences.baseUrl, accountsFactory)

    private suspend fun enter(repository: FakeRepository) {
        repository.restoreDemoUserPerspective()
        mutableState.value = state.value.copy(repository = repository, notice = null)
    }

    /** Ignore duplicate submissions instead of queuing a second login/save behind the first. */
    private suspend fun action(settings: Boolean, block: suspend () -> Unit) {
        if (!gate.tryLock()) return
        mutableState.value = state.value.copy(busy = true, authError = null, settingsError = null)
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            val safe = failure as? RepositoryException ?: RepositoryException("INTERNAL_ERROR", "Не удалось выполнить действие. Попробуй ещё раз.")
            mutableState.value = if (settings) state.value.copy(settingsError = safe)
            else state.value.copy(authError = safe, loading = false)
        } finally {
            mutableState.value = state.value.copy(busy = false)
            gate.unlock()
        }
    }
}
