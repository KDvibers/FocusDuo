package dev.focusduo.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.focusduo.data.DesktopPreferences
import dev.focusduo.designsystem.*
import dev.focusduo.domain.RepositoryException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** A real session never constructs FakeRepository. Leaving this workspace closes its HTTP client. */
@Composable
fun RealWorkspace(onEnterDemo: () -> Unit, onExit: () -> Unit) {
    val controller = remember { RealSessionController() }
    val session by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    var editConnection by remember { mutableStateOf(false) }
    DisposableEffect(controller) { onDispose { controller.close() } }
    LaunchedEffect(controller) { controller.initialize() }

    Surface(Modifier.fillMaxSize(), color = FocusDuoColors.Background) {
        Column(Modifier.fillMaxSize()) {
            session.notice?.let { Message(it) }
            val repository = session.repository
            if (repository == null) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    if (session.loading) CircularProgressIndicator()
                    else key(session.preferences.baseUrl) { AuthScreen(
                        initialUsername = session.preferences.rememberedUsername,
                        busy = session.busy,
                        error = session.authError,
                        onLogin = { username, password -> scope.launch { controller.login(username, password) } },
                        onRegister = { username, name, password -> scope.launch { controller.register(username, name, password) } },
                        onUseSample = onEnterDemo,
                        demo = false,
                        serverAddress = session.preferences.baseUrl,
                    ) }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.Center) {
                    TextButton({ editConnection = true }, enabled = !session.loading) { Text("Адрес сервера и имя") }
                    TextButton(onEnterDemo, enabled = !session.busy) { Text("Открыть деморежим") }
                    TextButton(onExit) { Text("Закрыть приложение") }
                }
            } else key(repository) {
                // Retain this workspace and all mounted drafts while resynchronizing after sleep.
                LaunchedEffect(repository) {
                    val recovery = ResumeRecoveryCoordinator(recover = {
                        try { repository.recoverAfterResume() }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { /* Repository publishes a safe connection error and retries. */ }
                    })
                    launch { AppResumeSignals.events.collect { recovery.onForegroundHint() } }
                    while (isActive) {
                        recovery.onHeartbeat()
                        delay(1_000)
                    }
                }
                RealApp(
                    repository = repository,
                    preferences = session.preferences,
                    sessionBusy = session.busy,
                    restoreError = session.restoreError,
                    settingsError = session.settingsError,
                    onRefresh = { scope.launch { controller.retryRestore() } },
                    onSavePreferences = { scope.launch { controller.savePreferences(it) } },
                    onLogout = { scope.launch { controller.logout() } },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        if (editConnection) ConnectionPreferencesDialog(
            preferences = session.preferences,
            busy = session.busy,
            error = session.settingsError,
            onDismiss = { editConnection = false },
            onSave = { value -> scope.launch { controller.savePreferences(value) } },
        )
    }
}

@Composable
private fun Message(value: String, error: Boolean = false) {
    Surface(color = if (error) FocusDuoColors.ErrorSurface else FocusDuoColors.PaleGreen, shape = MaterialTheme.shapes.small) {
        Text(value, Modifier.fillMaxWidth().padding(14.dp), color = if (error) FocusDuoColors.Error else FocusDuoColors.Text, style = MaterialTheme.typography.body2)
    }
}

@Composable
private fun ConnectionPreferencesDialog(
    preferences: DesktopPreferences, busy: Boolean, error: RepositoryException?,
    onDismiss: () -> Unit, onSave: (DesktopPreferences) -> Unit,
) {
    var address by remember { mutableStateOf(preferences.baseUrl) }
    var username by remember { mutableStateOf(preferences.rememberedUsername) }
    var validation by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<DesktopPreferences?>(null) }
    LaunchedEffect(preferences, busy, error) {
        if (!busy && pending != null && preferences == pending) onDismiss()
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Подключение к серверу") },
        text = {
            Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Адрес http:// или https:// без /api/v1. На двух ПК укажите адрес одного общего сервера.", style = MaterialTheme.typography.body2)
                OutlinedTextField(address, { address = it; validation = null }, label = { Text("Адрес сервера") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(username, { username = it; validation = null }, label = { Text("Запомнить имя пользователя") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                Text("Сохраняются только адрес и имя. Смена сервера очищает локальную сессию.", style = MaterialTheme.typography.caption, color = FocusDuoColors.Secondary)
                (validation ?: error?.message)?.let { Text(it, color = FocusDuoColors.Error, style = MaterialTheme.typography.body2) }
            }
        },
        confirmButton = {
            TextButton({
                try { DesktopPreferences(address, username).normalized().also { pending = it; validation = null; onSave(it) } }
                catch (failure: RepositoryException) { validation = failure.message }
            }, enabled = !busy) { Text(if (busy) "Сохранение…" else "Сохранить") }
        },
        dismissButton = { TextButton(onDismiss, enabled = !busy) { Text("Отмена") } },
    )
}
