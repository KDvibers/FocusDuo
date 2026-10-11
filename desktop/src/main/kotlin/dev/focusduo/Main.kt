package dev.focusduo

import androidx.compose.runtime.*
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.focusduo.designsystem.FocusDuoTheme
import dev.focusduo.presentation.StartupScreen
import dev.focusduo.presentation.AppResumeSignals
import dev.focusduo.presentation.ResumeDetector
import java.awt.Dimension
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent

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
            icon = painterResource("icons/focusduo.png"),
            state = windowState,
        ) {
            SideEffect { window.minimumSize = Dimension(840, 600) }
            DisposableEffect(window) {
                val detector = ResumeDetector()
                val listener = object : WindowAdapter() {
                    override fun windowLostFocus(event: WindowEvent) = detector.backgrounded()
                    override fun windowIconified(event: WindowEvent) = detector.backgrounded()
                    override fun windowGainedFocus(event: WindowEvent) = foregrounded()
                    override fun windowDeiconified(event: WindowEvent) = foregrounded()
                    private fun foregrounded() {
                        if (detector.foregrounded()) AppResumeSignals.notifyForegrounded()
                    }
                }
                window.addWindowFocusListener(listener)
                window.addWindowListener(listener)
                onDispose {
                    window.removeWindowFocusListener(listener)
                    window.removeWindowListener(listener)
                }
            }
            FocusDuoTheme {
                StartupScreen(demo = demo, onEnterDemo = { demo = true }, onExit = ::exitApplication)
            }
        }
    }
}
