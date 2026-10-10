package dev.focusduo.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.focusduo.data.DesktopPreferences
import dev.focusduo.designsystem.*
import dev.focusduo.domain.RepositoryException
import dev.focusduo.domain.RoomSnapshot
import dev.focusduo.domain.RoomStatus
import dev.focusduo.domain.RoundStatus
import dev.focusduo.domain.User

/** Settings use their own draft and commit it only through an explicit save. */
@Composable
fun SettingsScreen(
    preferences: DesktopPreferences,
    currentUser: User,
    room: RoomSnapshot?,
    busy: Boolean,
    error: RepositoryException?,
    onSave: (DesktopPreferences) -> Unit,
    onLogout: () -> Unit,
    onEditRoomSettings: () -> Unit,
    demo: Boolean = true,
    roomCommandsAvailable: Boolean = true,
) {
    var baseUrl by remember { mutableStateOf(preferences.baseUrl) }
    var rememberedUsername by remember { mutableStateOf(preferences.rememberedUsername) }
    var validationError by remember { mutableStateOf<RepositoryException?>(null) }
    var showRepositoryError by remember(error) { mutableStateOf(true) }
    var pendingSave by remember { mutableStateOf<DesktopPreferences?>(null) }
    var saved by remember { mutableStateOf(false) }
    val dirty = baseUrl != preferences.baseUrl || rememberedUsername != preferences.rememberedUsername
    val visibleError = validationError ?: error.takeIf { showRepositoryError }
    val activeRound = room?.round?.status in listOf(RoundStatus.RUNNING, RoundStatus.PAUSED)
    val canEditRoom = roomCommandsAvailable && room?.status == RoomStatus.OPEN && room.ownerId == currentUser.id && !activeRound

    fun edited() {
        validationError = null
        showRepositoryError = false
        saved = false
    }
    fun submit() {
        if (busy || !dirty) return
        try {
            val next = DesktopPreferences(baseUrl, rememberedUsername).normalized()
            validationError = null
            showRepositoryError = false
            saved = false
            pendingSave = next
            onSave(next)
        } catch (failure: RepositoryException) {
            validationError = failure
        }
    }

    // A successful explicit save may normalize fields. Background state updates may not.
    LaunchedEffect(preferences, busy, error) {
        val candidate = pendingSave
        if (!busy && candidate != null) {
            if (preferences == candidate) {
                baseUrl = candidate.baseUrl
                rememberedUsername = candidate.rememberedUsername
                pendingSave = null
                saved = true
            } else if (error != null) pendingSave = null
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text("Параметры", style = MaterialTheme.typography.h2)
        Text("Настрой приложение под себя.", color = FocusDuoColors.Secondary)
        FocusCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(26.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Подключение и вход", style = MaterialTheme.typography.h3)
                Text(if (demo) "Сейчас открыт деморежим. Адрес сохраняется для подключения к серверу; соединение здесь не создаётся." else "Вход выполнен на сервер. После изменения адреса потребуется заново войти на выбранный сервер.", style = MaterialTheme.typography.body2, color = FocusDuoColors.Secondary)
                SettingsInput(
                    label = "Адрес сервера", value = baseUrl, onChange = { baseUrl = it; edited() },
                    hint = "Например, http://127.0.0.1:8090 · без /api/v1",
                    error = visibleError?.fieldErrors?.get("baseUrl"), enabled = !busy, onSubmit = ::submit,
                )
                SettingsInput(
                    label = "Запомнить имя пользователя", value = rememberedUsername,
                    onChange = { rememberedUsername = it; edited() },
                    hint = "Необязательно. Оставь пустым, чтобы не запоминать имя.",
                    error = visibleError?.fieldErrors?.get("rememberedUsername"), enabled = !busy, onSubmit = ::submit,
                )
                Text(if (demo) "Пароль не сохраняется. После перезапуска потребуется повторный вход. Изменение адреса завершит текущий вход; следующий вход останется в деморежиме." else "Пароль и токен не сохраняются на диск. Смена адреса очищает текущую сессию и данные комнаты в клиенте. Комната на сервере остаётся открытой.", style = MaterialTheme.typography.body2, color = FocusDuoColors.Secondary)
                if (visibleError != null) Surface(color = FocusDuoColors.ErrorSurface, shape = MaterialTheme.shapes.small) {
                    Text(visibleError.message, Modifier.fillMaxWidth().padding(12.dp), color = FocusDuoColors.Error, style = MaterialTheme.typography.body2)
                }
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    if (maxWidth < 480.dp) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            PrimaryButton(::submit, Modifier.fillMaxWidth(), enabled = !busy && dirty) { Text(if (busy) "Сохранение…" else "Сохранить параметры") }
                            SecondaryButton({ baseUrl = DesktopPreferences().baseUrl; edited() }, Modifier.fillMaxWidth(), enabled = !busy) { Text("Адрес по умолчанию") }
                        }
                    } else Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        PrimaryButton(::submit, enabled = !busy && dirty) { Text(if (busy) "Сохранение…" else "Сохранить параметры") }
                        SecondaryButton({ baseUrl = DesktopPreferences().baseUrl; edited() }, enabled = !busy) { Text("Адрес по умолчанию") }
                    }
                }
                if (saved) Text("Параметры сохранены", style = MaterialTheme.typography.body2, color = FocusDuoColors.Green)
                else if (dirty) Text("Есть несохранённые изменения", style = MaterialTheme.typography.caption, color = FocusDuoColors.Secondary)
            }
        }
        FocusCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(26.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Твой аккаунт", style = MaterialTheme.typography.h3)
                Text(currentUser.displayName, style = MaterialTheme.typography.subtitle1)
                Text("@${currentUser.username}", color = FocusDuoColors.Secondary)
                Text("Для продолжения после выхода потребуется снова войти.", style = MaterialTheme.typography.body2, color = FocusDuoColors.Secondary)
                SecondaryButton(onLogout, enabled = !busy) { Text(if (demo) "Выйти из демоаккаунта" else "Выйти из аккаунта") }
            }
        }
        FocusCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(26.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Раунды в комнате", style = MaterialTheme.typography.h3)
                Text(
                    when {
                        room == null -> "Создай комнату, чтобы задать длительность фокуса и перерыва."
                        room.status == RoomStatus.CLOSED -> "Эта комната закрыта. Длительности можно задать при создании новой."
                        room.ownerId != currentUser.id -> "Длительности меняет создатель комнаты."
                        activeRound -> "Изменить длительности можно после завершения текущего раунда."
                        !roomCommandsAvailable -> "Для изменения длительностей восстанови соединение с комнатой."
                        else -> "Фокус: ${room.focusDurationSeconds / 60} мин · Перерыв: ${room.breakDurationSeconds / 60} мин"
                    },
                    style = MaterialTheme.typography.body2, color = FocusDuoColors.Secondary,
                )
                SecondaryButton(onEditRoomSettings, enabled = !busy && canEditRoom) { Text("Изменить длительности") }
            }
        }
    }
}

@Composable
private fun SettingsInput(label: String, value: String, onChange: (String) -> Unit, hint: String, error: String?, enabled: Boolean, onSubmit: () -> Unit) {
    val focus = LocalFocusManager.current
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        OutlinedTextField(
            value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true,
            enabled = enabled, isError = error != null, textStyle = MaterialTheme.typography.body1,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = label }.onPreviewKeyEvent {
                when {
                    it.type != KeyEventType.KeyDown -> false
                    it.key == Key.Tab -> { focus.moveFocus(if (it.isShiftPressed) FocusDirection.Previous else FocusDirection.Next); true }
                    it.key == Key.Enter || it.key == Key.NumPadEnter -> { onSubmit(); true }
                    else -> false
                }
            },
            shape = MaterialTheme.shapes.small,
            colors = TextFieldDefaults.outlinedTextFieldColors(unfocusedBorderColor = FocusDuoColors.Border),
        )
        Text(error ?: hint, style = MaterialTheme.typography.caption, color = if (error != null) FocusDuoColors.Error else FocusDuoColors.Secondary)
    }
}
