package dev.focusduo.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.focusduo.designsystem.*
import dev.focusduo.domain.HistoryItem
import dev.focusduo.domain.RoomSnapshot
import dev.focusduo.domain.RoomStatus
import dev.focusduo.domain.RoundKind

@Composable
fun HomeScreen(
    room: RoomSnapshot?, latestResult: HistoryItem?, busy: Boolean,
    onCreate: () -> Unit, onJoin: () -> Unit, onRoom: () -> Unit,
    onHistory: () -> Unit, onLatestResult: () -> Unit,
) {
    val openRoom = room?.status == RoomStatus.OPEN
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Готовы поработать вместе?", style = MaterialTheme.typography.h2)
        Text("Создай комнату или войди по коду, чтобы начать совместную сессию фокуса.", color = FocusDuoColors.Secondary)
    }
    FocusCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text(if (openRoom) "Твоя текущая комната" else "Начните с одной цели", style = MaterialTheme.typography.h3)
            Text(
                if (openRoom) "${room!!.code} · ${room.participants.size}/2 участника. ${if (room.participants.size == 1) "Ждём друга." else "Можно продолжать общую работу."}"
                else "Планируй задачи, делись целью и двигайся вперёд вместе с другом.",
                color = FocusDuoColors.Secondary,
            )
            if (openRoom) PrimaryButton(onRoom, enabled = !busy) { Text("Вернуться в комнату") }
            else BoxWithConstraints(Modifier.fillMaxWidth()) {
                if (maxWidth < 420.dp) Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    PrimaryButton(onCreate, Modifier.fillMaxWidth(), !busy) { Text("Создать комнату") }
                    SecondaryButton(onJoin, Modifier.fillMaxWidth(), !busy) { Text("Войти по коду") }
                } else Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PrimaryButton(onCreate, enabled = !busy) { Text("Создать комнату") }
                    SecondaryButton(onJoin, enabled = !busy) { Text("Войти по коду") }
                }
            }
        }
    }
    FocusCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(26.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Последняя сессия", style = MaterialTheme.typography.h3)
            if (latestResult == null) Text("Здесь появится результат первого завершённого или прерванного раунда.", color = FocusDuoColors.Secondary)
            else {
                Text("${if (latestResult.kind == RoundKind.FOCUS) "Фокус" else "Перерыв"} · ${formatActiveTime(latestResult.activeElapsedMs)}", style = MaterialTheme.typography.subtitle1)
                Text(historyStatusLabel(latestResult), color = FocusDuoColors.Secondary)
                SecondaryButton(onLatestResult, enabled = !busy) { Text("Открыть итог") }
            }
            TextButton(onHistory, enabled = !busy) { Text("Вся доступная история") }
        }
    }
}
