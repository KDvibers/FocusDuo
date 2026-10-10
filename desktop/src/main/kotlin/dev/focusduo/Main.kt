package dev.focusduo

import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.focusduo.designsystem.FocusDuoTheme
import dev.focusduo.presentation.StartupScreen
import java.awt.Dimension

fun main(args: Array<String>) {
    val options = LaunchOptions.parse(args)
    if (options.help) {
        println("FocusDuo Desktop: --demo [--width=1280] [--height=800]")
        println("Без --demo открывается вход на сервер; адрес задаётся в настройках или FOCUSDUO_BASE_URL.")
        return
    }
    application {
        var demo by remember { mutableStateOf(options.demo) }
        val windowState = rememberWindowState(width = options.width.dp, height = options.height.dp)
        Window(
            onCloseRequest = ::exitApplication,
            title = if (demo) "FocusDuo — Демо" else "FocusDuo",
            state = windowState,
        ) {
            SideEffect { window.minimumSize = Dimension(840, 600) }
            FocusDuoTheme {
                StartupScreen(demo = demo, onEnterDemo = { demo = true }, onExit = ::exitApplication)
            }
        }
    }
}
