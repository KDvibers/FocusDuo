package dev.focusduo.presentation

import dev.focusduo.data.DemoAccounts
import dev.focusduo.data.DesktopPreferences
import dev.focusduo.data.DesktopPreferencesStore
import dev.focusduo.data.FakeRepository
import dev.focusduo.domain.CompletionReason
import dev.focusduo.domain.RoomStatus
import dev.focusduo.domain.RoundKind
import dev.focusduo.domain.RoundStatus
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.*

class DemoSessionControllerTest {
    @TempDir lateinit var directory: Path
    private val clock = MutableClock(Instant.parse("2026-10-10T12:00:00Z"))

    @Test fun `logout preserves paused room and login restores account perspective`() = runBlocking<Unit> {
        val controller = controller()
        controller.initialize()
        login(controller)
        val repository = assertNotNull(controller.state.value.repository)
        val account = repository.state.value.currentUser
        repository.createRoom()
        repository.simulatePartnerJoin()
        val round = repository.startRound(RoundKind.FOCUS).round!!
        clock.advanceSeconds(37)
        val paused = repository.pauseRound(round.id)
        repository.switchPerspective()
        assertNotEquals(account.id, repository.state.value.currentUser.id)
        controller.logout()
        assertNull(controller.state.value.repository)
        clock.advanceSeconds(600)
        login(controller)
        assertSame(repository, controller.state.value.repository)
        assertEquals(account, repository.state.value.currentUser)
        assertEquals(RoomStatus.OPEN, repository.state.value.room!!.status)
        assertEquals(paused.round, repository.state.value.room!!.round)
        assertEquals(RoundStatus.PAUSED, repository.state.value.room!!.round!!.status)
        assertTrue(repository.state.value.history.isEmpty())
    }

    @Test fun `running round continues while logged out and expiry restores exactly one result`() = runBlocking<Unit> {
        val controller = controller()
        controller.initialize()
        login(controller)
        val repository = assertNotNull(controller.state.value.repository)
        repository.createRoom()
        repository.simulatePartnerJoin()
        val round = repository.startRound(RoundKind.FOCUS).round!!
        controller.logout()
        clock.advanceSeconds(123)
        login(controller)
        val running = repository.state.value.room!!.round!!
        assertEquals(RoundStatus.RUNNING, running.status)
        assertEquals(round.remainingMs - 123_000, running.remainingMs)
        assertEquals(123_000L, running.activeElapsedMs)
        controller.logout()
        clock.advanceSeconds(1500)
        login(controller)
        val completed = repository.state.value.room!!.round!!
        assertEquals(RoundStatus.COMPLETED, completed.status)
        assertEquals(CompletionReason.ELAPSED, completed.completionReason)
        assertEquals(round.endsAt, completed.endedAt)
        assertEquals(1, repository.state.value.history.size)
        controller.logout()
        login(controller)
        assertEquals(1, repository.state.value.history.size)
    }

    @Test fun `changing server clears visible session isolates accounts and preserves the original room`() = runBlocking<Unit> {
        val controller = controller()
        controller.initialize()
        login(controller)
        val original = assertNotNull(controller.state.value.repository)
        val room = original.createRoom()
        controller.savePreferences(DesktopPreferences("http://localhost:8091", "demo_user"))
        assertNull(controller.state.value.repository)
        assertEquals(RoomStatus.OPEN, original.state.value.room!!.status)
        login(controller)
        val otherServer = assertNotNull(controller.state.value.repository)
        assertNotSame(original, otherServer)
        assertNull(otherServer.state.value.room)
        assertTrue(otherServer.state.value.history.isEmpty())
        controller.savePreferences(DesktopPreferences("${DesktopPreferences.DEFAULT_BASE_URL}/", "demo_user"))
        assertNull(controller.state.value.repository)
        login(controller)
        assertSame(original, controller.state.value.repository)
        assertEquals(room, original.state.value.room)
    }

    @Test fun `invalid settings preserve active session and username-only change keeps it open`() = runBlocking<Unit> {
        val controller = controller()
        controller.initialize()
        login(controller)
        val repository = assertNotNull(controller.state.value.repository)
        val before = controller.state.value.preferences
        controller.savePreferences(DesktopPreferences("bad-address", "anna"))
        assertSame(repository, controller.state.value.repository)
        assertEquals(before, controller.state.value.preferences)
        assertEquals("VALIDATION_ERROR", controller.state.value.settingsError!!.code)
        assertTrue(controller.state.value.settingsError!!.fieldErrors.containsKey("baseUrl"))
        controller.savePreferences(before.copy(rememberedUsername = " DEMO_USER "))
        assertSame(repository, controller.state.value.repository)
        assertEquals("demo_user", controller.state.value.preferences.rememberedUsername)
        assertNull(controller.state.value.settingsError)
    }

    @Test fun `invalid credentials never open a repository and a successful login clears error`() = runBlocking<Unit> {
        val controller = controller()
        controller.initialize()
        controller.login("missing_user", "WrongPassword1!")
        assertNull(controller.state.value.repository)
        assertEquals("UNAUTHORIZED", controller.state.value.authError!!.code)
        assertFalse(controller.state.value.busy)
        controller.register("anna", "Анна", "OriginalPassword1!")
        val registered = assertNotNull(controller.state.value.repository)
        assertNull(controller.state.value.authError)
        controller.logout()
        controller.login("anna", "IncorrectPassword1!")
        assertNull(controller.state.value.repository)
        assertEquals("UNAUTHORIZED", controller.state.value.authError!!.code)
        controller.login(" ANNA ", "OriginalPassword1!")
        assertSame(registered, controller.state.value.repository)
        assertNull(controller.state.value.authError)
    }

    @Test fun `new process controller loads preferences but requires login and forgets local accounts`() = runBlocking<Unit> {
        val previous = controller()
        previous.initialize()
        previous.savePreferences(DesktopPreferences(rememberedUsername = "anna"))
        previous.register("anna", "Анна", "OriginalPassword1!")
        assertNotNull(previous.state.value.repository)
        val next = controller()
        next.initialize()
        assertFalse(next.state.value.loading)
        assertEquals("anna", next.state.value.preferences.rememberedUsername)
        assertNull(next.state.value.repository)
        next.login("anna", "OriginalPassword1!")
        assertNull(next.state.value.repository)
        assertEquals("UNAUTHORIZED", next.state.value.authError!!.code)
        login(next)
        assertNull(next.state.value.repository!!.state.value.room)
    }

    private fun controller() = DemoSessionController(
        preferencesStore = DesktopPreferencesStore(directory.resolve("preferences.properties"), emptyMap()),
        accountsFactory = {
            DemoAccounts { username, displayName ->
                FakeRepository(initialDisplayName = displayName, clock = clock, initialUsername = username)
            }
        },
    )

    private suspend fun login(controller: DemoSessionController) = controller.login(DemoAccounts.DEMO_USERNAME, DemoAccounts.DEMO_PASSWORD)

    private class MutableClock(@Volatile private var current: Instant) : Clock() {
        fun advanceSeconds(seconds: Long) { current = current.plusSeconds(seconds) }
        override fun instant(): Instant = current
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
    }
}
