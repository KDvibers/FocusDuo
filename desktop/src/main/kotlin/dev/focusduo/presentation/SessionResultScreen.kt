package dev.focusduo.presentation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.focusduo.designsystem.*
import dev.focusduo.domain.*

/** Results use frozen history data, including active time that excludes every pause. */
@Composable
fun SessionResultScreen(
    item: HistoryItem,
    currentUserId: String,
    room: RoomSnapshot?,
    busy: Boolean,
    onBackToRoom: () -> Unit,
    onStartBreak: () -> Unit,
    onStartFocus: () -> Unit,
    onHome: () -> Unit,
    onBackToHistory: (() -> Unit)? = null,
) {
    val cancelled = item.status == RoundStatus.CANCELLED || item.completionReason == CompletionReason.ROOM_CLOSED
    val manual = item.completionReason == CompletionReason.MANUAL
    val focus = item.kind == RoundKind.FOCUS
    val sameRoom = room?.roomId == item.roomId
    val open = sameRoom && room?.status == RoomStatus.OPEN
    val currentRound = room?.round
    val owner = room?.ownerId == currentUserId
    val canStartBreak = open && owner && !busy && currentRound?.id == item.roundId && currentRound.status == RoundStatus.COMPLETED && currentRound.kind == RoundKind.FOCUS
    val canStartFocus = open && owner && !busy && room?.participants?.size == 2 && currentRound?.status !in listOf(RoundStatus.RUNNING, RoundStatus.PAUSED)
    val title = when {
        cancelled -> if (focus) "Сессия прервана" else "Перерыв прерван"
        manual -> if (focus) "Фокус завершён досрочно" else "Перерыв завершён досрочно"
        focus -> "Сессия завершена"
        else -> "Перерыв завершён"
    }
    val description = when {
        cancelled -> "Комната закрыта. Сохранено время до закрытия; этот раунд не считается успешно завершённым."
        manual -> "Раунд завершён вручную. Сохранено фактическое активное время."
        focus -> "Время фокуса закончилось. Можно отдохнуть или начать следующий раунд."
        else -> "Время перерыва закончилось. Можно возвращаться к общей цели."
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Column(Modifier.widthIn(max = 650.dp).fillMaxWidth().padding(top = 10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ResultMark(cancelled, manual)
            Text(title, style = MaterialTheme.typography.h2, textAlign = TextAlign.Center)
            Text("${formatActiveTime(item.activeElapsedMs)} ${if (focus) "фокуса" else "перерыва"}", style = MaterialTheme.typography.h3, textAlign = TextAlign.Center)
            Text(description, color = FocusDuoColors.Secondary, style = MaterialTheme.typography.body2, textAlign = TextAlign.Center)
            Text("План: ${item.durationSeconds / 60} мин · паузы не включены", style = MaterialTheme.typography.caption, color = FocusDuoColors.Secondary, textAlign = TextAlign.Center)
        }
        val participants = item.participants.sortedByDescending { it.userId == currentUserId }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth >= 700.dp && participants.size > 1) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                participants.forEach { participant ->
                    Box(Modifier.weight(1f)) { ResultParticipantCard(participant, participant.userId == currentUserId) }
                }
            } else Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                participants.forEach { participant -> ResultParticipantCard(participant, participant.userId == currentUserId) }
            }
        }
        FocusCard(Modifier.widthIn(max = 650.dp).fillMaxWidth()) {
            Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when {
                    canStartBreak -> PrimaryButton(onStartBreak, Modifier.fillMaxWidth()) {
                        Text("Начать перерыв · ${room?.breakDurationSeconds?.div(60)} мин")
                    }
                    canStartFocus -> PrimaryButton(onStartFocus, Modifier.fillMaxWidth()) { Text("Начать новый фокус") }
                }
                if (open && !owner) Text("Следующий раунд запускает создатель комнаты.", Modifier.fillMaxWidth(), style = MaterialTheme.typography.caption, color = FocusDuoColors.Secondary, textAlign = TextAlign.Center)
                if (sameRoom && room?.status == RoomStatus.CLOSED) Text("Комната закрыта. На главной можно создать новую.", Modifier.fillMaxWidth(), style = MaterialTheme.typography.caption, color = FocusDuoColors.Secondary, textAlign = TextAlign.Center)
                if (sameRoom) SecondaryButton(onBackToRoom, Modifier.fillMaxWidth(), enabled = !busy) { Text(if (room?.status == RoomStatus.CLOSED) "Посмотреть закрытую комнату" else "Вернуться в комнату") }
                if (onBackToHistory != null) SecondaryButton(onBackToHistory, Modifier.fillMaxWidth(), enabled = !busy) { Text("К истории") }
                TextButton(onHome, Modifier.fillMaxWidth(), enabled = !busy) { Text("На главную") }
            }
        }
        Text("Снимок задач и целей сохранён в момент завершения раунда.", style = MaterialTheme.typography.caption, color = FocusDuoColors.Secondary, textAlign = TextAlign.Center)
    }
}

@Composable
private fun ResultMark(cancelled: Boolean, manual: Boolean) {
    val foreground = if (cancelled || manual) Color(0xFF8A6730) else FocusDuoColors.Green
    val background = if (cancelled || manual) Color(0xFFF8EEDB) else FocusDuoColors.PaleGreen
    Box(Modifier.size(58.dp).clip(CircleShape).background(background).semantics {
        contentDescription = when { cancelled -> "Раунд отменён"; manual -> "Досрочное завершение"; else -> "Раунд завершён по времени" }
    }, contentAlignment = Alignment.Center) {
        if (!cancelled && !manual) FocusIcon(FocusIcon.Check, Modifier.size(33.dp), foreground)
        else Canvas(Modifier.size(30.dp)) {
            val stroke = 2.4.dp.toPx()
            if (cancelled) {
                drawLine(foreground, Offset(size.width * .25f, size.height * .25f), Offset(size.width * .75f, size.height * .75f), stroke, StrokeCap.Round)
                drawLine(foreground, Offset(size.width * .75f, size.height * .25f), Offset(size.width * .25f, size.height * .75f), stroke, StrokeCap.Round)
            } else {
                drawLine(foreground, Offset(size.width / 2, size.height * .2f), Offset(size.width / 2, size.height * .58f), stroke, StrokeCap.Round)
                drawCircle(foreground, 1.5.dp.toPx(), Offset(size.width / 2, size.height * .8f))
            }
        }
    }
}

@Composable
private fun ResultParticipantCard(participant: HistoryParticipant, own: Boolean) {
    FocusCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(46.dp).clip(CircleShape).background(if (own) FocusDuoColors.PaleGreen else Color(0xFFFAEFD6)), contentAlignment = Alignment.Center) {
                    Text(if (participant.displayName.isBlank()) "?" else String(Character.toChars(participant.displayName.codePointAt(0))).uppercase(), fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(participant.displayName, style = MaterialTheme.typography.subtitle1)
                    Text(if (own) "Ты" else "Участник", style = MaterialTheme.typography.caption, color = FocusDuoColors.Secondary)
                }
                Surface(color = FocusDuoColors.Background, shape = RoundedCornerShape(50)) {
                    Text("${participant.completedTaskCount}/${participant.totalTaskCount}", Modifier.padding(horizontal = 10.dp, vertical = 5.dp), style = MaterialTheme.typography.body2, color = FocusDuoColors.Green)
                }
            }
            Text("${participant.completedTaskCount} из ${participant.totalTaskCount} задач выполнено", style = MaterialTheme.typography.subtitle1)
            Text(if (participant.goal.isBlank()) "Цель не указана" else "Цель: ${participant.goal}", color = FocusDuoColors.Secondary, style = MaterialTheme.typography.body2)
            Divider(color = FocusDuoColors.Border)
            if (participant.tasks.isEmpty()) Text("В этом раунде не было задач", style = MaterialTheme.typography.body2, color = FocusDuoColors.Secondary)
            else participant.tasks.forEach { task ->
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Surface(Modifier.padding(top = 3.dp).size(21.dp), color = if (task.completed) FocusDuoColors.Green else Color.White, shape = RoundedCornerShape(5.dp), border = if (task.completed) null else BorderStroke(1.dp, FocusDuoColors.Border)) {
                        if (task.completed) FocusIcon(FocusIcon.Check, Modifier.padding(3.dp), Color.White)
                    }
                    Text(task.title, Modifier.weight(1f), style = MaterialTheme.typography.body2, color = if (task.completed) FocusDuoColors.Secondary else FocusDuoColors.Text, textDecoration = if (task.completed) TextDecoration.LineThrough else TextDecoration.None)
                }
            }
        }
    }
}

internal fun formatActiveTime(activeElapsedMs: Long): String {
    val seconds = activeElapsedMs.coerceAtLeast(0L) / 1_000L
    if (seconds == 0L) return "Менее секунды"
    val minutes = seconds / 60L
    val rest = seconds % 60L
    return when {
        minutes == 0L -> "$rest сек"
        rest == 0L -> "$minutes мин"
        else -> "$minutes мин $rest сек"
    }
}
