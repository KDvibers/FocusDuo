package dev.focusduo.presentation

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import dev.focusduo.data.DesktopPreferences
import dev.focusduo.data.RealRepository
import dev.focusduo.designsystem.*
import dev.focusduo.domain.RepositoryException
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
                RealShell(
                    repository = repository,
                    preferences = session.preferences,
                    busy = session.busy,
                    restoreError = session.restoreError,
                    settingsError = session.settingsError,
                    onRefresh = { scope.launch { controller.retryRestore() } },
                    onSave = { scope.launch { controller.savePreferences(it) } },
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

private enum class RealPage { HOME, ROOM, SETTINGS }

@Composable
private fun RealShell(
    repository: RealRepository,
    preferences: DesktopPreferences,
    busy: Boolean,
    restoreError: RepositoryException?,
    settingsError: RepositoryException?,
    onRefresh: () -> Unit,
    onSave: (DesktopPreferences) -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val data by repository.state.collectAsState()
    var page by remember { mutableStateOf(RealPage.HOME) }
    var invite by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val scroll = rememberScrollState()
    val navScroll = rememberScrollState()
    fun copyCode() { data.room?.let { clipboard.setText(AnnotatedString(it.code)); copied = true } }

    Row(modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        FocusCard(Modifier.width(190.dp).fillMaxHeight()) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                FocusDuoLogo(compact = true)
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(navScroll), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    RealNav("Главная", FocusIcon.Home, page == RealPage.HOME) { page = RealPage.HOME }
                    RealNav("Комната", FocusIcon.People, page == RealPage.ROOM) { page = RealPage.ROOM }
                    SecondaryButton({}, Modifier.fillMaxWidth(), enabled = false) {
                        FocusIcon(FocusIcon.History); Spacer(Modifier.width(8.dp)); Text("История")
                    }
                    RealNav("Настройки", FocusIcon.Settings, page == RealPage.SETTINGS) { page = RealPage.SETTINGS }
                    Text("Команды комнаты и история подключаются на этапе 6.", style = MaterialTheme.typography.caption, color = FocusDuoColors.Secondary)
                }
                Text(data.currentUser.displayName, style = MaterialTheme.typography.subtitle1)
                Text("@${data.currentUser.username}", style = MaterialTheme.typography.caption, color = FocusDuoColors.Secondary)
                TextButton(onLogout, enabled = !busy) { Text("Выйти") }
            }
        }
        Box(Modifier.weight(1f).fillMaxHeight()) {
            Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Text("Сервер · ${preferences.baseUrl}", color = FocusDuoColors.Green, style = MaterialTheme.typography.body2)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                restoreError?.let { Message(it.message, error = true) }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    SecondaryButton(onRefresh, enabled = !busy) { Text(if (restoreError == null) "Обновить с сервера" else "Повторить загрузку") }
                    if (copied) Text("Код скопирован", color = FocusDuoColors.Green, style = MaterialTheme.typography.caption)
                }
                when (page) {
                    RealPage.HOME -> {
                        Text("Привет, ${data.currentUser.displayName}", style = MaterialTheme.typography.h2)
                        FocusCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(26.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                Text("Вход выполнен", style = MaterialTheme.typography.h3)
                                Text("Этот режим использует данные сервера. Изменения второго участника появятся после обновления; WebSocket будет подключён на этапе 6.", color = FocusDuoColors.Secondary)
                                when {
                                    restoreError != null -> Text("Загрузка профиля или комнаты не завершена. Повторите её кнопкой выше.", color = FocusDuoColors.Secondary)
                                    busy -> Text("Восстанавливаем текущую комнату…", color = FocusDuoColors.Secondary)
                                    data.room == null -> Text("Открытой комнаты нет.", color = FocusDuoColors.Secondary)
                                    else -> {
                                        Text("${data.room!!.code} · ${data.room!!.participants.size}/2 участника")
                                        PrimaryButton({ page = RealPage.ROOM }, enabled = !busy) { Text("Открыть текущую комнату") }
                                    }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    SecondaryButton({}, enabled = false) { Text("Создать комнату") }
                                    SecondaryButton({}, enabled = false) { Text("Войти по коду") }
                                }
                                Text("Создание комнаты, задачи и управление раундом будут доступны на этапе 6.", style = MaterialTheme.typography.caption, color = FocusDuoColors.Secondary)
                            }
                        }
                    }
                    RealPage.ROOM -> {
                        Text("Текущая комната", style = MaterialTheme.typography.h2)
                        Text("Показан серверный снимок. Время зафиксировано на момент его получения; живой отсчёт и команды будут подключены на этапе 6.", style = MaterialTheme.typography.body2, color = FocusDuoColors.Secondary)
                        val room = data.room
                        if (room == null) Text(if (restoreError != null) "Не удалось восстановить комнату." else if (busy) "Загружаем комнату…" else "Сейчас открытой комнаты нет.", color = FocusDuoColors.Secondary)
                        else RoomScreen(
                            room = room, currentUserId = data.currentUser.id, busy = busy,
                            onCopyCode = ::copyCode, onInvite = { invite = true },
                            onEditGoal = {}, onAddTask = {}, onEditTask = {}, onDeleteTask = {}, onToggleTask = {},
                            onEditSettings = {}, onStartFocus = {}, onStartBreak = {}, onPause = {}, onResume = {},
                            onFinish = {}, onCloseRoom = {}, onShowResult = {}, readOnly = true,
                            connectionLabel = "HTTP-снимок · revision ${room.revision} · без WebSocket",
                        )
                    }
                    RealPage.SETTINGS -> SettingsScreen(
                        preferences, data.currentUser, data.room, busy, settingsError,
                        onSave, onLogout, onEditRoomSettings = {}, demo = false, roomCommandsAvailable = false,
                    )
                }
            }
            VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
    }
    if (invite && data.room != null) AlertDialog(
        onDismissRequest = { invite = false },
        title = { Text("Пригласить в комнату") },
        text = { Text("Передай другу код ${data.room!!.code} и адрес ${preferences.baseUrl}. Оба клиента должны использовать один сервер.") },
        confirmButton = { TextButton(::copyCode) { Text(if (copied) "Код скопирован" else "Скопировать код") } },
        dismissButton = { TextButton({ invite = false }) { Text("Закрыть") } },
    )
}

@Composable
private fun RealNav(label: String, icon: FocusIcon, selected: Boolean, onClick: () -> Unit) {
    val content: @Composable RowScope.() -> Unit = { FocusIcon(icon); Spacer(Modifier.width(8.dp)); Text(label) }
    if (selected) PrimaryButton(onClick, Modifier.fillMaxWidth(), content = content)
    else SecondaryButton(onClick, Modifier.fillMaxWidth(), content = content)
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
