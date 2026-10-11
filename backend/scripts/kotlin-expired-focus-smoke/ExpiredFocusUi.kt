package dev.focusduo.smoke.expiredfocus

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.focusduo.data.DesktopPreferences
import dev.focusduo.data.RealRepository
import dev.focusduo.designsystem.FocusDuoTheme
import dev.focusduo.domain.RepositoryException
import dev.focusduo.presentation.RealApp
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.exitProcess

/** Test entry only: API bootstrap plus unchanged production RealApp; not Main/RealWorkspace/AuthScreen. */
private data class UiState(val repositories: List<RealRepository> = emptyList(), val finished: Boolean = false)

private class InspectionGates(private val directory: Path) {
    init {
        require(directory.isAbsolute && Files.isDirectory(directory)) { "Absolute control directory required" }
        for (name in listOf("ui-ready", "ui-expired-inspected", "ui-recovered-inspected")) {
            require(!Files.exists(directory.resolve(name))) { "UI inspection gates must be fresh" }
        }
    }

    suspend fun await(name: String) = withTimeout(120_000) {
        val temporary = directory.resolve("ui-phase.${UUID.randomUUID()}.tmp")
        try {
            Files.writeString(temporary,
                """{"kind":"focusduo-expired-focus-ui-v1","waitingFor":"$name","at":"${Instant.now()}"}""",
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
            Files.move(temporary, directory.resolve("ui-phase.json"),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary) }
        println("UI WAIT $name (maximum 120 seconds)")
        while (!Files.isRegularFile(directory.resolve(name))) delay(100)
        println("UI INSPECTED $name")
    }
}

fun main() {
    val directoryValue = System.getProperty("focusduo.smoke.controlDir", "").trim()
    if (directoryValue.isEmpty()) {
        System.err.println("FAIL UI setup: pass an absolute -Dfocusduo.smoke.controlDir for the isolated controller")
        exitProcess(1)
    }
    val gates = try { InspectionGates(Path.of(directoryValue).normalize()) }
    catch (failure: Exception) {
        System.err.println("FAIL UI setup: ${failure.javaClass.simpleName}; controller directory and gates must be fresh")
        exitProcess(1)
    }
    val baseUrl = System.getProperty("focusduo.smoke.baseUrl", "http://127.0.0.1:8090")
    val status = MutableStateFlow(UiState())
    val exitCode = AtomicInteger(1)
    val hostFailed = AtomicBoolean(false)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val runner = scope.launch {
        try {
            runExpiredFocusSmoke(ExpiredFocusHooks(
                onReady = { owner, peer ->
                    status.value = UiState(listOf(owner, peer))
                    gates.await("ui-ready")
                },
                onExpiredOffline = { gates.await("ui-expired-inspected") },
                onRecovered = { gates.await("ui-recovered-inspected") },
            ))
            exitCode.set(0)
        } catch (failure: Throwable) {
            // Error messages and stack traces can contain HTTP request data.
            System.err.println("FAIL expired FOCUS UI adapter: ${failure.javaClass.simpleName}")
        } finally {
            // Core returns only after its bounded, non-cancellable cleanup.
            status.value = UiState(finished = true)
        }
    }
    try {
        application(exitProcessOnExit = false) {
            val current by status.collectAsState()
            LaunchedEffect(current.finished) { if (current.finished) exitApplication() }
            if (!current.finished) repeat(2) { index ->
                key(index) {
                    Window(
                        onCloseRequest = { runner.cancel(CancellationException("Test window closed")) },
                        title = "FocusDuo FOCUS ELAPSED test — ${if (index == 0) "A" else "B"} (component)",
                        state = rememberWindowState(width = 1280.dp, height = 800.dp),
                    ) {
                        FocusDuoTheme {
                            Surface(Modifier.fillMaxSize()) {
                                val repository = current.repositories.getOrNull(index)
                                if (repository == null) Text("Подготовка API-сценария; это тестовое окно.")
                                else key(repository) { ProductionApp(repository, baseUrl, runner) }
                            }
                        }
                    }
                }
            }
        }
    } catch (failure: Throwable) {
        hostFailed.set(true)
        exitCode.set(1)
        runner.cancel(CancellationException("Window host failed"))
        System.err.println("FAIL expired FOCUS window host: ${failure.javaClass.simpleName}")
    } finally {
        // A window failure must still leave time for the core to restore Java and revoke its sessions.
        runBlocking {
            try { withTimeout(100_000) { runner.join() } }
            catch (_: TimeoutCancellationException) {
                exitCode.set(1)
                System.err.println("FAIL UI cleanup: core completion not confirmed")
            }
        }
        scope.cancel()
    }
    exitProcess(if (hostFailed.get()) 1 else exitCode.get())
}

/** Only the shell is test-specific. All room screens, countdowns and automatic result navigation are RealApp. */
@Composable
private fun ProductionApp(repository: RealRepository, baseUrl: String, runner: Job) {
    var preferences by remember { mutableStateOf(DesktopPreferences(baseUrl, repository.state.value.currentUser.username)) }
    var busy by remember { mutableStateOf(false) }
    var restoreError by remember { mutableStateOf<RepositoryException?>(null) }
    var settingsError by remember { mutableStateOf<RepositoryException?>(null) }
    val scope = rememberCoroutineScope()
    RealApp(
        repository = repository,
        preferences = preferences,
        sessionBusy = busy,
        restoreError = restoreError,
        settingsError = settingsError,
        onRefresh = {
            scope.launch {
                busy = true
                try { repository.refreshMe(); repository.refreshCurrentRoom(); restoreError = null }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { restoreError = RepositoryException("NETWORK_ERROR", "Не удалось обновить серверное состояние.") }
                finally { busy = false }
            }
        },
        onSavePreferences = { requested ->
            try {
                val normalized = requested.normalized()
                if (normalized.baseUrl != baseUrl) throw RepositoryException("VALIDATION_ERROR", "В этом тестовом окне сервер фиксирован.")
                preferences = normalized
                settingsError = null
            } catch (_: Exception) {
                settingsError = RepositoryException("VALIDATION_ERROR", "В этом тестовом окне сервер фиксирован; настройки хранятся только в памяти.")
            }
        },
        onLogout = {
            scope.launch {
                busy = true
                try { repository.logout() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { restoreError = RepositoryException("NETWORK_ERROR", "Сервер не подтвердил выход; сценарий остановлен.") }
                finally { runner.cancel(CancellationException("Test logout requested")); busy = false }
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
}
