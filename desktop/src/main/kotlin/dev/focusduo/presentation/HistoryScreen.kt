package dev.focusduo.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.focusduo.designsystem.*
import dev.focusduo.domain.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The list contains only the most recent results returned by the repository, never all-time totals. */
@Composable
fun HistoryScreen(
    items: List<HistoryItem>,
    currentUserId: String,
    busy: Boolean,
    onOpen: (HistoryItem) -> Unit,
    onGoHome: () -> Unit,
    onRetry: () -> Unit,
    error: String? = null,
) {
    var filter by remember { mutableStateOf(HistoryFilter.ALL) }
    val zone = remember { ZoneId.systemDefault() }
    val visibleItems = filterHistory(items, filter)

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text("История сессий", style = MaterialTheme.typography.h2)
        Text("Последние 50 завершённых и прерванных раундов. Даты окончания: ${zone.id}.", style = MaterialTheme.typography.body2, color = FocusDuoColors.Secondary)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val filterButtons: @Composable () -> Unit = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HistoryFilter.entries.forEach { option ->
                        if (filter == option) PrimaryButton({ filter = option }, enabled = !busy) { Text(option.label) }
                        else SecondaryButton({ filter = option }, enabled = !busy) { Text(option.label) }
                    }
                }
            }
            if (maxWidth >= 600.dp) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                filterButtons()
                TextButton(onRetry, enabled = !busy) { Text("Обновить") }
            } else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                filterButtons()
                TextButton(onRetry, enabled = !busy) { Text("Обновить") }
            }
        }
        when {
            busy -> FocusCard(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(28.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    Text("Загружаем историю…", style = MaterialTheme.typography.body1)
                }
            }
            error != null -> FocusCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(26.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Не удалось получить историю", style = MaterialTheme.typography.h3)
                    Text(error, color = FocusDuoColors.Error, style = MaterialTheme.typography.body2)
                    SecondaryButton(onRetry) { Text("Повторить") }
                }
            }
            items.isEmpty() -> FocusCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Здесь появятся ваши результаты", style = MaterialTheme.typography.h3)
                    Text("Завершите рабочий раунд или перерыв. Сохранятся активное время, цели и задачи обоих участников.", color = FocusDuoColors.Secondary)
                    PrimaryButton(onGoHome) { Text("На главную") }
                }
            }
            visibleItems.isEmpty() -> FocusCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(if (filter == HistoryFilter.FOCUS) "Рабочих раундов пока нет" else "Перерывов пока нет", style = MaterialTheme.typography.h3)
                    Text("Другие результаты доступны во вкладке «Все».", color = FocusDuoColors.Secondary)
                    SecondaryButton({ filter = HistoryFilter.ALL }) { Text("Показать все") }
                }
            }
            else -> {
                Text("Показано ${visibleItems.size} из ${items.size} сохранённых результатов", style = MaterialTheme.typography.caption, color = FocusDuoColors.Secondary)
                visibleItems.forEach { item ->
                    key(item.roundId) { HistoryResultCard(item, currentUserId, zone) { onOpen(item) } }
                }
            }
        }
    }
}

@Composable
private fun HistoryResultCard(item: HistoryItem, currentUserId: String, zone: ZoneId, onOpen: () -> Unit) {
    val cancelled = item.status == RoundStatus.CANCELLED || item.completionReason == CompletionReason.ROOM_CLOSED
    val warning = cancelled || item.completionReason == CompletionReason.MANUAL
    FocusCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val heading: @Composable () -> Unit = {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(if (item.kind == RoundKind.FOCUS) "Совместная работа" else "Перерыв", style = MaterialTheme.typography.h3)
                        Text(formatHistoryDate(item.endedAt, zone), style = MaterialTheme.typography.body2, color = FocusDuoColors.Secondary)
                    }
                }
                val status: @Composable () -> Unit = {
                    Surface(color = if (warning) Color(0xFFF8EEDB) else FocusDuoColors.PaleGreen, shape = RoundedCornerShape(50)) {
                        Text(historyStatusLabel(item), Modifier.padding(horizontal = 12.dp, vertical = 7.dp), style = MaterialTheme.typography.caption, color = if (warning) Color(0xFF8A6730) else FocusDuoColors.Green)
                    }
                }
                if (maxWidth >= 540.dp) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    heading()
                    status()
                } else Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    heading()
                    status()
                }
            }
            Text("${formatActiveTime(item.activeElapsedMs)} ${if (item.kind == RoundKind.FOCUS) "фокуса" else "перерыва"} · план ${item.durationSeconds / 60} мин", style = MaterialTheme.typography.subtitle1)
            Text("Активное время без пауз", style = MaterialTheme.typography.caption, color = FocusDuoColors.Secondary)
            Divider(color = FocusDuoColors.Border)
            item.participants.sortedByDescending { it.userId == currentUserId }.forEach { participant ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                    FocusIcon(FocusIcon.People, Modifier.padding(top = 2.dp).size(19.dp), FocusDuoColors.Secondary)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("${participant.displayName}${if (participant.userId == currentUserId) " · ты" else ""}", style = MaterialTheme.typography.body2)
                        Text("${participant.completedTaskCount} из ${participant.totalTaskCount} задач выполнено", color = FocusDuoColors.Secondary, style = MaterialTheme.typography.caption)
                    }
                }
            }
            SecondaryButton(onOpen) {
                Text("Открыть результат")
                Spacer(Modifier.width(10.dp))
                FocusIcon(FocusIcon.Arrow, Modifier.size(18.dp))
            }
        }
    }
}

internal enum class HistoryFilter(val label: String) { ALL("Все"), FOCUS("Фокус"), BREAK("Перерыв") }

internal fun filterHistory(items: List<HistoryItem>, filter: HistoryFilter): List<HistoryItem> = when (filter) {
    HistoryFilter.ALL -> items
    HistoryFilter.FOCUS -> items.filter { it.kind == RoundKind.FOCUS }
    HistoryFilter.BREAK -> items.filter { it.kind == RoundKind.BREAK }
}

internal fun historyStatusLabel(item: HistoryItem): String = when {
    item.status == RoundStatus.CANCELLED || item.completionReason == CompletionReason.ROOM_CLOSED -> "Прервано · комната закрыта"
    item.completionReason == CompletionReason.MANUAL -> "Завершено досрочно"
    else -> "Завершено по времени"
}

internal fun formatHistoryDate(instant: Instant, zone: ZoneId): String =
    DateTimeFormatter.ofPattern("d MMM yyyy · HH:mm", Locale.forLanguageTag("ru")).withZone(zone).format(instant)
