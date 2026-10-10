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
import dev.focusduo.data.PendingCommandInfo
import dev.focusduo.data.RealRepository
import dev.focusduo.designsystem.*
import dev.focusduo.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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
    val pendingCommand by repository.pendingCommand.collectAsState()
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val currentSessionBusy by rememberUpdatedState(sessionBusy)
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
    var conflictNeedsReview by remember { mutableStateOf(false) }
    var retryCompletion by remember { mutableStateOf<(() -> Unit)?>(null) }
    var historyError by remember { mutableStateOf<String?>(null) }
    var resultError by remember { mutableStateOf<String?>(null) }
    var failedResultRequest by remember { mutableStateOf<ResultLoadRequest?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var selectedResult by remember { mutableStateOf<HistoryItem?>(null) }
    var selectedResultOpenedAutomatically by remember { mutableStateOf(false) }
    var observedTerminalId by remember { mutableStateOf(data.room?.round?.takeIf { it.isTerminal() }?.id) }
    var timerTick by remember { mutableLongStateOf(0L) }
    val busy = sessionBusy || operationBusy || resultBusy
    val room = data.room
    val commandsAvailable = repository.canMutate && !conflictNeedsReview
    val displayRoom = remember(room, timerTick) { repository.projectedRoom() }
    val retryDelayStartedAt = remember(connection.status, connection.retryAttempt, connection.retryInMillis) { System.nanoTime() }
    val retryRemainingMillis = remember(connection, timerTick) {
        connection.retryInMillis?.let { delay ->
            (delay - (System.nanoTime() - retryDelayStartedAt).coerceAtLeast(0L) / 1_000_000L).coerceAtLeast(0L)
        }
    }

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

    fun automaticNavigationBlocked() = currentSessionBusy || operationBusy || dialog != null ||
        repository.pendingCommand.value != null || conflictNeedsReview ||
        failedResultRequest?.keepsExplicitRetryVisible(page) == true

    fun automaticDestination() = liveResultDestination(
        page, repository.state.value.room, selectedResult, selectedResultOpenedAutomatically,
        automaticNavigationBlocked(),
    )

    fun loadResult(request: ResultLoadRequest) {
        if (resultBusy || (!request.automatic && (sessionBusy || operationBusy))) return
        if (!request.isRelevant(repository.state.value.room)) {
            if (failedResultRequest == request) { failedResultRequest = null; resultError = null }
            return
        }
        if (!request.automatic) selectedResultOpenedAutomatically = false
        resultBusy = true
        resultError = null
        failedResultRequest = null
        scope.launch {
            try {
                val result = repository.loadHistoryItem(request.roundId)
                val currentRoom = repository.state.value.room
                val currentTerminal = isCurrentTerminalResult(result, currentRoom) && request.isRelevant(currentRoom)
                if (request.destinationAfterLoad(result, page, currentRoom, selectedResult,
                        selectedResultOpenedAutomatically, automaticNavigationBlocked()) == LiveResultDestination.RESULT) {
                    selectedResult = result
                    selectedResultOpenedAutomatically = request.automatic
                    page = RealPage.RESULT
                } else if (currentTerminal && !automaticNavigationBlocked()) {
                    notice = "Раунд завершён. Сохранённый итог доступен в истории."
                }
                // A blocked automatic result remains unobserved until the form/replay is resolved.
                if (currentTerminal && (!request.automatic || !automaticNavigationBlocked())) observedTerminalId = request.roundId
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                // A late failure for the previous automatic result must not cover the new round.
                if (request.isRelevant(repository.state.value.room)) {
                    failedResultRequest = request
                    resultError = safeRealError(failure)
                    // Expose an explicit retry instead of repeatedly fetching a failing result in an effect.
                    if (request.automatic && !automaticNavigationBlocked()) observedTerminalId = request.roundId
                }
            } finally { resultBusy = false }
        }
    }

    fun openResult(roundId: String, automatic: Boolean = false) {
        val originRoom = if (automatic) {
            repository.state.value.room?.takeIf { it.round?.id == roundId }?.roomId ?: return
        } else null
        loadResult(ResultLoadRequest(roundId, originRoom))
    }

    LaunchedEffect(repository) { refreshHistory() }
    LaunchedEffect(room?.roomId, room?.round?.id, room?.round?.status, resultBusy, page,
        selectedResult?.roundId, selectedResultOpenedAutomatically, dialog, pendingCommand,
        conflictNeedsReview, sessionBusy, operationBusy) {
        if (failedResultRequest?.let { it.automatic && !it.isRelevant(room) } == true) {
            failedResultRequest = null
            resultError = null
        }
        if (resultBusy || automaticNavigationBlocked()) return@LaunchedEffect
        if (automaticDestination() == LiveResultDestination.ROOM) {
            page = RealPage.ROOM
            return@LaunchedEffect
        }
        val round = room?.round
        if (round != null && round.isTerminal() && observedTerminalId != round.id) {
            openResult(round.id, automatic = true)
        }
    }

    fun openDialog(kind: RoomDialog, task: Task? = null) {
        if (sessionBusy || operationBusy || resultBusy) return
        error = null
        operationFailure = null
        selectedTask = task
        dialogRoomId = repository.state.value.room?.roomId
        confirmationRoundId = repository.state.value.room?.round?.id
        dialog = kind
    }

    fun perform(requireReady: Boolean = true, onRecovered: (() -> Unit)? = null, action: suspend () -> Unit) {
        if (sessionBusy || operationBusy || resultBusy) return
        operationFailure = null
        if (requireReady && !commandsAvailable) {
            error = when {
                pendingCommand != null -> "Результат предыдущего действия ещё не подтверждён. Используй повтор исходного запроса."
                conflictNeedsReview -> "Сначала проверь актуальные данные после конфликта."
                else -> "Нет актуального соединения с комнатой. Дождись восстановления и отправь действие снова."
            }
            return
        }
        operationBusy = true
        error = null
        notice = null
        scope.launch {
            try { action() }
            catch (cancelled: CancellationException) {
                if (repository.pendingCommand.value != null && (requireReady || onRecovered != null)) retryCompletion = onRecovered
                throw cancelled
            }
            catch (failure: Exception) {
                operationFailure = failure as? RepositoryException
                if (operationFailure?.code == "REVISION_CONFLICT") conflictNeedsReview = true
                if (repository.pendingCommand.value != null && (requireReady || onRecovered != null)) retryCompletion = onRecovered
                error = safeRealError(failure)
            }
            finally { operationBusy = false }
        }
    }

    fun reconnect() {
        perform(requireReady = false) { repository.reconnect() }
    }

    fun retryPending() {
        if (sessionBusy || operationBusy || resultBusy || repository.pendingCommand.value?.retryAvailable != true) return
        operationBusy = true
        operationFailure = null
        error = null
        scope.launch {
            try {
                repository.retryPendingCommand()
                retryCompletion?.invoke()
                retryCompletion = null
                notice = "Сервер подтвердил исходное действие. Повторное изменение не создаётся."
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                operationFailure = failure as? RepositoryException
                if (operationFailure?.code == "REVISION_CONFLICT") conflictNeedsReview = true
                error = safeRealError(failure)
                if (repository.pendingCommand.value == null) retryCompletion = null
            } finally { operationBusy = false }
        }
    }

    fun acknowledgeConflict() {
        conflictNeedsReview = false
        operationFailure = null
        error = null
        notice = "Теперь можно отправить новое действие. Оно будет использовать актуальную версию комнаты."
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

    val reconnecting = connection.status in listOf(RealConnectionStatus.CONNECTING, RealConnectionStatus.SYNCHRONIZING, RealConnectionStatus.RECONNECTING)
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
        LaunchedEffect(page, room?.roomId.takeIf { page == RealPage.ROOM },
            room?.round?.id.takeIf { page == RealPage.ROOM }) { scroll.scrollTo(0) }
        Box(Modifier.weight(1f).fillMaxHeight()) {
            Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Text("Сервер · ${preferences.baseUrl}", color = FocusDuoColors.Green, style = MaterialTheme.typography.body2)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                restoreError?.let { RealMessage(it.message, true) }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    SecondaryButton(onRefresh, enabled = !busy) { Text(if (restoreError == null) "Обновить с сервера" else "Повторить загрузку") }
                }
                if (room?.status == RoomStatus.OPEN || connection.status in listOf(RealConnectionStatus.CONNECTING, RealConnectionStatus.SYNCHRONIZING, RealConnectionStatus.DISCONNECTED, RealConnectionStatus.RECONNECTING)) FocusCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(connectionLabel(connection.status), style = MaterialTheme.typography.subtitle1)
                        connection.message?.let { Text(it, style = MaterialTheme.typography.body2, color = FocusDuoColors.Secondary) }
                        if (connection.retryAttempt > 0) Text(
                            "Попытка ${connection.retryAttempt}" + (retryRemainingMillis?.let { " · следующая через ${(it + 999) / 1000} с" } ?: ""),
                            style = MaterialTheme.typography.body2, color = FocusDuoColors.Secondary,
                        )
                        if (!repository.canMutate) {
                            Text(when (connection.status) {
                                RealConnectionStatus.DISCONNECTED -> "Автоматическое восстановление приостановлено. Данные остаются на экране. Проверь подключение и нажми «Восстановить соединение»."
                                RealConnectionStatus.CONNECTED -> "Актуальное состояние комнаты получено. Подтверди результат незавершённого запроса перед новыми изменениями."
                                RealConnectionStatus.NONE -> "Данные остаются на экране. Для новых изменений восстанови соединение с комнатой."
                                else -> "Соединение восстанавливается автоматически. Данные остаются на экране; изменения доступны после актуального снимка и подтверждения незавершённого запроса."
                            }, style = MaterialTheme.typography.body2, color = FocusDuoColors.Secondary)
                            SecondaryButton(::reconnect, enabled = !busy && connection.status != RealConnectionStatus.SYNCHRONIZING) { Text(if (reconnecting) "Попробовать сейчас" else "Восстановить соединение") }
                        }
                    }
                }
                pendingCommand?.let { PendingRequestCard(it, busy, ::retryPending, ::reconnect) }
                if (conflictNeedsReview) ConflictReviewCard(onReviewed = ::acknowledgeConflict, enabled = !busy)
                val banner = if (dialog == null) error ?: notice else notice
                if (banner != null) RealMessage(banner, error != null && dialog == null)
                if (resultError != null) FocusCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Не удалось загрузить сохранённый итог", style = MaterialTheme.typography.subtitle1)
                        Text(resultError.orEmpty(), color = FocusDuoColors.Error, style = MaterialTheme.typography.body2)
                        SecondaryButton({ failedResultRequest?.let(::loadResult) }, enabled = !busy) { Text("Повторить загрузку итога") }
                    }
                }
                when (page) {
                    RealPage.HOME -> {
                        HomeScreen(
                            room, data.history.firstOrNull(), busy || restoreError != null || (room?.status != RoomStatus.OPEN && !repository.canEnterRoom),
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
                            onStartBreak = { perform(onRecovered = { page = RealPage.ROOM }) { repository.startRound(RoundKind.BREAK); page = RealPage.ROOM } },
                            onStartFocus = { perform(onRecovered = { page = RealPage.ROOM }) { repository.startRound(RoundKind.FOCUS); page = RealPage.ROOM } },
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
            val membershipNeedsRestore = membershipCommand && !repository.canEnterRoom
            val membershipAlreadyExists = membershipCommand && room?.status == RoomStatus.OPEN
            val targetsCurrentRound = active != RoomDialog.FINISH_ROUND ||
                (confirmationRoundId == room?.round?.id && room?.round?.status in listOf(RoundStatus.RUNNING, RoundStatus.PAUSED))
            val canEditSettings = active != RoomDialog.SETTINGS || room?.round?.status !in listOf(RoundStatus.RUNNING, RoundStatus.PAUSED)
            val ready = if (membershipCommand) restoreError == null && !membershipNeedsRestore && !membershipAlreadyExists && pendingCommand == null && !conflictNeedsReview
                else targetsCurrentRoom && targetsCurrentRound && canEditSettings && commandsAvailable
            val disabledReason = when {
                pendingCommand != null -> "Сначала подтверди результат исходного запроса. Новые изменения заблокированы; черновик сохранён."
                conflictNeedsReview -> "Комната изменилась. Сравни черновик с актуальными данными ниже и подтверди проверку перед новым действием."
                membershipCommand && restoreError != null -> "Сначала восстанови данные с сервера. Черновик формы сохранён."
                membershipNeedsRestore -> "Ожидаем актуальные данные о текущей комнате. После восстановления можно отправить форму; черновик сохранён."
                membershipAlreadyExists -> "Сервер подтвердил открытую комнату. Закрой форму и открой её через главную; повторно создавать комнату или входить не требуется."
                !membershipCommand && !targetsCurrentRoom -> "Текущая комната изменилась. Черновик сохранён; закрой форму и проверь новую комнату."
                !membershipCommand && room?.status == RoomStatus.CLOSED -> "Комната уже закрыта сервером. Черновик сохранён, но отправить изменения в эту комнату нельзя."
                !targetsCurrentRound -> "Этот раунд уже завершён или сменился. Черновик сохранён; закрой подтверждение и проверь состояние комнаты."
                !canEditSettings -> "Раунд уже начался. Изменить длительности можно после его завершения; черновик сохранён."
                else -> null
            }
            DemoRoomDialog(
                active, data, selectedTask, busy, operationFailure?.let(::safeRealError) ?: error,
                onDismiss = { if (!sessionBusy && !operationBusy && !resultBusy) { dialog = null; error = null; operationFailure = null } }, onCopyCode = ::copyCode,
                demo = false, serverAddress = preferences.baseUrl, mutationsEnabled = ready || active == RoomDialog.INVITE,
                onReconnect = when {
                    membershipCommand && (membershipNeedsRestore || restoreError != null) -> onRefresh
                    room?.status == RoomStatus.OPEN && !commandsAvailable && !reconnecting -> ::reconnect
                    else -> null
                },
                mutationsDisabledReason = disabledReason,
                dismissLabel = if (pendingCommand != null) "Закрыть форму" else null,
                inputsEnabled = pendingCommand == null,
                recoveryContent = {
                    pendingCommand?.let {
                        PendingRequestCard(it, busy, ::retryPending, ::reconnect,
                            draftVisible = active in listOf(RoomDialog.CREATE, RoomDialog.JOIN, RoomDialog.GOAL, RoomDialog.ADD_TASK, RoomDialog.EDIT_TASK, RoomDialog.SETTINGS))
                    }
                    if (conflictNeedsReview) ConflictReviewCard(
                        serverValue = conflictServerValue(active, room, data.currentUser.id, selectedTask?.id),
                        onReviewed = ::acknowledgeConflict, enabled = !busy,
                    )
                },
                onSubmit = { first, second ->
                    val originRoomId = dialogRoomId
                    val originTaskId = selectedTask?.id
                    perform(requireReady = !membershipCommand, onRecovered = {
                        if (active in listOf(RoomDialog.CREATE, RoomDialog.JOIN, RoomDialog.CLOSE_ROOM)) page = RealPage.ROOM
                        if (dialog == active && dialogRoomId == originRoomId && selectedTask?.id == originTaskId) dialog = null
                    }) {
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
        "PENDING_COMMAND" -> "Исходный запрос ещё не подтверждён. Дождись синхронизации и используй его безопасный повтор."
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
    RealConnectionStatus.RECONNECTING -> "Восстанавливаем соединение…"
}

@Composable
private fun PendingRequestCard(
    pending: PendingCommandInfo,
    busy: Boolean,
    onRetry: () -> Unit,
    onReconnect: () -> Unit,
    draftVisible: Boolean = false,
) {
    FocusCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Нужно подтвердить результат действия", style = MaterialTheme.typography.subtitle1)
            Text(pending.description, style = MaterialTheme.typography.body2, color = FocusDuoColors.Green)
            Text(
                "Ответ сервера не получен. Сначала клиент восстанавливает состояние, затем можно повторить именно исходный запрос без второго изменения." +
                    if (draftVisible) " Черновик сохранён. Поля временно недоступны: повтор использует первоначально отправленные значения." else "",
                style = MaterialTheme.typography.body2, color = FocusDuoColors.Secondary,
            )
            if (!pending.retryAvailable) Text(
                "Ожидаем актуальные данные. Повтор запроса станет доступен после синхронизации.",
                style = MaterialTheme.typography.caption, color = FocusDuoColors.Secondary,
            )
            PrimaryButton(onRetry, Modifier.fillMaxWidth(), enabled = !busy && pending.retryAvailable) {
                Text(if (busy) "Подтверждаем…" else "Повторить исходный запрос")
            }
            if (!pending.retryAvailable) SecondaryButton(onReconnect, Modifier.fillMaxWidth(), enabled = !busy) {
                Text("Попробовать подключиться сейчас")
            }
        }
    }
}

@Composable
private fun ConflictReviewCard(serverValue: String? = null, onReviewed: () -> Unit, enabled: Boolean) {
    Surface(color = FocusDuoColors.ErrorSurface, shape = MaterialTheme.shapes.small) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Комната изменилась", style = MaterialTheme.typography.subtitle1, color = FocusDuoColors.Error)
            Text("Сервер отклонил действие из-за новой версии комнаты. Проверь актуальные данные и сохранившийся черновик; отправка нового действия требует отдельного нажатия.", style = MaterialTheme.typography.body2)
            serverValue?.let { Text(it, style = MaterialTheme.typography.body2) }
            SecondaryButton(onReviewed, Modifier.fillMaxWidth(), enabled = enabled) {
                Text("Данные проверены — разрешить новое действие")
            }
        }
    }
}

private fun conflictServerValue(kind: RoomDialog, room: RoomSnapshot?, userId: String, taskId: String?): String {
    if (room == null) return "Открытой комнаты на сервере нет."
    val own = room.participants.find { it.userId == userId }
    return when (kind) {
        RoomDialog.GOAL -> "Цель на сервере: ${own?.goal?.ifEmpty { "не задана" }.orEmpty()}"
        RoomDialog.EDIT_TASK, RoomDialog.DELETE_TASK -> own?.tasks?.find { it.id == taskId }?.let {
            "Задача на сервере: ${it.title}\n${if (it.completed) "Отмечена выполненной" else "Ещё не выполнена"}"
        } ?: "Этой задачи уже нет в актуальном списке."
        RoomDialog.SETTINGS -> "Сейчас на сервере: фокус ${room.focusDurationSeconds / 60} мин, перерыв ${room.breakDurationSeconds / 60} мин."
        else -> "Актуальная версия комнаты: ${room.revision}. Состояние и списки задач обновлены."
    }
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
