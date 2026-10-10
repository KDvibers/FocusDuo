package dev.focusduo.data

import dev.focusduo.domain.CompletionReason
import dev.focusduo.domain.RoomSnapshot
import dev.focusduo.domain.RoomStatus
import dev.focusduo.domain.RoundKind
import dev.focusduo.domain.RoundStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Opt-in only: creates two accounts and persistent room/history records on a development server. */
class LiveBackendRoomTest {
    @Test
    fun twoRealRepositoriesUseRestCommandsAndReceiveServerSnapshots() = runBlocking<Unit> {
        val address = System.getProperty("focusduo.smoke.baseUrl")
        val suffix = UUID.randomUUID().toString().replace("-", "").take(20)
        val owner = RealRepository.register(address, "desk_a_$suffix", "Клиент А", UUID.randomUUID().toString())
        try {
            val partner = RealRepository.register(address, "desk_b_$suffix", "Клиент Б", UUID.randomUUID().toString())
            try {
                owner.bootstrap()
                partner.bootstrap()
                val created = owner.createRoom(300, 60)
                awaitReady(owner, created.roomId, created.revision)
                val joined = partner.joinRoom(created.code.lowercase())
                awaitReady(owner, joined.roomId, joined.revision)
                awaitReady(partner, joined.roomId, joined.revision)
                assertEquals(2, owner.state.value.room!!.participants.size)
                val rejoined = partner.joinRoom(created.code)
                assertEquals(2, rejoined.participants.size)
                assertEquals(joined.revision, rejoined.revision, "Repeated membership must not change revision")

                val goal = owner.updateGoal("Проверить настоящий Kotlin-клиент")
                awaitReady(partner, goal.roomId, goal.revision)
                assertEquals("Проверить настоящий Kotlin-клиент", partner.state.value.room!!.participants.first { it.userId == owner.state.value.currentUser.id }.goal)
                val withTask = owner.addTask("Задача для совместной проверки")
                awaitReady(partner, withTask.roomId, withTask.revision)
                val taskId = withTask.participants.first { it.userId == owner.state.value.currentUser.id }.tasks.single().id
                val checked = owner.updateTask(taskId, completed = true)
                awaitReady(partner, checked.roomId, checked.revision)

                val running = owner.startRound(RoundKind.FOCUS)
                val roundId = assertNotNull(running.round).id
                awaitReady(partner, running.roomId, running.revision)
                assertEquals(RoundStatus.RUNNING, partner.state.value.room!!.round!!.status)
                val paused = owner.pauseRound(roundId)
                awaitReady(partner, paused.roomId, paused.revision)
                assertEquals(RoundStatus.PAUSED, partner.state.value.room!!.round!!.status)
                assertEquals(running.round!!.startedAt, paused.round!!.startedAt)
                val resumed = owner.resumeRound(roundId)
                awaitReady(partner, resumed.roomId, resumed.revision)
                assertEquals(running.round!!.startedAt, resumed.round!!.startedAt)
                val completed = owner.finishRound(roundId)
                awaitReady(partner, completed.roomId, completed.revision)
                assertEquals(CompletionReason.MANUAL, completed.round!!.completionReason)

                val ownerResult = owner.loadHistoryItem(roundId)
                val partnerResult = partner.loadHistoryItem(roundId)
                assertEquals(ownerResult, partnerResult)
                assertEquals(1, ownerResult.participants.first { it.userId == owner.state.value.currentUser.id }.completedTaskCount)
                owner.loadHistory()
                partner.loadHistory()
                assertTrue(owner.state.value.history.any { it.roundId == roundId })
                assertTrue(partner.state.value.history.any { it.roundId == roundId })

                val changed = owner.updateTask(taskId, title = "Правка после завершения", completed = false)
                awaitReady(partner, changed.roomId, changed.revision)
                assertEquals(ownerResult, owner.loadHistoryItem(roundId), "History must remain frozen after later task edits")
                val breakRoom = owner.startRound(RoundKind.BREAK)
                awaitReady(partner, breakRoom.roomId, breakRoom.revision)
                val breakId = breakRoom.round!!.id
                val closed = partner.closeRoom()
                withTimeout(10_000) {
                    while (owner.state.value.room?.status != RoomStatus.CLOSED) delay(20)
                }
                assertEquals(RoomStatus.CLOSED, closed.status)
                assertEquals(RoundStatus.CANCELLED, closed.round!!.status)
                val cancelled = owner.loadHistoryItem(breakId)
                assertEquals(CompletionReason.ROOM_CLOSED, cancelled.completionReason)
                assertFalse(owner.canMutate)
                assertFalse(partner.canMutate)
                owner.logout()
                partner.logout()
            } finally { partner.close() }
        } finally { owner.close() }
    }

    private suspend fun awaitReady(repository: RealRepository, roomId: String, revision: Long): RoomSnapshot = withTimeout(10_000) {
        while (true) {
            val room = repository.state.value.room
            if (repository.canMutate && room?.roomId == roomId && room.revision >= revision) return@withTimeout room
            delay(20)
        }
        @Suppress("UNREACHABLE_CODE")
        error("Unreachable")
    }
}
