package dev.focusduo.presentation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.focusduo.designsystem.FocusDuoColors

/** Demo is constructed only after an explicit selection; no network failure can activate it. */
@Composable
fun StartupScreen(demo: Boolean, onEnterDemo: () -> Unit, onExit: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = FocusDuoColors.Background) {
        if (demo) DemoWorkspace(onExit) else RealWorkspace(onEnterDemo, onExit)
    }
}
