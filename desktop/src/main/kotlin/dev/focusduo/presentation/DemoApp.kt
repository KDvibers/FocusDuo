package dev.focusduo.presentation

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.focusduo.data.FakeRepository
import dev.focusduo.data.DesktopPreferences
import dev.focusduo.designsystem.*
import dev.focusduo.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private enum class DemoPage { HOME, ROOM, HISTORY, RESULT, SETTINGS }
private enum class HistoryPreview { NORMAL, LOADING, ERROR }
internal enum class RoomDialog { CREATE, JOIN, GOAL, ADD_TASK, EDIT_TASK, DELETE_TASK, SETTINGS, PROFILE, INVITE, RESET, FINISH_ROUND, CLOSE_ROOM }

/** The shell selects an explicit fake implementation; room components only consume snapshots. */
@Composable
fun DemoApp(
    repository: FakeRepository,
    preferences: DesktopPreferences,
    sessionBusy: Boolean,
    settingsError: RepositoryException?,
    onSavePreferences: (DesktopPreferences) -> Unit,
    onLogout: () -> Unit,
    onExit: () -> Unit,
) {
    val state by repository.state.collectAsState()
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var page by remember { mutableStateOf(DemoPage.HOME) }
    var dialog by remember { mutableStateOf<RoomDialog?>(null) }
    var selectedTask by remember { mutableStateOf<Task?>(null) }
    var operationBusy by remember { mutableStateOf(false) }
    val busy = operationBusy || sessionBusy
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var selectedResult by remember { mutableStateOf<HistoryItem?>(null) }
    var confirmationRoundId by remember { mutableStateOf<String?>(null) }
    var historyPreview by remember { mutableStateOf(HistoryPreview.NORMAL) }
    var lastObservedRoundId by remember { mutableStateOf(state.history.firstOrNull()?.roundId) }

    // FakeRepository plays the server here. The UI never declares completion on its own.
    LaunchedEffect(repository) {
        while (isActive) {
            repository.refreshDemoTime()
            delay(200)
        }
    }
    LaunchedEffect(state.history.firstOrNull()?.roundId) {
        val latest = state.history.firstOrNull()
        if (latest?.roundId != lastObservedRoundId) {
            lastObservedRoundId = latest?.roundId
            latest?.let { result ->
                if (page in listOf(DemoPage.HOME, DemoPage.ROOM) && dialog == null) {
                    selectedResult = result
                    page = DemoPage.RESULT
                } else notice = "Раунд завершён. Новый итог доступен в истории."
            }
        }
    }

    fun openDialog(next: RoomDialog, task: Task? = null) {
        error = null
        selectedTask = task
        confirmationRoundId = state.room?.round?.id
        dialog = next
    }
    fun perform(action: suspend () -> Unit) {
        if (busy) return
        operationBusy = true
        error = null
        notice = null
        scope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: RepositoryException) { error = failure.message }
            catch (_: Exception) { error = "Не удалось выполнить действие. Попробуй ещё раз." }
            finally { operationBusy = false }
        }
    }
    fun copyCode(): Boolean {
        return state.room?.let {
            try {
                clipboard.setText(AnnotatedString(it.code))
                notice = "Код комнаты скопирован"
                true
            } catch (_: Exception) {
                error = "Не удалось скопировать код. Выдели его в окне приглашения."
                false
            }
        } ?: false
    }

    Surface(Modifier.fillMaxSize(), color = FocusDuoColors.Background) {
        Row(Modifier.fillMaxSize()) {
            Surface(Modifier.width(202.dp).fillMaxHeight(), color = FocusDuoColors.Sidebar) {
                Column(Modifier.padding(16.dp, 26.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        FocusDuoLogo(Modifier.padding(8.dp), compact = true)
                        Spacer(Modifier.height(24.dp))
                        NavItem("Главная", FocusIcon.Home, page == DemoPage.HOME, !busy) { page = DemoPage.HOME }
                        NavItem("Комната", FocusIcon.People, page == DemoPage.ROOM, !busy && state.room != null) { page = DemoPage.ROOM }
                        NavItem("История", FocusIcon.History, page == DemoPage.HISTORY || page == DemoPage.RESULT, !busy) { page = DemoPage.HISTORY }
                        NavItem("Настройки", FocusIcon.Settings, page == DemoPage.SETTINGS, !busy) { page = DemoPage.SETTINGS }
                    }
                    Divider(color = FocusDuoColors.Border)
                    Text("ЛОКАЛЬНЫЙ ПРОСМОТР", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.overline, color = FocusDuoColors.Secondary)
                    Text(state.currentUser.displayName, style = MaterialTheme.typography.subtitle1)
                    TextButton(onClick = { openDialog(RoomDialog.PROFILE) }, enabled = !busy && state.room?.status != RoomStatus.CLOSED) { Text("Изменить имя") }
                    Text("Демо · без сервера", style = MaterialTheme.typography.caption, color = FocusDuoColors.Green)
                    TextButton(onClick = onLogout, enabled = !busy) { Text("Выйти из аккаунта", style = MaterialTheme.typography.caption) }
                    TextButton(onClick = onExit) { Text("Закрыть приложение", style = MaterialTheme.typography.caption, color = FocusDuoColors.Secondary) }
                }
            }
            Divider(Modifier.fillMaxHeight().width(1.dp), color = FocusDuoColors.Border)
            val scroll = rememberScrollState()
            LaunchedEffect(page, state.room?.roomId) { scroll.scrollTo(0) }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(26.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(when (page) { DemoPage.HOME -> "ТВОЁ ПРОСТРАНСТВО"; DemoPage.ROOM -> "СОВМЕСТНАЯ РАБОТА"; DemoPage.RESULT -> "ИТОГ СЕССИИ"; DemoPage.HISTORY -> "ТВОИ СЕССИИ"; DemoPage.SETTINGS -> "НАСТРОЙКИ" }, style = MaterialTheme.typography.overline, color = FocusDuoColors.Secondary)
                        DemoBadge()
                    }
                    val banner = if (dialog == null) error ?: notice else notice
                    if (banner != null) Surface(color = if (error != null && dialog == null) FocusDuoColors.ErrorSurface else FocusDuoColors.PaleGreen, shape = MaterialTheme.shapes.small) {
                        Row(Modifier.fillMaxWidth().padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(banner, Modifier.weight(1f).padding(vertical = 10.dp), style = MaterialTheme.typography.body2)
                            TextButton(onClick = { error = null; notice = null }) { Text("Скрыть") }
                        }
                    }
                    val room = state.room
                    val result = selectedResult
                    if (page == DemoPage.RESULT && result != null) {
                        SessionResultScreen(
                            item = result, currentUserId = state.currentUser.id, room = room, busy = busy,
                            onBackToRoom = { page = DemoPage.ROOM },
                            onStartBreak = { perform { repository.startRound(RoundKind.BREAK); page = DemoPage.ROOM } },
                            onStartFocus = { perform { repository.startRound(RoundKind.FOCUS); page = DemoPage.ROOM } },
                            onHome = { page = DemoPage.HOME },
                            onBackToHistory = { page = DemoPage.HISTORY },
                        )
                    } else if (page == DemoPage.ROOM && room != null) {
                        RoomScreen(
                            room = room, currentUserId = state.currentUser.id, busy = busy,
                            onCopyCode = { copyCode() }, onInvite = { openDialog(RoomDialog.INVITE) },
                            onEditGoal = { openDialog(RoomDialog.GOAL) },
                            onAddTask = { openDialog(RoomDialog.ADD_TASK) },
                            onEditTask = { openDialog(RoomDialog.EDIT_TASK, it) },
                            onDeleteTask = { openDialog(RoomDialog.DELETE_TASK, it) },
                            onToggleTask = { task -> perform { repository.updateTask(task.id, completed = !task.completed) } },
                            onEditSettings = { openDialog(RoomDialog.SETTINGS) },
                            onStartFocus = { perform { repository.startRound(RoundKind.FOCUS) } },
                            onStartBreak = { perform { repository.startRound(RoundKind.BREAK) } },
                            onPause = { room.round?.let { round -> perform { repository.pauseRound(round.id) } } },
                            onResume = { room.round?.let { round -> perform { repository.resumeRound(round.id) } } },
                            onFinish = { openDialog(RoomDialog.FINISH_ROUND) },
                            onCloseRoom = { openDialog(RoomDialog.CLOSE_ROOM) },
                            onShowResult = { state.history.firstOrNull { it.roundId == room.round?.id }?.let { selectedResult = it; page = DemoPage.RESULT } },
                        )
                    } else if (page == DemoPage.HISTORY) {
                        HistoryScreen(
                            items = state.history, currentUserId = state.currentUser.id,
                            busy = busy || historyPreview == HistoryPreview.LOADING,
                            error = if (historyPreview == HistoryPreview.ERROR) "Демонстрационная ошибка загрузки истории. Данные сохранены; попробуй ещё раз." else null,
                            onOpen = { selectedResult = it; page = DemoPage.RESULT },
                            onGoHome = { page = DemoPage.HOME },
                            onRetry = { historyPreview = HistoryPreview.NORMAL; perform { repository.refreshDemoTime(); notice = "История обновлена" } },
                        )
                        HistoryPreviewTools(historyPreview, !busy) { historyPreview = it }
                    } else if (page == DemoPage.SETTINGS) {
                        SettingsScreen(
                            preferences = preferences, currentUser = state.currentUser, room = room,
                            busy = busy, error = settingsError, onSave = onSavePreferences, onLogout = onLogout,
                            onEditRoomSettings = { openDialog(RoomDialog.SETTINGS) },
                        )
                    } else {
                        HomeScreen(
                            room = room, latestResult = state.history.firstOrNull(), busy = busy,
                            onCreate = { openDialog(RoomDialog.CREATE) }, onJoin = { openDialog(RoomDialog.JOIN) },
                            onRoom = { page = DemoPage.ROOM }, onHistory = { page = DemoPage.HISTORY },
                            onLatestResult = { state.history.firstOrNull()?.let { selectedResult = it; page = DemoPage.RESULT } },
                        )
                    }
                    if (page == DemoPage.HOME || page == DemoPage.ROOM) DemoTools(
                        room = room,
                        viewerName = state.currentUser.displayName, busy = busy,
                        onJoinPartner = { perform { repository.simulatePartnerJoin(); notice = "Демоучастник присоединился к комнате" } },
                        onSwitch = { perform { repository.switchPerspective(); notice = "Теперь ты смотришь от имени другого участника" } },
                        onSample = { perform { repository.joinRoom(FakeRepository.SAMPLE_ROOM_CODE); page = DemoPage.ROOM } },
                        onReset = { openDialog(RoomDialog.RESET) },
                        onAdvanceMinute = { perform { repository.advanceDemoTime(60) } },
                        onAdvanceToEnd = { perform { repository.advanceDemoTime((((room?.round?.remainingMs ?: 0) + 999) / 1000).coerceAtLeast(1)) } },
                    )
                }
                VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 8.dp))
            }
        }
    }
    dialog?.let { active ->
        key(active, selectedTask?.id, state.currentUser.id) {
            DemoRoomDialog(
                kind = active, state = state, task = selectedTask, busy = busy, error = error,
                onDismiss = { if (!busy) { dialog = null; error = null } }, onCopyCode = ::copyCode,
                onSubmit = { first, second ->
                    perform {
                        when (active) {
                            RoomDialog.CREATE -> { repository.createRoom(first.toInt() * 60, second.toInt() * 60); page = DemoPage.ROOM }
                            RoomDialog.JOIN -> { repository.joinRoom(first); page = DemoPage.ROOM }
                            RoomDialog.GOAL -> repository.updateGoal(first)
                            RoomDialog.ADD_TASK -> repository.addTask(first)
                            RoomDialog.EDIT_TASK -> repository.updateTask(requireNotNull(selectedTask).id, title = first)
                            RoomDialog.DELETE_TASK -> repository.deleteTask(requireNotNull(selectedTask).id)
                            RoomDialog.SETTINGS -> repository.updateSettings(first.toInt() * 60, second.toInt() * 60)
                            RoomDialog.PROFILE -> repository.updateDemoProfile(first)
                            RoomDialog.RESET -> { repository.resetDemo(); selectedResult = null; page = DemoPage.HOME }
                            RoomDialog.FINISH_ROUND -> repository.finishRound(requireNotNull(confirmationRoundId))
                            RoomDialog.CLOSE_ROOM -> { repository.closeRoom(); page = DemoPage.ROOM }
                            RoomDialog.INVITE -> Unit
                        }
                        dialog = null
                    }
                },
            )
        }
    }
}

@Composable
private fun HistoryPreviewTools(preview: HistoryPreview, enabled: Boolean, onSelect: (HistoryPreview) -> Unit) {
    Surface(Modifier.fillMaxWidth(), color = FocusDuoColors.PaleGreen.copy(alpha = .5f), shape = MaterialTheme.shapes.small) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Проверка состояний истории · только демо", style = MaterialTheme.typography.subtitle1)
            Text("Эти кнопки показывают загрузку и ошибку без подключения к серверу.", style = MaterialTheme.typography.body2, color = FocusDuoColors.Secondary)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(HistoryPreview.NORMAL to "Показать данные", HistoryPreview.LOADING to "Показать загрузку", HistoryPreview.ERROR to "Показать ошибку").forEach { (value, label) ->
                    SecondaryButton({ onSelect(value) }, enabled = enabled && value != preview) { Text(label) }
                }
            }
        }
    }
}

@Composable
private fun NavItem(label: String, icon: FocusIcon, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.small, color = if (selected) FocusDuoColors.PaleGreen else FocusDuoColors.Sidebar) {
        TextButton(onClick, Modifier.fillMaxWidth(), enabled = enabled, contentPadding = PaddingValues(14.dp)) {
            FocusIcon(icon, color = if (enabled) FocusDuoColors.Green else FocusDuoColors.Secondary.copy(alpha = .4f))
            Spacer(Modifier.width(12.dp))
            Text(label, Modifier.weight(1f), fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
        }
    }
}

@Composable
private fun DemoTools(room: RoomSnapshot?, viewerName: String, busy: Boolean, onJoinPartner: () -> Unit, onSwitch: () -> Unit, onSample: () -> Unit, onReset: () -> Unit, onAdvanceMinute: () -> Unit, onAdvanceToEnd: () -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small, color = FocusDuoColors.PaleGreen.copy(alpha = .5f)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Управление демо", style = MaterialTheme.typography.subtitle1)
            Text("Данные локальные. Второй участник моделируется на этом компьютере; подключения к серверу нет.", style = MaterialTheme.typography.body2, color = FocusDuoColors.Secondary)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                when {
                    room == null -> SecondaryButton(onSample, enabled = !busy) { Text("Открыть пример комнаты") }
                    room.participants.size == 2 -> SecondaryButton(onSwitch, enabled = !busy) { Text("Сменить участника") }
                    room.status == RoomStatus.OPEN -> SecondaryButton(onJoinPartner, enabled = !busy) { Text("Добавить демоучастника") }
                }
                if (room != null) TextButton(onReset, enabled = !busy) { Text("Сбросить демо", color = FocusDuoColors.Secondary) }
            }
            if (room != null) Text("Сейчас ты: $viewerName", style = MaterialTheme.typography.caption, color = FocusDuoColors.Green)
            if (room?.status == RoomStatus.OPEN && room.round != null) {
                Text("Проверка времени · только демо", style = MaterialTheme.typography.caption, color = FocusDuoColors.Secondary)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val canAdvance = !busy && room.round.status == RoundStatus.RUNNING
                    SecondaryButton(onAdvanceMinute, enabled = canAdvance) { Text("+1 минута") }
                    SecondaryButton(onAdvanceToEnd, enabled = canAdvance) { Text("До окончания раунда") }
                }
            }
        }
    }
}
