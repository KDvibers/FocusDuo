package dev.focusduo.data

import dev.focusduo.domain.RepositoryException
import dev.focusduo.domain.RoomStatus
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class DemoAccountsTest {
    @Test fun `sample login normalizes username and restores the same repository`() = runBlocking<Unit> {
        val accounts = DemoAccounts()
        val first = accounts.authenticate(" DEMO_USER ", DemoAccounts.DEMO_PASSWORD)
        val room = first.createRoom()
        val restored = accounts.authenticate(DemoAccounts.DEMO_USERNAME, DemoAccounts.DEMO_PASSWORD)
        assertSame(first, restored)
        assertEquals(room, restored.state.value.room)
        assertEquals(RoomStatus.OPEN, restored.state.value.room!!.status)
        assertEquals(DemoAccounts.DEMO_USERNAME, restored.state.value.currentUser.username)
        assertEquals(DemoAccounts.DEMO_DISPLAY_NAME, restored.state.value.currentUser.displayName)
    }

    @Test fun `registration uses normalized identity and password spaces remain significant`() = runBlocking<Unit> {
        val accounts = DemoAccounts()
        val password = "  MyPassword1!  "
        val created = accounts.register("  ANNA_1  ", "  Анна  ", password)
        assertEquals("anna_1", created.state.value.currentUser.username)
        assertEquals("Анна", created.state.value.currentUser.displayName)
        assertSame(created, accounts.authenticate("ANNA_1", password))
        expectError("UNAUTHORIZED") { accounts.authenticate("anna_1", password.trim()) }
        expectError("UNAUTHORIZED") { accounts.authenticate("anna_1", password.lowercase()) }
        assertNotSame(created, accounts.authenticate(DemoAccounts.DEMO_USERNAME, DemoAccounts.DEMO_PASSWORD))
    }

    @Test fun `duplicate normalized names reject without replacing existing account`() = runBlocking<Unit> {
        val accounts = DemoAccounts()
        val original = accounts.register("anna", "Анна", "OriginalPassword1!")
        val error = expectError("USERNAME_TAKEN") { accounts.register(" ANNA ", "Иван", "OtherPassword1!") }
        assertEquals(setOf("username"), error.fieldErrors.keys)
        expectError("USERNAME_TAKEN") { accounts.register("DEMO_USER", "Иван", "OtherPassword1!") }
        assertSame(original, accounts.authenticate("anna", "OriginalPassword1!"))
        expectError("UNAUTHORIZED") { accounts.authenticate("anna", "OtherPassword1!") }
    }

    @Test fun `unknown user and incorrect password have the same generic error`() = runBlocking<Unit> {
        val accounts = DemoAccounts()
        val unknown = expectError("UNAUTHORIZED") { accounts.authenticate("missing_user", "WrongPassword1!") }
        val wrong = expectError("UNAUTHORIZED") { accounts.authenticate(DemoAccounts.DEMO_USERNAME, "WrongPassword1!") }
        assertEquals(unknown.message, wrong.message)
        assertTrue(unknown.fieldErrors.isEmpty())
        assertTrue(wrong.fieldErrors.isEmpty())
        expectError("VALIDATION_ERROR") { accounts.register("a", "Я", "short") }
        expectError("VALIDATION_ERROR") { accounts.authenticate("a", "short") }
    }

    @Test fun `concurrent registration creates only one account and a new registry forgets registrations`() = runBlocking<Unit> {
        val accounts = DemoAccounts()
        val attempts = (1..4).map {
            async { runCatching { accounts.register("same_user", "Анна", "SamePassword1!") } }
        }.awaitAll()
        assertEquals(1, attempts.count { it.isSuccess })
        assertEquals(3, attempts.count { (it.exceptionOrNull() as? RepositoryException)?.code == "USERNAME_TAKEN" })
        val registered = attempts.single { it.isSuccess }.getOrThrow()
        assertSame(registered, accounts.authenticate("same_user", "SamePassword1!"))
        expectError("UNAUTHORIZED") { DemoAccounts().authenticate("same_user", "SamePassword1!") }
    }

    private suspend fun expectError(code: String, action: suspend () -> Any?): RepositoryException {
        val error = try { action(); fail("Expected $code") } catch (error: RepositoryException) { error }
        assertEquals(code, error.code)
        assertTrue(error.message.isNotBlank())
        return error
    }
}
