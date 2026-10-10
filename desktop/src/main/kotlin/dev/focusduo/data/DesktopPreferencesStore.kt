package dev.focusduo.data

import dev.focusduo.domain.AuthValidation
import dev.focusduo.domain.RepositoryException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.Properties

data class DesktopPreferences(
    val baseUrl: String = DEFAULT_BASE_URL,
    val rememberedUsername: String = "",
) {
    fun normalized(): DesktopPreferences = DesktopPreferences(
        ServerAddress.normalize(baseUrl), AuthValidation.normalizeRememberedUsername(rememberedUsername),
    )

    companion object { const val DEFAULT_BASE_URL = "http://127.0.0.1:8090" }
}

data class LoadedPreferences(val preferences: DesktopPreferences, val warning: String? = null)

object ServerAddress {
    /** The agreed API and WebSocket paths are appended to this origin, never to an arbitrary path. */
    fun normalize(value: String): String {
        val uri = try { URI(value.trim()) } catch (_: Exception) { invalid() }
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if (scheme !in setOf("http", "https") || uri.isOpaque || uri.host.isNullOrBlank() ||
            uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null ||
            uri.rawPath !in listOf(null, "", "/") || uri.port !in -1..65535 || uri.port == 0) invalid()
        return URI(scheme, null, uri.host.lowercase(Locale.ROOT), uri.port, null, null, null).toASCIIString()
    }

    private fun invalid(): Nothing {
        val message = "Введите адрес http:// или https:// с хостом и, при необходимости, портом; без пути, логина, параметров и #."
        throw RepositoryException("VALIDATION_ERROR", message, mapOf("baseUrl" to message))
    }
}

/** Persistence has an explicit whitelist: server address and username, never credentials or room data. */
class DesktopPreferencesStore(
    private val path: Path = defaultPath(),
    private val environment: Map<String, String> = System.getenv(),
) {
    suspend fun load(): LoadedPreferences = withContext(Dispatchers.IO) {
        val warnings = mutableListOf<String>()
        val values = Properties()
        try {
            if (Files.exists(path)) Files.newInputStream(path).use(values::load)
        } catch (_: Exception) {
            values.clear()
            warnings += "Не удалось прочитать сохранённые настройки. Используются настройки по умолчанию."
        }
        val savedAddress = values.getProperty("baseUrl")?.let { saved ->
            try { ServerAddress.normalize(saved) } catch (_: RepositoryException) {
                warnings += "Сохранённый адрес сервера некорректен. Используется адрес по умолчанию."
                null
            }
        } ?: DesktopPreferences.DEFAULT_BASE_URL
        val username = values.getProperty("rememberedUsername", "").let { saved ->
            try { AuthValidation.normalizeRememberedUsername(saved) } catch (_: RepositoryException) {
                warnings += "Сохранённый логин некорректен. Введите его заново."
                ""
            }
        }
        val address = environment["FOCUSDUO_BASE_URL"]?.takeIf { it.isNotBlank() }?.let { override ->
            try { ServerAddress.normalize(override) } catch (_: RepositoryException) {
                warnings += "FOCUSDUO_BASE_URL содержит некорректный адрес. Используется сохранённый адрес или адрес по умолчанию; режим работы не изменён."
                savedAddress
            }
        } ?: savedAddress
        LoadedPreferences(DesktopPreferences(address, username), warnings.takeIf { it.isNotEmpty() }?.joinToString(" "))
    }

    suspend fun save(preferences: DesktopPreferences): DesktopPreferences = withContext(Dispatchers.IO) {
        val normalized = preferences.normalized()
        val absolute = path.toAbsolutePath().normalize()
        var temporary: Path? = null
        try {
            Files.createDirectories(absolute.parent)
            temporary = Files.createTempFile(absolute.parent, ".focusduo-preferences-", ".tmp")
            val values = Properties().apply {
                setProperty("baseUrl", normalized.baseUrl)
                setProperty("rememberedUsername", normalized.rememberedUsername)
            }
            Files.newOutputStream(temporary).use { values.store(it, "FocusDuo desktop preferences") }
            try {
                Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (_: Exception) {
            throw RepositoryException("INTERNAL_ERROR", "Не удалось сохранить настройки. Проверьте доступ к каталогу настроек.")
        } finally {
            temporary?.let { try { Files.deleteIfExists(it) } catch (_: Exception) { /* Best-effort cleanup. */ } }
        }
        normalized
    }

    companion object {
        fun defaultPath(): Path = System.getProperty("focusduo.preferences.path")?.trim()?.takeIf(String::isNotEmpty)
            ?.let(Path::of) ?: Path.of(System.getProperty("user.home"), ".focusduo", "preferences.properties")
    }
}
