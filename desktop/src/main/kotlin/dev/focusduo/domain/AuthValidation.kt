package dev.focusduo.domain

import java.util.Locale

data class LoginCredentials(val username: String, val password: String) {
    override fun toString(): String = "LoginCredentials(username=$username, password=<redacted>)"
}

data class RegistrationCredentials(val username: String, val displayName: String, val password: String) {
    override fun toString(): String = "RegistrationCredentials(username=$username, displayName=$displayName, password=<redacted>)"
}

/** Shared v1 input rules. In particular, spaces and case in passwords are significant. */
object AuthValidation {
    private val usernamePattern = Regex("[a-z0-9_]{3,32}")

    fun normalizeUsername(username: String): String = normalizedUsername(username).also {
        if (!usernamePattern.matches(it)) requireValid(mapOf("username" to USERNAME_ERROR))
    }

    fun normalizeRememberedUsername(username: String): String = normalizedUsername(username).also {
        if (it.isNotEmpty() && !usernamePattern.matches(it)) {
            requireValid(mapOf("rememberedUsername" to USERNAME_ERROR))
        }
    }

    fun validateLogin(username: String, password: String): Map<String, String> = buildMap {
        if (!usernamePattern.matches(normalizedUsername(username))) put("username", USERNAME_ERROR)
        if (characterCount(password) !in 10..128) put("password", "Пароль — от 10 до 128 символов. Пробелы учитываются.")
    }

    fun validateRegistration(username: String, displayName: String, password: String): Map<String, String> =
        buildMap {
            putAll(validateLogin(username, password))
            if (characterCount(displayName.trim()) !in 2..40) put("displayName", "Имя — от 2 до 40 символов.")
        }

    fun login(username: String, password: String): LoginCredentials {
        requireValid(validateLogin(username, password))
        return LoginCredentials(normalizeUsername(username), password)
    }

    fun registration(username: String, displayName: String, password: String): RegistrationCredentials {
        requireValid(validateRegistration(username, displayName, password))
        return RegistrationCredentials(normalizeUsername(username), displayName.trim(), password)
    }

    private fun requireValid(errors: Map<String, String>) {
        if (errors.isNotEmpty()) throw RepositoryException("VALIDATION_ERROR", "Проверьте заполненные поля.", errors)
    }

    private fun normalizedUsername(username: String): String = username.trim().lowercase(Locale.ROOT)
    fun characterCount(value: String): Int = value.codePointCount(0, value.length)

    private const val USERNAME_ERROR = "Логин — от 3 до 32 символов: латинские буквы, цифры и _."
}
