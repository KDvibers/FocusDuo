package dev.focusduo.presentation

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.focusduo.designsystem.*

/** Demo is constructed only after an explicit selection; no network failure can activate it. */
@Composable
fun StartupScreen(demo: Boolean, onEnterDemo: () -> Unit, onExit: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = FocusDuoColors.Background) {
        if (demo) DemoWorkspace(onExit) else Welcome(onEnterDemo, onExit)
    }
}

@Composable
private fun Welcome(onEnterDemo: () -> Unit, onExit: () -> Unit) {
    val scroll = rememberScrollState()
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                FocusDuoLogo()
                Text("Работать вместе. Двигаться вперёд.", style = MaterialTheme.typography.body2, color = FocusDuoColors.Secondary)
            }
            Spacer(Modifier.height(64.dp))
            FocusCard(Modifier.widthIn(max = 660.dp).fillMaxWidth()) {
                Column(Modifier.padding(40.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    Surface(color = FocusDuoColors.PaleGreen, shape = RoundedCornerShape(18.dp)) {
                        Box(Modifier.padding(16.dp)) { FocusIcon(FocusIcon.Leaf, Modifier.size(32.dp)) }
                    }
                    Text("Совместная работа\nначинается с фокуса", style = MaterialTheme.typography.h1)
                    Text("Меньше отвлечений. Больше внимания тому, что важно. Подготовь свою цель и познакомься с FocusDuo.", color = FocusDuoColors.Secondary)
                    Divider(color = FocusDuoColors.Border)
                    Text("Открой локальный демоаккаунт или зарегистрируй новый, создай комнату и подготовь задачи для совместной сессии.", style = MaterialTheme.typography.body2, color = FocusDuoColors.Secondary)
                    PrimaryButton(onEnterDemo, Modifier.fillMaxWidth()) {
                        Text("Открыть деморежим")
                        Spacer(Modifier.width(10.dp))
                        FocusIcon(FocusIcon.Arrow, color = Color.White)
                    }
                    Text("Есть готовый демоаккаунт. Подключение к серверу пока не используется.", style = MaterialTheme.typography.caption, color = FocusDuoColors.Secondary)
                }
            }
            Spacer(Modifier.height(20.dp))
            TextButton(onClick = onExit) { Text("Закрыть приложение", color = FocusDuoColors.Secondary) }
        }
        VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}
