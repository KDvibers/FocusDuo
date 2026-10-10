package dev.focusduo.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.focusduo.data.DemoAccounts
import dev.focusduo.designsystem.FocusDuoColors
import kotlinx.coroutines.launch

/** Created only after explicit demo selection; authenticated UI is discarded on logout. */
@Composable
fun DemoWorkspace(onExit: () -> Unit) {
    val controller = remember { DemoSessionController() }
    val session by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(controller) { controller.initialize() }
    val repository = session.repository
    if (repository != null) {
        key(repository) {
            DemoApp(
                repository = repository,
                preferences = session.preferences,
                sessionBusy = session.busy,
                settingsError = session.settingsError,
                onSavePreferences = { scope.launch { controller.savePreferences(it) } },
                onLogout = { scope.launch { controller.logout() } },
                onExit = onExit,
            )
        }
    } else {
        Surface(Modifier.fillMaxSize(), color = FocusDuoColors.Background) {
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (session.notice != null) Surface(color = FocusDuoColors.PaleGreen, shape = MaterialTheme.shapes.small) {
                        Text(session.notice!!, Modifier.fillMaxWidth().padding(16.dp), style = MaterialTheme.typography.body2)
                    }
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        if (session.loading) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(Modifier.padding(32.dp))
                            Text("Загружаем настройки…")
                        } else AuthScreen(
                        initialUsername = session.preferences.rememberedUsername,
                        busy = session.busy,
                        error = session.authError,
                        onLogin = { username, password -> scope.launch { controller.login(username, password) } },
                        onRegister = { username, displayName, password -> scope.launch { controller.register(username, displayName, password) } },
                        onUseSample = { scope.launch { controller.login(DemoAccounts.DEMO_USERNAME, DemoAccounts.DEMO_PASSWORD) } },
                    )
                    }
                    androidx.compose.material.TextButton(onClick = onExit, enabled = !session.busy) { Text("Закрыть приложение") }
                }
        }
    }
}
