package dev.focusduo.data

import dev.focusduo.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.*

class FakeRoundTest {
    private val start = Instant.parse("2026-10-10T12:00:00Z")

    @Test fun `focus requires two participants and only owner controls rounds`() = runBlocking {
        val repository = FakeRepository(clock = MutableClock(start))
        repository.createRoom(300, 60)
        expectError("INVALID_STATE") { repository.startRound(RoundKind.FOCUS) }
        expectError("INVALID_STATE") { repository.startRound(RoundKind.BREAK) }
        repository.simulatePartnerJoin()
        repository.switchPerspective()
        expectError("FORBIDDEN") { repository.startRound(RoundKind.FOCUS) }
        repository.switchPerspective()
        val room = repository.startRound(RoundKind.FOCUS)
        val round = room.round!!
        assertEquals(RoundStatus.RUNNING, round.status)
        assertEquals(start, round.startedAt)
        assertEquals(start.plusSeconds(300), round.endsAt)
        assertEquals(300000L, round.remainingMs)
        assertEquals(0L, round.activeElapsedMs)
        assertNull(round.endedAt)
        expectError("INVALID_STATE") { repository.startRound(RoundKind.FOCUS) }
        expectError("INVALID_STATE") { repository.updateSettings(600, 120) }
        expectError("INVALID_STATE") { repository.resumeRound(round.id) }
        repository.switchPerspective()
        expectError("FORBIDDEN") { repository.pauseRound(round.id) }
        expectError("FORBIDDEN") { repository.resumeRound(round.id) }
        expectError("FORBIDDEN") { repository.finishRound(round.id) }
        assertTrue(repository.state.value.history.isEmpty())
    }

    @Test fun `pause freezes active time resume preserves start and ticks do not change revision`() = runBlocking {
        val clock = MutableClock(start)
        val repository = ready(clock)
        val runningRoom = repository.startRound(RoundKind.FOCUS)
        val id = runningRoom.round!!.id
        clock.advanceMillis(12345)
        repository.refreshDemoTime()
        val tick = repository.state.value.room!!
        assertEquals(runningRoom.revision, tick.revision)
        assertEquals(287655L, tick.round!!.remainingMs)
        assertEquals(12345L, tick.round.activeElapsedMs)
        val pausedRoom = repository.pauseRound(id)
        val paused = pausedRoom.round!!
        assertEquals(RoundStatus.PAUSED, paused.status)
        assertNull(paused.endsAt)
        expectError("INVALID_STATE") { repository.pauseRound(id) }
        expectError("INVALID_STATE") { repository.updateSettings(600, 120) }
        clock.advanceMillis(90000)
        repository.refreshDemoTime()
        assertEquals(paused, repository.state.value.room!!.round)
        val resumed = repository.resumeRound(id).round!!
        assertEquals(start, resumed.startedAt)
        assertEquals(clock.instant().plusMillis(287655), resumed.endsAt)
        assertEquals(paused.activeElapsedMs, resumed.activeElapsedMs)
        clock.advanceMillis(10000)
        val complete = repository.finishRound(id).round!!
        assertEquals(RoundStatus.COMPLETED, complete.status)
        assertEquals(CompletionReason.MANUAL, complete.completionReason)
        assertEquals(22345L, complete.activeElapsedMs)
        assertEquals(0L, complete.remainingMs)
        assertNull(complete.endsAt)
        assertEquals(clock.instant(), complete.endedAt)
        assertEquals(22345L, repository.state.value.history.single().activeElapsedMs)
        expectError("INVALID_STATE") { repository.finishRound(id) }
        expectError("INVALID_STATE") { repository.pauseRound(id) }
        expectError("INVALID_STATE") { repository.resumeRound(id) }
    }

    @Test fun `finish paused round retains real elapsed time after long pause`() = runBlocking {
        val clock = MutableClock(start)
        val repository = ready(clock)
        val id = repository.startRound(RoundKind.FOCUS).round!!.id
        clock.advanceMillis(17500)
        repository.pauseRound(id)
        clock.advanceMillis(3600000)
        val complete = repository.finishRound(id).round!!
        assertEquals(17500L, complete.activeElapsedMs)
        assertEquals(0L, complete.remainingMs)
        assertEquals(clock.instant(), complete.endedAt)
        assertEquals(CompletionReason.MANUAL, complete.completionReason)
    }

    @Test fun `late auto completion uses exact deadline and persists before rejected command`() = runBlocking {
        val clock = MutableClock(start)
        val repository = ready(clock)
        val running = repository.startRound(RoundKind.FOCUS)
        val id = running.round!!.id
        clock.advanceMillis(420000)
        expectError("INVALID_STATE") { repository.pauseRound(id) }
        val room = repository.state.value.room!!
        val round = room.round!!
        assertEquals(RoundStatus.COMPLETED, round.status)
        assertEquals(CompletionReason.ELAPSED, round.completionReason)
        assertEquals(start.plusSeconds(300), round.endedAt)
        assertEquals(300000L, round.activeElapsedMs)
        assertEquals(0L, round.remainingMs)
        assertNull(round.endsAt)
        assertEquals(running.revision + 1, room.revision)
        val history = repository.state.value.history.single()
        assertEquals(round.endedAt, history.endedAt)
        repository.refreshDemoTime()
        expectError("INVALID_STATE") { repository.finishRound(id) }
        assertEquals(listOf(history), repository.state.value.history)
        assertEquals(room.revision, repository.state.value.room!!.revision)
    }

    @Test fun `break must follow completed focus and has independent round and history`() = runBlocking {
        val clock = MutableClock(start)
        val repository = ready(clock)
        expectError("INVALID_STATE") { repository.startRound(RoundKind.BREAK) }
        val focus = repository.startRound(RoundKind.FOCUS).round!!
        expectError("INVALID_STATE") { repository.startRound(RoundKind.BREAK) }
        clock.advanceMillis(10000)
        repository.finishRound(focus.id)
        val rest = repository.startRound(RoundKind.BREAK).round!!
        assertNotEquals(focus.id, rest.id)
        assertEquals(RoundKind.BREAK, rest.kind)
        assertEquals(60, rest.durationSeconds)
        assertEquals(60000L, rest.remainingMs)
        expectError("ROUND_NOT_CURRENT") { repository.pauseRound(focus.id) }
        expectError("ROUND_NOT_CURRENT") { repository.resumeRound(focus.id) }
        expectError("ROUND_NOT_CURRENT") { repository.finishRound(focus.id) }
        clock.advanceMillis(60000)
        repository.refreshDemoTime()
        assertEquals(2, repository.state.value.history.size)
        assertEquals(RoundKind.BREAK, repository.state.value.history.first().kind)
        assertEquals(CompletionReason.ELAPSED, repository.state.value.history.first().completionReason)
        expectError("INVALID_STATE") { repository.startRound(RoundKind.BREAK) }
        val next = repository.startRound(RoundKind.FOCUS).round!!
        assertNotEquals(rest.id, next.id)
        assertEquals(RoundKind.FOCUS, next.kind)
    }

    @Test fun `expiry freezes tasks before a later valid edit command`() = runBlocking {
        val clock = MutableClock(start)
        val repository = ready(clock)
        val task = repository.addTask("Задача к окончанию раунда").participants.first().tasks.single()
        val running = repository.startRound(RoundKind.FOCUS)
        clock.advanceMillis(301000)
        val edited = repository.updateTask(task.id, title = "Задача после раунда", completed = true)
        assertEquals(running.revision + 2, edited.revision)
        assertEquals(RoundStatus.COMPLETED, edited.round!!.status)
        val historyTask = repository.state.value.history.single().participants.first().tasks.single()
        assertEquals("Задача к окончанию раунда", historyTask.title)
        assertFalse(historyTask.completed)
        assertEquals("Задача после раунда", edited.participants.first().tasks.single().title)
        assertTrue(edited.participants.first().tasks.single().completed)
    }

    @Test fun `either participant can close running or paused round with cancellation`() = runBlocking {
        for (pause in listOf(false, true)) {
            val clock = MutableClock(start)
            val repository = ready(clock)
            val id = repository.startRound(RoundKind.FOCUS).round!!.id
            clock.advanceMillis(18000)
            if (pause) {
                repository.pauseRound(id)
                clock.advanceMillis(60000)
            }
            repository.switchPerspective()
            val room = repository.closeRoom()
            assertEquals(RoomStatus.CLOSED, room.status)
            assertEquals(2, room.participants.size)
            val round = room.round!!
            assertEquals(RoundStatus.CANCELLED, round.status)
            assertEquals(CompletionReason.ROOM_CLOSED, round.completionReason)
            assertEquals(18000L, round.activeElapsedMs)
            assertEquals(0L, round.remainingMs)
            assertNull(round.endsAt)
            assertEquals(clock.instant(), round.endedAt)
            assertEquals(round.id, repository.state.value.history.single().roundId)
            assertEquals(RoundStatus.CANCELLED, repository.state.value.history.single().status)
            expectError("ROOM_CLOSED") { repository.closeRoom() }
            expectError("ROOM_CLOSED") { repository.startRound(RoundKind.BREAK) }
            expectError("ROOM_CLOSED") { repository.addTask("Новая задача") }
            expectError("ROOM_CLOSED") { repository.updateGoal("Новая цель") }
            expectError("ROOM_CLOSED") { repository.updateSettings(600, 120) }
            expectError("ROOM_CLOSED") { repository.simulatePartnerJoin() }
            expectError("ROOM_CLOSED") { repository.updateDemoProfile("Новое имя") }
            expectError("ROOM_CLOSED") { repository.advanceDemoTime(1) }
            expectError("ROOM_CLOSED") { repository.joinRoom(room.code) }
            repository.switchPerspective()
            assertEquals(room.ownerId, repository.state.value.currentUser.id)
            assertEquals(room, repository.state.value.room)
        }
    }

    @Test fun `closing expired completed or empty room never rewrites history`() = runBlocking {
        val clock = MutableClock(start)
        val repository = ready(clock)
        repository.startRound(RoundKind.FOCUS)
        clock.advanceMillis(360000)
        val room = repository.closeRoom()
        assertEquals(RoomStatus.CLOSED, room.status)
        assertEquals(RoundStatus.COMPLETED, room.round!!.status)
        assertEquals(CompletionReason.ELAPSED, room.round.completionReason)
        assertEquals(start.plusSeconds(300), room.round.endedAt)
        val frozenHistory = repository.state.value.history
        val newRoom = repository.createRoom()
        assertNotEquals(room.roomId, newRoom.roomId)
        assertEquals(frozenHistory, repository.state.value.history)
        assertNull(repository.closeRoom().round)
        assertEquals(frozenHistory, repository.state.value.history)
        repository.joinRoom(FakeRepository.SAMPLE_ROOM_CODE)
        assertEquals(frozenHistory, repository.state.value.history)
        repository.resetDemo()
        assertTrue(repository.state.value.history.isEmpty())
        assertNull(repository.state.value.room)
    }

    @Test fun `history freezes names goals tasks and counts across edits and later rounds`(): Unit = runBlocking {
        val clock = MutableClock(start)
        val repository = ready(clock)
        repository.updateGoal("Исходная цель")
        val task = repository.addTask("Исходная задача").participants.first().tasks.single()
        repository.updateTask(task.id, completed = true)
        val focus = repository.startRound(RoundKind.FOCUS).round!!
        clock.advanceMillis(15000)
        repository.finishRound(focus.id)
        val history = repository.state.value.history.single()
        val participant = history.participants.first()
        assertEquals("Ты", participant.displayName)
        assertEquals("Исходная цель", participant.goal)
        assertEquals("Исходная задача", participant.tasks.single().title)
        assertEquals(1, participant.totalTaskCount)
        assertEquals(1, participant.completedTaskCount)
        repository.updateDemoProfile("Изменённое имя")
        repository.updateGoal("Изменённая цель")
        repository.updateTask(task.id, title = "Изменённая задача", completed = false)
        repository.deleteTask(task.id)
        repository.addTask("Поздняя задача")
        assertEquals(history, repository.state.value.history.single())
        val next = repository.startRound(RoundKind.FOCUS).round!!
        assertEquals("Изменённая цель", repository.state.value.room!!.participants.first().goal)
        clock.advanceMillis(30000)
        repository.finishRound(next.id)
        assertEquals(next.id, repository.state.value.history.first().roundId)
        assertEquals(history, repository.state.value.history.last())
        assertFailsWith<UnsupportedOperationException> { (history.participants as MutableList<*>).clear() }
        assertFailsWith<UnsupportedOperationException> { (participant.tasks as MutableList<*>).clear() }
        assertFailsWith<UnsupportedOperationException> { (repository.state.value.history as MutableList<*>).clear() }
        repository.closeRoom()
        assertEquals(2, repository.state.value.history.size)
        assertEquals(history, repository.state.value.history.last())
    }

    @Test fun `expiry is recorded once under concurrent refresh and invalid finish commands`() = runBlocking {
        val clock = MutableClock(start)
        val repository = ready(clock)
        val room = repository.startRound(RoundKind.FOCUS)
        val id = room.round!!.id
        clock.advanceMillis(600000)
        val outcomes = (1..40).map { number ->
            async(Dispatchers.Default) {
                runCatching { if (number % 2 == 0) repository.refreshDemoTime() else repository.finishRound(id) }
            }
        }.awaitAll()
        assertEquals(20, outcomes.count { it.isSuccess })
        assertEquals(20, outcomes.count { (it.exceptionOrNull() as? RepositoryException)?.code == "INVALID_STATE" })
        assertEquals(1, repository.state.value.history.size)
        assertEquals(room.revision + 1, repository.state.value.room!!.revision)
        assertEquals(CompletionReason.ELAPSED, repository.state.value.history.single().completionReason)
    }

    @Test fun `concurrent starts create only one active round and finish only one history entry`() = runBlocking {
        val repository = ready(MutableClock(start))
        val starts = (1..20).map { async(Dispatchers.Default) { runCatching { repository.startRound(RoundKind.FOCUS) } } }.awaitAll()
        assertEquals(1, starts.count { it.isSuccess })
        assertEquals(19, starts.count { (it.exceptionOrNull() as? RepositoryException)?.code == "INVALID_STATE" })
        val room = repository.state.value.room!!
        val id = room.round!!.id
        val finishes = (1..20).map { async(Dispatchers.Default) { runCatching { repository.finishRound(id) } } }.awaitAll()
        assertEquals(1, finishes.count { it.isSuccess })
        assertEquals(19, finishes.count { (it.exceptionOrNull() as? RepositoryException)?.code == "INVALID_STATE" })
        assertEquals(room.revision + 1, repository.state.value.room!!.revision)
        assertEquals(1, repository.state.value.history.size)
    }

    @Test fun `explicit demo time advancement uses server rules and pauses remain frozen`() = runBlocking {
        val repository = ready(MutableClock(start))
        val id = repository.startRound(RoundKind.FOCUS).round!!.id
        repository.advanceDemoTime(30)
        assertEquals(270000L, repository.state.value.room!!.round!!.remainingMs)
        repository.pauseRound(id)
        repository.advanceDemoTime(3600)
        assertEquals(270000L, repository.state.value.room!!.round!!.remainingMs)
        assertTrue(repository.state.value.history.isEmpty())
        expectError("VALIDATION_ERROR") { repository.advanceDemoTime(0) }
        expectError("VALIDATION_ERROR") { repository.advanceDemoTime(-1) }
        repository.resumeRound(id)
        repository.advanceDemoTime(600)
        val history = repository.state.value.history.single()
        assertEquals(300000L, history.activeElapsedMs)
        assertEquals(start.plusSeconds(3900), history.endedAt)
    }

    @Test fun `new rooms after partner closes retain two distinct identities`() = runBlocking {
        val repository = ready(MutableClock(start))
        repository.switchPerspective()
        val partnerId = repository.state.value.currentUser.id
        repository.closeRoom()
        val sample = repository.joinRoom(FakeRepository.SAMPLE_ROOM_CODE)
        assertEquals(2, sample.participants.map { it.userId }.distinct().size)
        assertNotEquals(partnerId, sample.ownerId)
        assertEquals(partnerId, sample.participants.last().userId)
        repository.closeRoom()
        val created = repository.createRoom()
        assertEquals(partnerId, created.ownerId)
        val shared = repository.simulatePartnerJoin()
        assertEquals(2, shared.participants.map { it.userId }.distinct().size)
        repository.startRound(RoundKind.FOCUS)
        assertEquals(RoundStatus.RUNNING, repository.state.value.room!!.round!!.status)
    }

    @Test fun `history keeps newest fifty results without dropping current room tasks`() = runBlocking {
        val clock = MutableClock(start)
        val repository = ready(clock)
        repository.addTask("Сохраняемая задача")
        val ids = mutableListOf<String>()
        repeat(55) {
            val round = repository.startRound(RoundKind.FOCUS).round!!
            ids.add(round.id)
            clock.advanceMillis(1000)
            repository.finishRound(round.id)
        }
        assertEquals(50, repository.state.value.history.size)
        assertEquals(ids.takeLast(50).reversed(), repository.state.value.history.map { it.roundId })
        assertEquals("Сохраняемая задача", repository.state.value.room!!.participants.first().tasks.single().title)
        repository.closeRoom()
        assertEquals(50, repository.state.value.history.size)
        assertEquals(ids.last(), repository.state.value.history.first().roundId)
    }

    private suspend fun ready(clock: Clock): FakeRepository = FakeRepository(clock = clock).also {
        it.createRoom(300, 60)
        it.simulatePartnerJoin()
    }

    private suspend fun expectError(code: String, action: suspend () -> Any?) {
        val error = try {
            action()
            fail("Expected RepositoryException($code)")
        } catch (error: RepositoryException) { error }
        assertEquals(code, error.code)
        assertTrue(error.message.isNotBlank())
    }

    private class MutableClock(private var current: Instant) : Clock() {
        override fun instant(): Instant = current
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        fun advanceMillis(milliseconds: Long) { current = current.plusMillis(milliseconds) }
    }
}
