package dev.focusduo.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import java.util.UUID

/** Excluded from normal tests. Run backendAuthSmoke explicitly against a development backend. */
class LiveBackendAuthTest {
    @Test
    fun registerLoginRestoreAndLogout() = runBlocking<Unit> {
        val address = System.getProperty("focusduo.smoke.baseUrl")
        val username = "desktop_" + UUID.randomUUID().toString().replace("-", "").take(20)
        val password = UUID.randomUUID().toString() + "Я!"
        val registration = RealRepository.register(address, username, "Проверка Kotlin", password)
        val userId = registration.state.value.currentUser.id
        try {
            registration.refreshMe()
            registration.refreshCurrentRoom()
            assertEquals(username, registration.state.value.currentUser.username)
            assertEquals("Проверка Kotlin", registration.state.value.currentUser.displayName)
            assertNull(registration.state.value.room)
            registration.logout()
        } finally { registration.close() }

        val login = RealRepository.login(address, username, password)
        try {
            login.refreshMe()
            login.refreshCurrentRoom()
            assertEquals(userId, login.state.value.currentUser.id)
            assertNull(login.state.value.room)
            login.logout()
        } finally { login.close() }
    }
}
