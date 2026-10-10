package dev.focusduo.data

import dev.focusduo.domain.RepositoryException
import dev.focusduo.domain.RoomStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.*

class FakeRepositoryTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-10T12:00:00Z"), ZoneOffset.UTC)

    @Test fun `unicode characters use the same limits in auth room and profile`() = runBlocking<Unit> {
        val character = String(Character.toChars(0x1F33F))
        val repository = FakeRepository(character.repeat(40), clock)
        repository.createRoom()
        repository.updateGoal(character.repeat(240))
        repository.addTask(character.repeat(160))
        assertEquals(character.repeat(40), repository.state.value.currentUser.displayName)
        assertEquals(character.repeat(240), repository.state.value.room!!.participants.single().goal)
        assertEquals(character.repeat(160), repository.state.value.room!!.participants.single().tasks.single().title)
        expectError("VALIDATION_ERROR") { repository.updateDemoProfile(character.repeat(41)) }
        expectError("VALIDATION_ERROR") { repository.updateGoal(character.repeat(241)) }
        expectError("VALIDATION_ERROR") { repository.addTask(character.repeat(161)) }
    }

    @Test fun `fresh room is empty and owned by the current user`() = runBlocking<Unit> {
        val repository = FakeRepository("  Анна  ", clock)
        assertNull(repository.state.value.room)
        val room = repository.createRoom()
        assertEquals("Анна", repository.state.value.currentUser.displayName)
        assertEquals(repository.state.value.currentUser.id, room.ownerId)
        assertEquals(RoomStatus.OPEN, room.status)
        assertEquals(1, room.participants.size)
        assertEquals(1L, room.revision)
        assertEquals(1500, room.focusDurationSeconds)
        assertEquals(300, room.breakDurationSeconds)
        assertEquals(clock.instant(), room.serverNow)
        assertEquals("", room.participants.single().goal)
        assertTrue(room.participants.single().tasks.isEmpty())
        assertNull(room.round)
        UUID.fromString(room.roomId)
        assertNotEquals(FakeRepository.SAMPLE_ROOM_CODE, room.code)
    }

    @Test fun `join normalizes opaque code and repeat join changes nothing`() = runBlocking<Unit> {
        val repository = FakeRepository(clock = clock)
        val room = repository.joinRoom("  ${FakeRepository.SAMPLE_ROOM_CODE.lowercase()}  ")
        assertEquals(2, room.participants.size)
        assertNotEquals(repository.state.value.currentUser.id, room.ownerId)
        assertTrue(room.participants.all { it.tasks.isNotEmpty() && it.goal.isNotEmpty() })
        assertSame(room, repository.joinRoom(room.code))
        assertEquals(room, repository.state.value.room)
    }

    @Test fun `room admission errors leave state intact`() = runBlocking<Unit> {
        val repository = FakeRepository(clock = clock)
        expectError("VALIDATION_ERROR") { repository.joinRoom("   ") }
        expectError("NOT_FOUND") { repository.joinRoom("any-opaque-code") }
        assertNull(repository.state.value.room)
        val room = repository.createRoom()
        expectError("ALREADY_IN_ROOM") { repository.createRoom() }
        expectError("ALREADY_IN_ROOM") { repository.joinRoom(FakeRepository.SAMPLE_ROOM_CODE) }
        assertSame(room, repository.state.value.room)
        assertSame(room, repository.joinRoom(room.code.lowercase()))
    }

    @Test fun `partner simulation respects capacity and perspective preserves owner`() = runBlocking<Unit> {
        val repository = FakeRepository(clock = clock)
        val originalUser = repository.state.value.currentUser
        val created = repository.createRoom()
        expectError("INVALID_STATE") { repository.switchPerspective() }
        val shared = repository.simulatePartnerJoin()
        assertEquals(2L, shared.revision)
        assertEquals(2, shared.participants.size)
        expectError("ROOM_FULL") { repository.simulatePartnerJoin() }
        assertSame(shared, repository.state.value.room)
        repository.switchPerspective()
        assertNotEquals(originalUser.id, repository.state.value.currentUser.id)
        assertEquals(created.ownerId, repository.state.value.room!!.ownerId)
        assertSame(shared, repository.state.value.room)
        repository.switchPerspective()
        assertEquals(originalUser, repository.state.value.currentUser)
    }

    @Test fun `task CRUD trims title keeps completion and changes only own participant`() = runBlocking<Unit> {
        val repository = FakeRepository(clock = clock)
        repository.createRoom()
        val initial = repository.simulatePartnerJoin()
        val friend = initial.participants.last()
        val added = repository.addTask("  Подготовить экран  ")
        val task = added.participants.first().tasks.single()
        assertEquals("Подготовить экран", task.title)
        assertFalse(task.completed)
        UUID.fromString(task.id)
        repository.updateTask(task.id, completed = true)
        val renamed = repository.updateTask(task.id, title = "  Проверить интерфейс ")
        assertEquals("Проверить интерфейс", renamed.participants.first().tasks.single().title)
        assertTrue(renamed.participants.first().tasks.single().completed)
        assertEquals(friend, renamed.participants.last())
        assertEquals(initial.revision + 3, renamed.revision)
        val removed = repository.deleteTask(task.id)
        assertTrue(removed.participants.first().tasks.isEmpty())
        assertEquals(friend, removed.participants.last())
    }

    @Test fun `even owner cannot change or delete friends tasks`() = runBlocking<Unit> {
        val repository = FakeRepository(clock = clock)
        repository.createRoom()
        val room = repository.simulatePartnerJoin()
        val foreignTask = room.participants.last().tasks.first()
        expectError("FORBIDDEN") { repository.updateTask(foreignTask.id, completed = false) }
        expectError("FORBIDDEN") { repository.deleteTask(foreignTask.id) }
        assertSame(room, repository.state.value.room)
        repository.switchPerspective()
        val changed = repository.updateTask(foreignTask.id, completed = false)
        assertFalse(changed.participants.last().tasks.first().completed)
        assertEquals(room.participants.first(), changed.participants.first())
    }

    @Test fun `invalid task edits are atomic and field errors are useful`() = runBlocking<Unit> {
        val repository = FakeRepository(clock = clock)
        repository.createRoom()
        val room = repository.addTask("я".repeat(160))
        val task = room.participants.single().tasks.single()
        for (title in listOf(" ", "я".repeat(161))) {
            val error = expectError("VALIDATION_ERROR") { repository.addTask(title) }
            assertTrue(error.fieldErrors.containsKey("title"))
            expectError("VALIDATION_ERROR") { repository.updateTask(task.id, title, completed = true) }
        }
        expectError("VALIDATION_ERROR") { repository.updateTask(task.id) }
        expectError("NOT_FOUND") { repository.updateTask("missing", completed = true) }
        expectError("NOT_FOUND") { repository.deleteTask("missing") }
        assertSame(room, repository.state.value.room)
        assertFalse(room.participants.single().tasks.single().completed)
    }

    @Test fun `goals allow empty and preserve text up to 240 characters`() = runBlocking<Unit> {
        val repository = FakeRepository(clock = clock)
        repository.createRoom()
        repository.simulatePartnerJoin()
        val goal = " " + "я".repeat(238) + " "
        val room = repository.updateGoal(goal)
        assertEquals(goal, room.participants.first().goal)
        assertEquals("собрать интерфейс", room.participants.last().goal)
        val error = expectError("VALIDATION_ERROR") { repository.updateGoal("я".repeat(241)) }
        assertTrue(error.fieldErrors.containsKey("goal"))
        assertSame(room, repository.state.value.room)
        assertEquals("", repository.updateGoal("").participants.first().goal)
    }

    @Test fun `task capacity stays 20 even with concurrent commands and recovers after delete`() = runBlocking<Unit> {
        val repository = FakeRepository(clock = clock)
        repository.createRoom()
        val results = (1..30).map { number ->
            async(Dispatchers.Default) { runCatching { repository.addTask("Задача $number") } }
        }.awaitAll()
        assertEquals(20, results.count { it.isSuccess })
        assertEquals(10, results.count { (it.exceptionOrNull() as? RepositoryException)?.code == "VALIDATION_ERROR" })
        val room = repository.state.value.room!!
        assertEquals(21L, room.revision)
        assertEquals(20, room.participants.single().tasks.map { it.id }.distinct().size)
        repository.deleteTask(room.participants.single().tasks.first().id)
        assertEquals(20, repository.addTask("Новая задача").participants.single().tasks.size)
    }

    @Test fun `settings require owner and whole minute bounds`() = runBlocking<Unit> {
        val repository = FakeRepository(clock = clock)
        expectError("VALIDATION_ERROR") { repository.createRoom(299, 300) }
        assertNull(repository.state.value.room)
        repository.createRoom(300, 60)
        val room = repository.updateSettings(10800, 3600)
        assertEquals(10800, room.focusDurationSeconds)
        assertEquals(3600, room.breakDurationSeconds)
        listOf(299 to 60, 10860 to 60, 301 to 60, 300 to 59, 300 to 3660, 300 to 61).forEach { (focus, pause) ->
            expectError("VALIDATION_ERROR") { repository.updateSettings(focus, pause) }
        }
        assertSame(room, repository.state.value.room)
        repository.simulatePartnerJoin()
        repository.switchPerspective()
        val shared = repository.state.value.room
        expectError("FORBIDDEN") { repository.updateSettings(1500, 300) }
        assertSame(shared, repository.state.value.room)
    }

    @Test fun `snapshots cannot be altered by later changes or mutable list casts`(): Unit = runBlocking<Unit> {
        val repository = FakeRepository(clock = clock)
        val emptyRoom = repository.createRoom()
        val roomWithTask = repository.addTask("Исходная задача")
        val task = roomWithTask.participants.single().tasks.single()
        repository.updateTask(task.id, title = "Новая задача", completed = true)
        repository.updateGoal("Новая цель")
        assertTrue(emptyRoom.participants.single().tasks.isEmpty())
        assertEquals("", roomWithTask.participants.single().goal)
        assertEquals("Исходная задача", roomWithTask.participants.single().tasks.single().title)
        assertFalse(roomWithTask.participants.single().tasks.single().completed)
        assertFailsWith<UnsupportedOperationException> {
            (roomWithTask.participants as MutableList<*>).clear()
        }
        assertFailsWith<UnsupportedOperationException> {
            (roomWithTask.participants.single().tasks as MutableList<*>).clear()
        }
    }

    @Test fun `demo profiles and reset preserve the correct local identity`() = runBlocking<Unit> {
        val repository = FakeRepository(clock = clock)
        repository.updateDemoProfile("  Моё имя  ")
        val user = repository.state.value.currentUser
        val room = repository.createRoom()
        expectError("VALIDATION_ERROR") { repository.updateDemoProfile("А") }
        expectError("VALIDATION_ERROR") { repository.updateDemoProfile("А".repeat(41)) }
        assertSame(room, repository.state.value.room)
        repository.simulatePartnerJoin()
        repository.switchPerspective()
        repository.updateDemoProfile("Имя друга")
        assertEquals("Имя друга", repository.state.value.currentUser.displayName)
        assertEquals("Имя друга", repository.state.value.room!!.participants.last().displayName)
        repository.switchPerspective()
        assertEquals(user, repository.state.value.currentUser)
        repository.switchPerspective()
        repository.resetDemo()
        assertEquals(user, repository.state.value.currentUser)
        assertNull(repository.state.value.room)
        assertEquals("Моё имя", repository.createRoom().participants.single().displayName)
        assertEquals("Друг", repository.simulatePartnerJoin().participants.last().displayName)
    }

    private suspend fun expectError(code: String, action: suspend () -> Any?): RepositoryException {
        val error = try {
            action()
            fail("Expected RepositoryException($code)")
        } catch (error: RepositoryException) {
            error
        }
        assertEquals(code, error.code)
        assertTrue(error.message.isNotBlank())
        return error
    }
}
