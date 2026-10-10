package dev.focusduo.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.focusduo.data.FakeRepository
import dev.focusduo.designsystem.*
import dev.focusduo.domain.RepositoryState
import dev.focusduo.domain.Task
import dev.focusduo.domain.AuthValidation.characterCount

@Composable
internal fun DemoRoomDialog(
    kind: RoomDialog, state: RepositoryState, task: Task?, busy: Boolean, error: String?,
    onDismiss: () -> Unit, onCopyCode: () -> Boolean, onSubmit: (String, String) -> Unit,
    demo: Boolean = true, serverAddress: String? = null, mutationsEnabled: Boolean = true,
    onReconnect: (() -> Unit)? = null,
    mutationsDisabledReason: String? = null,
) {
    val room = state.room
    val own = room?.participants?.find { it.userId == state.currentUser.id }
    var first by remember {
        mutableStateOf(when (kind) {
            RoomDialog.CREATE -> "25"
            RoomDialog.SETTINGS -> ((room?.focusDurationSeconds ?: 1500) / 60).toString()
            RoomDialog.GOAL -> own?.goal.orEmpty()
            RoomDialog.EDIT_TASK -> task?.title.orEmpty()
            RoomDialog.PROFILE -> state.currentUser.displayName
            else -> ""
        })
    }
    var second by remember { mutableStateOf(((room?.breakDurationSeconds ?: 300) / 60).toString()) }
    var attempted by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    val durationForm = kind == RoomDialog.CREATE || kind == RoomDialog.SETTINGS
    val taskForm = kind == RoomDialog.ADD_TASK || kind == RoomDialog.EDIT_TASK
    val firstError = when {
        durationForm && first.toIntOrNull() !in 5..180 -> "Укажи целое число от 5 до 180 минут."
        taskForm && characterCount(first.trim()) !in 1..160 -> "Задача должна содержать от 1 до 160 символов."
        kind == RoomDialog.GOAL && characterCount(first) > 240 -> "Цель должна содержать не более 240 символов."
        kind == RoomDialog.PROFILE && characterCount(first.trim()) !in 2..40 -> "Имя должно содержать от 2 до 40 символов."
        kind == RoomDialog.JOIN && first.isBlank() -> "Введи код комнаты."
        else -> null
    }
    val secondError = if (durationForm && second.toIntOrNull() !in 1..60) "Укажи целое число от 1 до 60 минут." else null
    val title = when (kind) {
        RoomDialog.CREATE -> "Создать комнату"
        RoomDialog.JOIN -> "Войти по коду"
        RoomDialog.GOAL -> "Твоя цель на сессию"
        RoomDialog.ADD_TASK -> "Новая задача"
        RoomDialog.EDIT_TASK -> "Изменить задачу"
        RoomDialog.DELETE_TASK -> "Удалить задачу?"
        RoomDialog.SETTINGS -> "Длительность раундов"
        RoomDialog.PROFILE -> "Как тебя называть?"
        RoomDialog.INVITE -> "Пригласить в комнату"
        RoomDialog.RESET -> "Сбросить деморежим?"
        RoomDialog.FINISH_ROUND -> "Завершить раунд досрочно?"
        RoomDialog.CLOSE_ROOM -> "Закрыть комнату для обоих?"
    }
    val inputFocus = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss) {
        FocusCard(Modifier.width(510.dp).heightIn(max = 560.dp)) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(26.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(title, style = MaterialTheme.typography.h3)
                when {
                    durationForm -> {
                        Text("Длительности задаются в минутах. Их может менять только создатель комнаты.", style = MaterialTheme.typography.body2, color = FocusDuoColors.Secondary)
                        RoomInput("Фокус, минут", first, { first = it }, "5–180", if (attempted) firstError else null, true, !busy, inputFocus)
                        RoomInput("Перерыв, минут", second, { second = it }, "1–60", if (attempted) secondError else null, true, !busy)
                    }
                    taskForm -> RoomInput("Текст задачи", first, { first = it }, "${characterCount(first.trim())} / 160", if (attempted) firstError else null, false, !busy, inputFocus)
                    kind == RoomDialog.GOAL -> RoomInput("Цель", first, { first = it }, "${characterCount(first)} / 240 · можно оставить пустой", if (attempted) firstError else null, false, !busy, inputFocus)
                    kind == RoomDialog.PROFILE -> RoomInput("Имя", first, { first = it }, "2–40 символов · только в деморежиме", if (attempted) firstError else null, true, !busy, inputFocus)
                    kind == RoomDialog.JOIN -> {
                        Text(if (demo) "В деморежиме можно войти в подготовленный пример с участником и задачами." else "Введи код, полученный от друга. Оба клиента должны подключаться к одному серверу.", style = MaterialTheme.typography.body2, color = FocusDuoColors.Secondary)
                        RoomInput("Код комнаты", first, { first = it }, "Код нечувствителен к регистру", if (attempted) firstError else null, true, !busy, inputFocus)
                        if (demo) TextButton(onClick = { first = FakeRepository.SAMPLE_ROOM_CODE }, enabled = !busy) { Text("Использовать ${FakeRepository.SAMPLE_ROOM_CODE}") }
                    }
                    kind == RoomDialog.DELETE_TASK -> {
                        Text(task?.title.orEmpty())
                        Text(if (demo) "Задача исчезнет из твоего списка в этой демокомнате." else "Задача будет удалена из твоего списка в комнате. Уже сохранённые итоги не изменятся.", style = MaterialTheme.typography.body2, color = FocusDuoColors.Secondary)
                    }
                    kind == RoomDialog.RESET -> Text("Локальная комната, цели и задачи будут сброшены. Ты вернёшься на главную. Это инструмент демо, данные сервера не затрагиваются.")
                    kind == RoomDialog.FINISH_ROUND -> Text("Текущий раунд завершится для обоих участников. В итог попадут фактическое время работы без пауз и задачи на момент завершения. Перерыв запускается отдельно.")
                    kind == RoomDialog.CLOSE_ROOM -> Text("Комната закроется для обоих участников. Активный или приостановленный раунд будет отменён, а его итог сохранится. Изменять эту комнату после закрытия нельзя; можно создать новую.")
                    kind == RoomDialog.INVITE -> {
                        Text("Код комнаты", style = MaterialTheme.typography.body2, color = FocusDuoColors.Secondary)
                        SelectionContainer { Text(room?.code.orEmpty(), style = MaterialTheme.typography.h2) }
                        SecondaryButton(onClick = { copied = onCopyCode() }) { Text(if (copied) "Скопировано" else "Копировать код") }
                        Text(if (!demo) "Передай другу этот код и адрес ${serverAddress.orEmpty()}. Оба клиента должны использовать один сервер."
                        else if ((room?.participants?.size ?: 0) < 2)
                            "Это локальная демокомната. Второго участника можно добавить кнопкой «Добавить демоучастника» под комнатой. Приглашения никому не отправляются."
                        else "В комнате уже два участника. Для проверки их задач используй «Сменить участника» под комнатой. Приглашения никому не отправляются.",
                            style = MaterialTheme.typography.body2, color = FocusDuoColors.Secondary)
                    }
                }
                if (error != null) Surface(color = FocusDuoColors.ErrorSurface, shape = MaterialTheme.shapes.small) {
                    Text(error, Modifier.fillMaxWidth().padding(12.dp), color = FocusDuoColors.Error, style = MaterialTheme.typography.body2)
                }
                if (!mutationsEnabled && kind != RoomDialog.INVITE) Text(mutationsDisabledReason ?: "Изменения станут доступны после получения актуального снимка комнаты. Черновик сохранён; восстанови соединение и отправь форму снова.", color = FocusDuoColors.Secondary, style = MaterialTheme.typography.body2)
                if (!mutationsEnabled && onReconnect != null) SecondaryButton(onReconnect, enabled = !busy) { Text("Восстановить соединение") }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onDismiss, enabled = !busy) { Text(if (kind == RoomDialog.INVITE) "Готово" else "Отмена") }
                    if (kind != RoomDialog.INVITE) {
                        Spacer(Modifier.width(10.dp))
                        PrimaryButton(onClick = {
                            attempted = true
                            if (firstError == null && secondError == null) onSubmit(first, second)
                        }, enabled = !busy && mutationsEnabled) {
                            Text(if (busy) "Сохранение…" else when (kind) {
                                RoomDialog.CREATE -> "Создать"
                                RoomDialog.JOIN -> "Войти"
                                RoomDialog.DELETE_TASK -> "Удалить"
                                RoomDialog.RESET -> "Сбросить демо"
                                RoomDialog.FINISH_ROUND -> "Завершить"
                                RoomDialog.CLOSE_ROOM -> "Закрыть комнату"
                                else -> "Сохранить"
                            })
                        }
                    }
                }
            }
        }
    }
    if (durationForm || taskForm || kind in listOf(RoomDialog.GOAL, RoomDialog.PROFILE, RoomDialog.JOIN)) {
        LaunchedEffect(Unit) { inputFocus.requestFocus() }
    }
}

@Composable
private fun RoomInput(label: String, value: String, onChange: (String) -> Unit, hint: String, error: String?, singleLine: Boolean, enabled: Boolean, focusRequester: FocusRequester? = null) {
    val focus = LocalFocusManager.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(value, onChange,
            label = { Text(label) }, singleLine = singleLine, maxLines = if (singleLine) 1 else 4,
            enabled = enabled, isError = error != null, textStyle = MaterialTheme.typography.body1,
            modifier = Modifier.fillMaxWidth()
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .semantics { contentDescription = label }
                .onPreviewKeyEvent {
                    if (it.type == KeyEventType.KeyDown && it.key == Key.Tab) {
                        focus.moveFocus(if (it.isShiftPressed) FocusDirection.Previous else FocusDirection.Next)
                        true
                    } else false
                },
            shape = MaterialTheme.shapes.small,
            colors = TextFieldDefaults.outlinedTextFieldColors(unfocusedBorderColor = FocusDuoColors.Border),
        )
        Text(error ?: hint, style = MaterialTheme.typography.caption, color = if (error != null) FocusDuoColors.Error else FocusDuoColors.Secondary)
    }
}
