package dev.focusduo.domain

import kotlin.test.*

class AuthValidationTest {
    @Test fun `username and display name normalize while password stays exact`() {
        val input = AuthValidation.registration("  A_User_1  ", "  Анна  ", " Password1! ")
        assertEquals("a_user_1", input.username)
        assertEquals("Анна", input.displayName)
        assertEquals(" Password1! ", input.password)
        assertEquals("a_user_1", AuthValidation.login(" A_USER_1 ", input.password).username)
        assertEquals("iii", AuthValidation.normalizeUsername("III"))
    }

    @Test fun `username accepts inclusive bounds and rejects other scripts and punctuation`() {
        listOf("abc", "a".repeat(32), "123", "___").forEach { assertEquals(it, AuthValidation.normalizeUsername(it)) }
        listOf("", "ab", "a".repeat(33), "анна", "a-b", "a.b", "a b", "a@b").forEach { username ->
            val error = assertFailsWith<RepositoryException> { AuthValidation.login(username, "1234567890") }
            assertEquals("VALIDATION_ERROR", error.code)
            assertTrue(error.fieldErrors.containsKey("username"))
        }
    }

    @Test fun `registration reports all invalid fields and display name bounds are after trim`() {
        val error = assertFailsWith<RepositoryException> { AuthValidation.registration("a", " Я ", "short") }
        assertEquals(setOf("username", "displayName", "password"), error.fieldErrors.keys)
        listOf("Яя", "Я".repeat(40)).forEach { display ->
            assertEquals(display, AuthValidation.registration("user", " $display ", "1234567890").displayName)
        }
        assertTrue(AuthValidation.validateRegistration("user", "Я".repeat(41), "1234567890").containsKey("displayName"))
    }

    @Test fun `password bounds preserve whitespace and case and never appear in credential strings`() {
        listOf(" ".repeat(10), "a".repeat(128)).forEach { password ->
            assertEquals(password, AuthValidation.login("user", password).password)
        }
        listOf("a".repeat(9), "a".repeat(129)).forEach { password ->
            assertTrue(AuthValidation.validateLogin("user", password).containsKey("password"))
        }
        val password = " SensitivePassword1! "
        assertFalse(AuthValidation.login("user", password).toString().contains(password))
        assertFalse(AuthValidation.registration("user", "Анна", password).toString().contains(password))
    }

    @Test fun `optional remembered username is normalized and has its own error field`() {
        assertEquals("", AuthValidation.normalizeRememberedUsername("   "))
        assertEquals("saved_user", AuthValidation.normalizeRememberedUsername(" SAVED_USER "))
        val error = assertFailsWith<RepositoryException> { AuthValidation.normalizeRememberedUsername("Я") }
        assertEquals(setOf("rememberedUsername"), error.fieldErrors.keys)
    }

    @Test fun `unicode display name and password bounds count code points like the backend`() {
        val emoji = "\uD83C\uDF3F"
        val valid = AuthValidation.registration("user", emoji.repeat(40), emoji.repeat(128))
        assertEquals(emoji.repeat(40), valid.displayName)
        assertEquals(emoji.repeat(128), valid.password)
        assertTrue(AuthValidation.validateRegistration("user", emoji, "1234567890").containsKey("displayName"))
        assertTrue(AuthValidation.validateRegistration("user", emoji.repeat(41), "1234567890").containsKey("displayName"))
        assertTrue(AuthValidation.validateLogin("user", emoji.repeat(9)).containsKey("password"))
        assertTrue(AuthValidation.validateLogin("user", emoji.repeat(129)).containsKey("password"))
        assertTrue(AuthValidation.validateLogin("user", emoji.repeat(10)).isEmpty())
    }
}
