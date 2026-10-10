package dev.focusduo.presentation

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import dev.focusduo.data.DesktopPreferences
import dev.focusduo.data.RealConnectionStatus
import dev.focusduo.data.RealRepository
import dev.focusduo.designsystem.*
import dev.focusduo.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private enum class RealPage { HOME, ROOM, HISTORY, RESULT, SETTINGS }

/** Every room, task and result in this workspace comes from the authenticated backend. */
@Composable
internal fun RealApp(
    repository: RealRepository,
    preferences: DesktopPreferences,
    sessionBusy: Boolean,
    restoreError: RepositoryException?,
    settingsError: RepositoryException?,
    onRefresh: () -> Unit,
    onSavePreferences: (DesktopPreferences) -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val data by repository.state.collectAsState()
    val connection by repository.connectionState.collectAsState()
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var page by remember { mutableStateOf(RealPage.HOME) }
    var dialog by remember { mutableStateOf<RoomDialog?>(null) }
    var selectedTask by remember { mutableStateOf<Task?>(null) }
    var dialogRoomId by remember { mutableStateOf<String?>(null) }
    var confirmationRoundId by remember { mutableStateOf<String?>(null) }
    var operationBusy by remember { mutableStateOf(false) }
    var historyBusy by remember { mutableStateOf(false) }
    var resultBusy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var operationFailure by remember { mutableStateOf<RepositoryException?>(null) }
    var historyError by remember { mutableStateOf<String?>(null) }
    var resultError by remember { mutableStateOf<String?>(null) }
    var failedResultId by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var selectedResult by remember { mutableStateOf<HistoryItem?>(null) }
    var observedTerminalId by remember { mutableStateOf(data.room?.round?.takeIf { it.isTerminal() }?.id) }
    var timerTick by remember { mutableLongStateOf(0L) }
    val busy = sessionBusy || operationBusy || resultBusy
    val room = data.room
    val commandsAvailable = repository.canMutate
    val displayRoom = remember(room, timerTick) { repository.projectedRoom() }

    LaunchedEffect(repository) {
        while (isActive) { delay(200); timerTick++ }
    }

    fun refreshHistory() {
        if (historyBusy) return
        historyBusy = true
        historyError = null
        scope.launch {
            try { repository.loadHistory() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { historyError = safeRealError(failure) }
            finally { historyBusy = false }
        }
    }

    fun openResult(roundId: String, automatic: Boolean = false) {
        if (resultBusy || (!automatic && busy)) return
        resultBusy = true
        resultError = null
        failedResultId = null
        scope.launch {
            try {
                val result = repository.loadHistoryItem(roundId)
                if (!automatic || (page in listOf(RealPage.HOME, RealPage.ROOM) && dialog == null)) {
                    selectedResult = result
                    page = RealPage.RESULT
                } else notice = "Раунд завершён. Сохранённый итог доступен в истории."
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                failedResultId = roundId
                resultError = safeRealError(failure)
            } finally { resultBusy = false }
        }
    }

    LaunchedEffect(repository) { refreshHistory() }
    LaunchedEffect(room?.round?.id, room?.round?.status, resultBusy) {
        val round = room?.round
        if (round != null && round.isTerminal() && !resultBusy && observedTerminalId != round.id) {
            observedTerminalId = round.id
            openResult(round.id, automatic = true)
        }
    }

    fun openDialog(kind: RoomDialog, task: Task? = null) {
        if (busy) return
        error = null
        operationFailure = null
        selectedTask = task
        dialogRoomId = repository.state.value.room?.roomId
        confirmationRoundId = repository.state.value.room?.round?.id
        dialog = kind
    }

    fun perform(requireReady: Boolean = true, action: suspend () -> Unit) {
        if (busy) return
        operationFailure = null
        if (requireReady && !repository.canMutate) {
            error = "Нет актуального соединения с комнатой. Восстанови его и отправь действие снова."
            return
        }
        operationBusy = true
        error = null
        notice = null
        scope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                operationFailure = failure as? RepositoryException
                error = safeRealError(failure)
            }
            finally { operationBusy = false }
        }
    }

    fun reconnect() {
        perform(requireReady = false) { repository.reconnect() }
    }

    fun copyCode(): Boolean = repository.state.value.room?.let {
        try {
            clipboard.setText(AnnotatedString(it.code))
            notice = "Код комнаты скопирован"
            true
        } catch (_: Exception) {
            operationFailure = null
            error = "Не удалось скопировать код. Его можно выделить в окне приглашения."
            false
        }
    } ?: false

    val reconnecting = connection.status in listOf(RealConnectionStatus.CONNECTING, RealConnectionStatus.SYNCHRONIZING)
    Row(modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        FocusCard(Modifier.width(190.dp).fillMaxHeight()) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                FocusDuoLogo(compact = true)
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    RealNav("Главная", FocusIcon.Home, page == RealPage.HOME, !busy) { page = RealPage.HOME }
                    RealNav("Комната", FocusIcon.People, page == RealPage.ROOM, !busy && room != null) { page = RealPage.ROOM }
                    RealNav("История", FocusIcon.History, page in listOf(RealPage.HISTORY, RealPage.RESULT), !busy) { page = RealPage.HISTORY }
                    RealNav("Настройки", FocusIcon.Settings, page == RealPage.SETTINGS, !busy) { page = RealPage.SETTINGS }
                }
                Text(data.currentUser.displayName, style = MaterialTheme.typography.subtitle1)
                Text("@${data.currentUser.username}", style = MaterialTheme.typography.caption, color = FocusDuoColors.Secondary)
                Text("Подключение к серверу", style = MaterialTheme.typography.caption, color = FocusDuoColors.Green)
                TextButton(onLogout, enabled = !busy) { Text("Выйти") }
            }
        }
        val scroll = rememberScrollState()
        LaunchedEffect(page, room?.roomId) { scroll.scrollTo(0) }
        Box(Modifier.weight(1f).fillMaxHeight()) {
            Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Text("Сервер · ${preferences.baseUrl}", color = FocusDuoColors.Green, style = MaterialTheme.typography.body2)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                restoreError?.let { RealMessage(it.message, true) }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    SecondaryButton(onRefresh, enabled = !busy) { Text(if (restoreError == null) "Обновить с сервера" else "Повторить загрузку") }
                }
                if (room?.status == RoomStatus.OPEN) FocusCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(connectionLabel(connection.status), style = MaterialTheme.typography.subtitle1)
                        connection.message?.let { Text(it, style = MaterialTheme.typography.body2, color = FocusDuoColors.Secondary) }
                        if (!commandsAvailable) {
                            Text("Изменения доступны после первого актуального снимка WebSocket. Данные комнаты остаются на экране.", style = MaterialTheme.typography.body2, color = FocusDuoColors.Secondary)
                            SecondaryButton(::reconnect, enabled = !busy && !reconnecting) { Text(if (reconnecting) "Подключение…" else "Восстановить соединение") }
                        }
                    }
                }
                val banner = if (dialog == null) error ?: notice else notice
                if (banner != null) RealMessage(banner, error != null && dialog == null)
                if (resultError != null) FocusCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Не удалось загрузить сохранённый итог", style = MaterialTheme.typography.subtitle1)
                        Text(resultError.orEmpty(), color = FocusDuoColors.Error, style = MaterialTheme.typography.body2)
                        SecondaryButton({ failedResultId?.let { openResult(it) } }, enabled = !busy) { Text("Повторить загрузку итога") }
                    }
                }
                when (page) {
                    RealPage.HOME -> {
                        HomeScreen(
                            room, data.history.firstOrNull(), busy || restoreError != null,
                            onCreate = { openDialog(RoomDialog.CREATE) }, onJoin = { openDialog(RoomDialog.JOIN) },
                            onRoom = { page = RealPage.ROOM }, onHistory = { page = RealPage.HISTORY },
                            onLatestResult = { data.history.firstOrNull()?.let { openResult(it.roundId) } },
                        )
                        if (historyBusy) Text("Загружаем последнюю сессию…", style = MaterialTheme.typography.body2, color = FocusDuoColors.Secondary)
                        historyError?.let { RealMessage("История: $it", true) }
                    }
                    RealPage.ROOM -> if (displayRoom == null) {
                        Text("Сейчас открытой комнаты нет.", color = FocusDuoColors.Secondary)
                        PrimaryButton({ page = RealPage.HOME }, enabled = !busy) { Text("На главную") }
                    } else RoomScreen(
                        room = displayRoom, currentUserId = data.currentUser.id, busy = busy,
                        onCopyCode = { copyCode() }, onInvite = { openDialog(RoomDialog.INVITE) },
                        onEditGoal = { openDialog(RoomDialog.GOAL) }, onAddTask = { openDialog(RoomDialog.ADD_TASK) },
                        onEditTask = { openDialog(RoomDialog.EDIT_TASK, it) }, onDeleteTask = { openDialog(RoomDialog.DELETE_TASK, it) },
                        onToggleTask = { task -> perform { repository.updateTask(task.id, completed = !task.completed) } },
                        onEditSettings = { openDialog(RoomDialog.SETTINGS) },
                        onStartFocus = { perform { repository.startRound(RoundKind.FOCUS) } },
                        onStartBreak = { perform { repository.startRound(RoundKind.BREAK) } },
                        onPause = { room?.round?.let { round -> perform { repository.pauseRound(round.id) } } },
                        onResume = { room?.round?.let { round -> perform { repository.resumeRound(round.id) } } },
                        onFinish = { openDialog(RoomDialog.FINISH_ROUND) }, onCloseRoom = { openDialog(RoomDialog.CLOSE_ROOM) },
                        onShowResult = { room?.round?.let { openResult(it.id) } },
                        readOnly = !commandsAvailable,
                        connectionLabel = "${connectionLabel(connection.status)} · revision ${displayRoom.revision}",
                        waitingForServer = displayRoom.round?.status == RoundStatus.RUNNING && displayRoom.round?.remainingMs == 0L,
                    )
                    RealPage.HISTORY -> HistoryScreen(
                        data.history, data.currentUser.id, busy || historyBusy,
                        onOpen = { openResult(it.roundId) }, onGoHome = { page = RealPage.HOME },
                        onRetry = ::refreshHistory, error = historyError,
                    )
                    RealPage.RESULT -> selectedResult?.let { result ->
                        SessionResultScreen(
                            result, data.currentUser.id, room, busy,
                            onBackToRoom = { page = RealPage.ROOM },
                            onStartBreak = { perform { repository.startRound(RoundKind.BREAK); page = RealPage.ROOM } },
                            onStartFocus = { perform { repository.startRound(RoundKind.FOCUS); page = RealPage.ROOM } },
                            onHome = { page = RealPage.HOME }, onBackToHistory = { page = RealPage.HISTORY },
                            commandsAvailable = commandsAvailable,
                        )
                    }
                    RealPage.SETTINGS -> SettingsScreen(
                        preferences, data.currentUser, room, busy, settingsError,
                        onSavePreferences, onLogout, onEditRoomSettings = { openDialog(RoomDialog.SETTINGS) },
                        demo = false, roomCommandsAvailable = commandsAvailable,
                    )
                }
            }
            VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
    }
    dialog?.let { active ->
        key(active, selectedTask?.id, data.currentUser.id) {
            val targetsCurrentRoom = dialogRoomId == room?.roomId
            val membershipCommand = active in listOf(RoomDialog.CREATE, RoomDialog.JOIN)
            val membershipNeedsRestore = membershipCommand && connection.status == RealConnectionStatus.DISCONNECTED
            val membershipAlreadyExists = membershipCommand && room?.status == RoomStatus.OPEN
            val targetsCurrentRound = active != RoomDialog.FINISH_ROUND ||
                (confirmationRoundId == room?.round?.id && room?.round?.status in listOf(RoundStatus.RUNNING, RoundStatus.PAUSED))
            val canEditSettings = active != RoomDialog.SETTINGS || room?.round?.status !in listOf(RoundStatus.RUNNING, RoundStatus.PAUSED)
            val ready = if (membershipCommand) restoreError == null && !membershipNeedsRestore && !membershipAlreadyExists
                else targetsCurrentRoom && targetsCurrentRound && canEditSettings && commandsAvailable
            val disabledReason = when {
                membershipCommand && restoreError != null -> "Сначала восстанови данные с сервера. Черновик формы сохранён."
                membershipNeedsRestore -> "Результат создания комнаты или входа неизвестен. Сначала обнови текущую комнату, чтобы не отправить действие повторно. Черновик сохранён."
                membershipAlreadyExists -> "Сервер подтвердил открытую комнату. Закрой форму и открой её через главную; повторно создавать комнату или входить не требуется."
                !membershipCommand && !targetsCurrentRoom -> "Текущая комната изменилась. Черновик сохранён; закрой форму и проверь новую комнату."
                !membershipCommand && room?.status == RoomStatus.CLOSED -> "Комната уже закрыта сервером. Черновик сохранён, но отправить изменения в эту комнату нельзя."
                !targetsCurrentRound -> "Этот раунд уже завершён или сменился. Черновик сохранён; закрой подтверждение и проверь состояние комнаты."
                !canEditSettings -> "Раунд уже начался. Изменить длительности можно после его завершения; черновик сохранён."
                else -> null
            }
            DemoRoomDialog(
                active, data, selectedTask, busy, operationFailure?.let(::safeRealError) ?: error,
                onDismiss = { if (!busy) { dialog = null; error = null; operationFailure = null } }, onCopyCode = ::copyCode,
                demo = false, serverAddress = preferences.baseUrl, mutationsEnabled = ready || active == RoomDialog.INVITE,
                onReconnect = when {
                    membershipCommand && (membershipNeedsRestore || restoreError != null) -> onRefresh
                    room?.status == RoomStatus.OPEN && !commandsAvailable && !reconnecting -> ::reconnect
                    else -> null
                },
                mutationsDisabledReason = disabledReason,
                onSubmit = { first, second ->
                    perform(requireReady = !membershipCommand) {
                        if (!membershipCommand && repository.state.value.room?.roomId != dialogRoomId) {
                            throw RepositoryException("ROOM_CHANGED", "Текущая комната изменилась. Закрой форму и открой её заново.")
                        }
                        when (active) {
                            RoomDialog.CREATE -> { repository.createRoom(first.toInt() * 60, second.toInt() * 60); page = RealPage.ROOM }
                            RoomDialog.JOIN -> { repository.joinRoom(first); page = RealPage.ROOM }
                            RoomDialog.GOAL -> repository.updateGoal(first)
                            RoomDialog.ADD_TASK -> repository.addTask(first)
                            RoomDialog.EDIT_TASK -> repository.updateTask(requireNotNull(selectedTask).id, title = first)
                            RoomDialog.DELETE_TASK -> repository.deleteTask(requireNotNull(selectedTask).id)
                            RoomDialog.SETTINGS -> repository.updateSettings(first.toInt() * 60, second.toInt() * 60)
                            RoomDialog.FINISH_ROUND -> repository.finishRound(requireNotNull(confirmationRoundId))
                            RoomDialog.CLOSE_ROOM -> { repository.closeRoom(); page = RealPage.ROOM }
                            RoomDialog.INVITE -> Unit
                            RoomDialog.PROFILE, RoomDialog.RESET -> throw RepositoryException("DEMO_ONLY", "Действие доступно только в деморежиме")
                        }
                        dialog = null
                    }
                },
            )
        }
    }
}

private fun Round.isTerminal() = status in listOf(RoundStatus.COMPLETED, RoundStatus.CANCELLED)

private fun safeRealError(failure: Exception): String {
    val error = failure as? RepositoryException ?: return "Не удалось выполнить действие. Попробуй ещё раз."
    val message = when (error.code) {
        "REVISION_CONFLICT" -> "Комната изменилась. Проверь актуальные данные и форму перед новым действием."
        "ROOM_RESTORE_REQUIRED" -> "Результат предыдущего запроса неизвестен. Сначала обнови текущую комнату, чтобы не отправить действие повторно."
        else -> error.message
    }
    // Transport has already replaced server-supplied text with safe messages for known fields.
    val labels = mapOf(
        "code" to "Код комнаты", "goal" to "Цель", "title" to "Задача", "task" to "Задача", "tasks" to "Задачи",
        "focusDurationSeconds" to "Фокус", "breakDurationSeconds" to "Перерыв",
    )
    val fields = error.fieldErrors.mapNotNull { (field, text) -> labels[field]?.let { "$it: $text" } }
    return (listOf(message) + fields).joinToString("\n")
}

private fun connectionLabel(status: RealConnectionStatus): String = when (status) {
    RealConnectionStatus.NONE -> "Нет соединения с комнатой"
    RealConnectionStatus.CONNECTING -> "Подключаемся к комнате…"
    RealConnectionStatus.SYNCHRONIZING -> "Получаем актуальный снимок…"
    RealConnectionStatus.CONNECTED -> "Соединение установлено"
    RealConnectionStatus.DISCONNECTED -> "Соединение с комнатой потеряно"
}

@Composable
private fun RealNav(label: String, icon: FocusIcon, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val content: @Composable RowScope.() -> Unit = { FocusIcon(icon); Spacer(Modifier.width(8.dp)); Text(label) }
    if (selected) PrimaryButton(onClick, Modifier.fillMaxWidth(), enabled, content = content)
    else SecondaryButton(onClick, Modifier.fillMaxWidth(), enabled, content = content)
}

@Composable
private fun RealMessage(value: String, error: Boolean = false) {
    Surface(color = if (error) FocusDuoColors.ErrorSurface else FocusDuoColors.PaleGreen, shape = MaterialTheme.shapes.small) {
        Text(value, Modifier.fillMaxWidth().padding(14.dp), color = if (error) FocusDuoColors.Error else FocusDuoColors.Text, style = MaterialTheme.typography.body2)
    }
}
