package dev.focusduo.data

import dev.focusduo.domain.RepositoryException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import kotlin.test.*

class DesktopPreferencesStoreTest {
    @TempDir lateinit var directory: Path

    @Test fun `server address normalizes supported origins including IPv6`() {
        assertEquals("https://example.com:8443", ServerAddress.normalize(" HTTPS://EXAMPLE.COM:8443/ "))
        assertEquals("http://localhost", ServerAddress.normalize("http://localhost"))
        assertEquals("http://[::1]:8090", ServerAddress.normalize("http://[::1]:8090/"))
    }

    @Test fun `server address rejects credentials paths queries fragments and invalid schemes or ports`() {
        listOf("", "localhost:8090", "ftp://localhost", "http:///", "http://user:secret@localhost",
            "http://localhost/api/v1", "http://localhost//", "http://localhost?token=secret",
            "http://localhost#", "http://localhost:0", "http://localhost:65536", "http://local host")
            .forEach { value ->
                val error = assertFailsWith<RepositoryException> { ServerAddress.normalize(value) }
                assertEquals("VALIDATION_ERROR", error.code)
                assertEquals(setOf("baseUrl"), error.fieldErrors.keys)
                assertFalse(error.message.contains("secret"))
            }
    }

    @Test fun `missing preferences use contract default and blank username`() = runBlocking<Unit> {
        val loaded = store().load()
        assertEquals(DesktopPreferences(), loaded.preferences)
        assertNull(loaded.warning)
        assertFalse(Files.exists(file()))
    }

    @Test fun `atomic save creates directories normalizes and persists only whitelisted fields`() = runBlocking<Unit> {
        val target = directory.resolve("nested/preferences.properties")
        val preferencesStore = DesktopPreferencesStore(target, emptyMap())
        val normalized = preferencesStore.save(DesktopPreferences(" HTTPS://EXAMPLE.COM/ ", " ANNA_1 "))
        assertEquals(DesktopPreferences("https://example.com", "anna_1"), normalized)
        val values = Properties().apply { Files.newInputStream(target).use(::load) }
        assertEquals(setOf("baseUrl", "rememberedUsername"), values.stringPropertyNames())
        assertEquals(normalized, preferencesStore.load().preferences)
        preferencesStore.save(DesktopPreferences())
        assertEquals(DesktopPreferences(), preferencesStore.load().preferences)
        Files.list(target.parent).use { assertEquals(listOf(target), it.toList()) }
    }

    @Test fun `unknown disk fields are ignored and removed by the next save`() = runBlocking<Unit> {
        Files.writeString(file(), "baseUrl=http://localhost:8091\nrememberedUsername=anna\npassword=NeverSaveThis\naccessToken=NeitherThis\n")
        val loaded = store().load()
        assertEquals(DesktopPreferences("http://localhost:8091", "anna"), loaded.preferences)
        store().save(loaded.preferences)
        val saved = Files.readString(file())
        assertFalse(saved.contains("password"))
        assertFalse(saved.contains("accessToken"))
        assertFalse(saved.contains("NeverSaveThis"))
    }

    @Test fun `environment address overrides saved address and invalid override produces visible warning`() = runBlocking<Unit> {
        store().save(DesktopPreferences("http://localhost:8091", "anna"))
        val overridden = store(mapOf("FOCUSDUO_BASE_URL" to "https://example.com/")).load()
        assertEquals(DesktopPreferences("https://example.com", "anna"), overridden.preferences)
        assertNull(overridden.warning)
        val invalid = store(mapOf("FOCUSDUO_BASE_URL" to "not-an-origin")).load()
        assertEquals(DesktopPreferences("http://localhost:8091", "anna"), invalid.preferences)
        assertTrue(invalid.warning!!.contains("FOCUSDUO_BASE_URL"))
        assertEquals("http://localhost:8091", store(mapOf("FOCUSDUO_BASE_URL" to " ")).load().preferences.baseUrl)
    }

    @Test fun `invalid saved settings and unreadable properties recover with warnings`() = runBlocking<Unit> {
        Files.writeString(file(), "baseUrl=broken\nrememberedUsername=bad!\n")
        val invalid = store().load()
        assertEquals(DesktopPreferences(), invalid.preferences)
        assertNotNull(invalid.warning)
        Files.writeString(file(), "baseUrl=\\uZZZZ\n")
        val malformed = store().load()
        assertEquals(DesktopPreferences(), malformed.preferences)
        assertNotNull(malformed.warning)
        val unreadable = DesktopPreferencesStore(directory, emptyMap()).load()
        assertEquals(DesktopPreferences(), unreadable.preferences)
        assertNotNull(unreadable.warning)
    }

    @Test fun `invalid save does not overwrite previous settings and write errors are safe`() = runBlocking<Unit> {
        val preferencesStore = store()
        preferencesStore.save(DesktopPreferences("http://localhost:8091", "anna"))
        val before = Files.readString(file())
        expectError("VALIDATION_ERROR") { preferencesStore.save(DesktopPreferences("bad", "anna")) }
        val usernameError = expectError("VALIDATION_ERROR") { preferencesStore.save(DesktopPreferences(rememberedUsername = "bad!")) }
        assertEquals(setOf("rememberedUsername"), usernameError.fieldErrors.keys)
        assertEquals(before, Files.readString(file()))
        val writeError = expectError("INTERNAL_ERROR") { DesktopPreferencesStore(directory, emptyMap()).save(DesktopPreferences()) }
        assertFalse(writeError.message.contains(directory.toString()))
    }

    private fun file(): Path = directory.resolve("preferences.properties")
    private fun store(environment: Map<String, String> = emptyMap()) = DesktopPreferencesStore(file(), environment)

    private suspend fun expectError(code: String, action: suspend () -> Any?): RepositoryException {
        val error = try { action(); fail("Expected $code") } catch (error: RepositoryException) { error }
        assertEquals(code, error.code)
        return error
    }
}
