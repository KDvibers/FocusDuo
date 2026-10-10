package dev.focusduo.presentation

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.focusduo.designsystem.*
import dev.focusduo.domain.AuthValidation
import dev.focusduo.domain.RepositoryException

/** Explicit local authentication demo; passwords are held only by this mounted form. */
@Composable
fun AuthScreen(
    initialUsername: String,
    busy: Boolean,
    error: RepositoryException?,
    onLogin: (String, String) -> Unit,
    onRegister: (String, String, String) -> Unit,
    onUseSample: () -> Unit,
) {
    var registration by remember { mutableStateOf(false) }
    // A new server snapshot or saved username must not replace an unfinished draft.
    var username by remember { mutableStateOf(initialUsername) }
    var displayName by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var validationError by remember { mutableStateOf<RepositoryException?>(null) }
    var showRepositoryError by remember(error) { mutableStateOf(true) }
    val visibleError = validationError ?: error.takeIf { showRepositoryError }
    val firstInput = remember { FocusRequester() }
    val scroll = rememberScrollState()

    fun edited() {
        validationError = null
        showRepositoryError = false
    }
    fun selectMode(register: Boolean) {
        if (registration == register) return
        registration = register
        password = ""
        passwordVisible = false
        edited()
    }
    fun submit() {
        if (busy) return
        try {
            if (registration) {
                val credentials = AuthValidation.registration(username, displayName, password)
                validationError = null
                showRepositoryError = false
                onRegister(credentials.username, credentials.displayName, credentials.password)
            } else {
                val credentials = AuthValidation.login(username, password)
                validationError = null
                showRepositoryError = false
                onLogin(credentials.username, credentials.password)
            }
        } catch (failure: RepositoryException) {
            validationError = failure
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(scroll).padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                FocusDuoLogo()
                DemoBadge()
            }
            Spacer(Modifier.height(28.dp))
            FocusCard(Modifier.widthIn(max = 560.dp).fillMaxWidth()) {
                Column(Modifier.padding(30.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(if (registration) "Создать демоаккаунт" else "Войти в FocusDuo", style = MaterialTheme.typography.h2)
                    Text("Это явный деморежим. Аккаунты и комнаты работают локально, без подключения к серверу.", color = FocusDuoColors.Secondary, style = MaterialTheme.typography.body2)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        AuthModeButton("Вход", !registration, !busy, Modifier.weight(1f)) { selectMode(false) }
                        AuthModeButton("Регистрация", registration, !busy, Modifier.weight(1f)) { selectMode(true) }
                    }
                    AuthInput(
                        label = "Имя пользователя", value = username,
                        onChange = { username = it; edited() }, hint = "3–32 символа: латиница, цифры и _",
                        error = visibleError?.fieldErrors?.get("username"), enabled = !busy,
                        focusRequester = firstInput, onSubmit = ::submit,
                    )
                    if (registration) AuthInput(
                        label = "Отображаемое имя", value = displayName,
                        onChange = { displayName = it; edited() }, hint = "Так тебя увидит второй участник · 2–40 символов",
                        error = visibleError?.fieldErrors?.get("displayName"), enabled = !busy, onSubmit = ::submit,
                    )
                    AuthInput(
                        label = "Пароль", value = password,
                        onChange = { password = it; edited() },
                        hint = if (registration) "10–128 символов. Пароль не сохраняется на диск." else "Пароль не сохраняется на диск.",
                        error = visibleError?.fieldErrors?.get("password"), enabled = !busy,
                        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            TextButton(onClick = { passwordVisible = !passwordVisible }, enabled = !busy) {
                                Text(if (passwordVisible) "Скрыть" else "Показать", style = MaterialTheme.typography.caption)
                            }
                        },
                        onSubmit = ::submit,
                    )
                    if (visibleError != null) Surface(color = FocusDuoColors.ErrorSurface, shape = MaterialTheme.shapes.small) {
                        Text(visibleError.message, Modifier.fillMaxWidth().padding(12.dp), color = FocusDuoColors.Error, style = MaterialTheme.typography.body2)
                    }
                    PrimaryButton(onClick = ::submit, modifier = Modifier.fillMaxWidth(), enabled = !busy) {
                        if (busy) {
                            CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                        }
                        Text(if (busy) "Подождите…" else if (registration) "Создать аккаунт" else "Войти")
                    }
                    Divider(color = FocusDuoColors.Border)
                    Text("Хочешь сразу посмотреть приложение?", style = MaterialTheme.typography.subtitle1)
                    Text("Демоаккаунт: demo_user\nПароль: FocusDuoDemo1!", style = MaterialTheme.typography.body2, color = FocusDuoColors.Secondary)
                    SecondaryButton(
                        onClick = { password = ""; validationError = null; showRepositoryError = false; onUseSample() },
                        modifier = Modifier.fillMaxWidth(), enabled = !busy,
                    ) { Text("Войти демоаккаунтом") }
                }
            }
            Spacer(Modifier.height(20.dp))
            Text("После перезапуска потребуется повторный вход.", style = MaterialTheme.typography.caption, color = FocusDuoColors.Secondary)
        }
        VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 8.dp))
    }
    LaunchedEffect(registration) { firstInput.requestFocus() }
}

@Composable
private fun AuthModeButton(label: String, selected: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    if (selected) PrimaryButton(onClick, modifier, enabled) { Text(label) }
    else SecondaryButton(onClick, modifier, enabled) { Text(label) }
}

@Composable
private fun AuthInput(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    hint: String,
    error: String?,
    enabled: Boolean,
    focusRequester: FocusRequester? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingIcon: (@Composable () -> Unit)? = null,
    onSubmit: () -> Unit,
) {
    val focus = LocalFocusManager.current
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        OutlinedTextField(
            value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true,
            enabled = enabled, isError = error != null, textStyle = MaterialTheme.typography.body1,
            visualTransformation = visualTransformation, trailingIcon = trailingIcon,
            modifier = Modifier.fillMaxWidth()
                .then(if (focusRequester == null) Modifier else Modifier.focusRequester(focusRequester))
                .semantics { contentDescription = label }
                .onPreviewKeyEvent {
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
