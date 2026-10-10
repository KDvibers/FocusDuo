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
import dev.focusduo.designsystem.*
import dev.focusduo.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private enum class DemoPage { HOME, ROOM, RESULT }
internal enum class RoomDialog { CREATE, JOIN, GOAL, ADD_TASK, EDIT_TASK, DELETE_TASK, SETTINGS, PROFILE, INVITE, RESET, FINISH_ROUND, CLOSE_ROOM }

/** The shell selects an explicit fake implementation; room components only consume snapshots. */
@Composable
fun DemoApp(onExit: () -> Unit) {
    val repository = remember { FakeRepository() }
    val state by repository.state.collectAsState()
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var page by remember { mutableStateOf(DemoPage.HOME) }
    var dialog by remember { mutableStateOf<RoomDialog?>(null) }
    var selectedTask by remember { mutableStateOf<Task?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var selectedResult by remember { mutableStateOf<HistoryItem?>(null) }
    var confirmationRoundId by remember { mutableStateOf<String?>(null) }

    // FakeRepository plays the server here. The UI never declares completion on its own.
    LaunchedEffect(repository) {
        while (isActive) {
            repository.refreshDemoTime()
            delay(200)
        }
    }
    LaunchedEffect(state.history.firstOrNull()?.roundId) {
        state.history.firstOrNull()?.let { result ->
            selectedResult = result
            page = DemoPage.RESULT
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
        busy = true
        error = null
        notice = null
        scope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: RepositoryException) { error = failure.message }
            catch (_: Exception) { error = "Не удалось выполнить действие. Попробуй ещё раз." }
            finally { busy = false }
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
                    FocusDuoLogo(Modifier.padding(8.dp), compact = true)
                    Spacer(Modifier.height(24.dp))
                    NavItem("Главная", FocusIcon.Home, page == DemoPage.HOME) { page = DemoPage.HOME }
                    NavItem("Комната", FocusIcon.People, page == DemoPage.ROOM, state.room != null) { page = DemoPage.ROOM }
                    NavItem("Итог", FocusIcon.Check, page == DemoPage.RESULT, selectedResult != null) { page = DemoPage.RESULT }
                    Spacer(Modifier.weight(1f))
                    Divider(color = FocusDuoColors.Border)
                    Text("ЛОКАЛЬНЫЙ ПРОСМОТР", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.overline, color = FocusDuoColors.Secondary)
                    Text(state.currentUser.displayName, style = MaterialTheme.typography.subtitle1)
                    TextButton(onClick = { openDialog(RoomDialog.PROFILE) }, enabled = !busy && state.room?.status != RoomStatus.CLOSED) { Text("Изменить имя") }
                    Text("Демо · без сервера", style = MaterialTheme.typography.caption, color = FocusDuoColors.Green)
                    TextButton(onClick = onExit) { Text("Закрыть приложение", style = MaterialTheme.typography.caption, color = FocusDuoColors.Secondary) }
                }
            }
            Divider(Modifier.fillMaxHeight().width(1.dp), color = FocusDuoColors.Border)
            val scroll = rememberScrollState()
            LaunchedEffect(page, state.room?.roomId) { scroll.scrollTo(0) }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(26.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(when (page) { DemoPage.HOME -> "ТВОЁ ПРОСТРАНСТВО"; DemoPage.ROOM -> "СОВМЕСТНАЯ РАБОТА"; DemoPage.RESULT -> "ИТОГ СЕССИИ" }, style = MaterialTheme.typography.overline, color = FocusDuoColors.Secondary)
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
                    val openRoom = room?.status == RoomStatus.OPEN
                    val result = selectedResult
                    if (page == DemoPage.RESULT && result != null) {
                        SessionResultScreen(
                            item = result, currentUserId = state.currentUser.id, room = room, busy = busy,
                            onBackToRoom = { page = DemoPage.ROOM },
                            onStartBreak = { perform { repository.startRound(RoundKind.BREAK); page = DemoPage.ROOM } },
                            onStartFocus = { perform { repository.startRound(RoundKind.FOCUS); page = DemoPage.ROOM } },
                            onHome = { page = DemoPage.HOME },
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
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Готовы поработать вместе?", style = MaterialTheme.typography.h2)
                            Text("Создай комнату или войди по коду, чтобы подготовить совместную сессию фокуса.", color = FocusDuoColors.Secondary)
                        }
                        FocusCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                                Text(if (openRoom) "Твоя комната ждёт" else "Начните с одной цели", style = MaterialTheme.typography.h3)
                                Text(if (openRoom) "${room!!.code} · ${room.participants.size}/2 участника. Задачи и цели сохранены в памяти." else "Планируй задачи, делись целью и двигайся вперёд вместе с другом.", color = FocusDuoColors.Secondary)
                                if (!openRoom) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        PrimaryButton({ openDialog(RoomDialog.CREATE) }, enabled = !busy) { Text("Создать комнату") }
                                        SecondaryButton({ openDialog(RoomDialog.JOIN) }, enabled = !busy) { Text("Войти по коду") }
                                    }
                                } else PrimaryButton({ page = DemoPage.ROOM }) { Text("Вернуться в комнату") }
                                if (state.history.isNotEmpty()) TextButton(onClick = { selectedResult = state.history.first(); page = DemoPage.RESULT }) { Text("Открыть последний итог") }
                            }
                        }
                    }
                    DemoTools(
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
