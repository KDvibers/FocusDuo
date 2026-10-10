package dev.focusduo.data

import dev.focusduo.data.remote.FocusDuoJson
import dev.focusduo.data.remote.RoomSnapshotDto
import dev.focusduo.data.remote.toDomain
import dev.focusduo.domain.CompletionReason
import dev.focusduo.domain.RoomSnapshot
import dev.focusduo.domain.RoundStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class ServerTimerTest {
    @Test fun `running projection uses server deadline plus monotonic elapsed and leaves raw snapshot intact`() {
        var nanos = 123_000_000L
        val timer = ServerTimer { nanos }
        val room = fixture("room-running")
        timer.onSnapshot(room)
        nanos += 2_750_000_000L
        val displayed = timer.displayRoom(room)
        assertEquals(1_119_250L, displayed.round!!.remainingMs)
        assertEquals(380_750L, displayed.round.activeElapsedMs)
        assertEquals(1_122_000L, room.round!!.remainingMs)
        assertEquals(room.serverNow, displayed.serverNow)
        assertEquals(room.revision, displayed.revision)
        assertSame(room.participants, displayed.participants)
    }

    @Test fun `deadline is authoritative instead of stale remaining field or client calendar`() {
        var nanos = 0L
        val timer = ServerTimer { nanos }
        val original = fixture("room-running")
        val room = original.copy(round = original.round!!.copy(remainingMs = 1L))
        timer.onSnapshot(room)
        nanos = 1_000_000_000L
        assertEquals(1_121_000L, timer.displayRoom(room).round!!.remainingMs)
    }

    @Test fun `reaching zero waits for backend and never completes locally`() {
        var nanos = 0L
        val timer = ServerTimer { nanos }
        val room = fixture("room-running")
        timer.onSnapshot(room)
        nanos = 9_000_000_000_000L
        val round = timer.displayRoom(room).round!!
        assertEquals(0L, round.remainingMs)
        assertEquals(1_500_000L, round.activeElapsedMs)
        assertEquals(RoundStatus.RUNNING, round.status)
        assertNull(round.endedAt)
        assertNull(round.completionReason)
    }

    @Test fun `paused timing stays frozen through arbitrary local elapsed and transit estimate`() {
        var nanos = 0L
        val timer = ServerTimer { nanos }
        val room = fixture("room-paused")
        timer.onSnapshot(room, estimatedTransitMs = 500L)
        nanos = 20_000_000_000_000L
        assertSame(room, timer.displayRoom(room))
    }

    @Test fun `resume takes a new deadline without counting time spent paused`() {
        var nanos = 0L
        val timer = ServerTimer { nanos }
        val paused = fixture("room-paused")
        timer.onSnapshot(paused)
        nanos = 20_000_000_000L
        val resumed = fixture("room-resumed")
        timer.onSnapshot(resumed)
        nanos += 3_000_000_000L
        val round = timer.displayRoom(resumed).round!!
        assertEquals(1_119_000L, round.remainingMs)
        assertEquals(381_000L, round.activeElapsedMs)
        assertEquals(resumed.round!!.startedAt, round.startedAt)
    }

    @Test fun `manual and cancelled results keep actual active time instead of planned duration`() {
        var nanos = 0L
        val timer = ServerTimer { nanos }
        val complete = fixture("room-completed")
        for (status in listOf(RoundStatus.COMPLETED, RoundStatus.CANCELLED)) {
            val room = complete.copy(round = complete.round!!.copy(status = status, activeElapsedMs = 12_345L,
                completionReason = if (status == RoundStatus.COMPLETED) CompletionReason.MANUAL else CompletionReason.ROOM_CLOSED))
            timer.onSnapshot(room)
            nanos += 9_000_000_000_000L
            assertEquals(0L, timer.displayRoom(room).round!!.remainingMs)
            assertEquals(12_345L, timer.displayRoom(room).round!!.activeElapsedMs)
            assertEquals(status, timer.displayRoom(room).round!!.status)
        }
    }

    @Test fun `duplicate snapshot cannot reanchor an ongoing countdown`() {
        var nanos = 0L
        val timer = ServerTimer { nanos }
        val room = fixture("room-running")
        timer.onSnapshot(room)
        nanos = 5_000_000_000L
        timer.onSnapshot(room.copy())
        assertEquals(1_117_000L, timer.displayRoom(room).round!!.remainingMs)
        val fresh = RoomSnapshotReducer.reduce(room, room.copy(serverNow = room.serverNow.plusSeconds(5),
            round = room.round!!.copy(remainingMs = 1_117_000L, activeElapsedMs = 383_000L)))
        timer.onSnapshot(fresh)
        nanos += 2_000_000_000L
        assertEquals(1_115_000L, timer.displayRoom(fresh).round!!.remainingMs)
    }

    @Test fun `optional transit adjustment advances remaining and active timing equally`() {
        var nanos = 0L
        val timer = ServerTimer { nanos }
        val room = fixture("room-running")
        timer.onSnapshot(room, estimatedTransitMs = 75L)
        nanos = 125_000_000L
        assertEquals(1_121_800L, timer.displayRoom(room).round!!.remainingMs)
        assertEquals(378_200L, timer.displayRoom(room).round!!.activeElapsedMs)
    }

    @Test fun `timing clamps expired deadlines and saturated delays within display range`() {
        val timer = ServerTimer { 0L }
        val original = fixture("room-running")
        val expired = original.copy(round = original.round!!.copy(endsAt = original.serverNow.minusSeconds(1)))
        timer.onSnapshot(expired, Long.MAX_VALUE)
        val displayed = timer.displayRoom(expired).round!!
        assertEquals(0L, displayed.remainingMs)
        assertEquals(1_500_000L, displayed.activeElapsedMs)
        val future = original.copy(round = original.round.copy(endsAt = original.serverNow.plusSeconds(9_000)))
        timer.onSnapshot(future)
        assertEquals(1_500_000L, timer.displayRoom(future).round!!.remainingMs)
    }

    @Test fun `signed monotonic wrap is supported and a backwards sample does not add elapsed time`() {
        var nanos = Long.MAX_VALUE - 500_000_000L
        val timer = ServerTimer { nanos }
        val room = fixture("room-running")
        timer.onSnapshot(room)
        nanos += 1_000_000_000L
        assertEquals(1_121_000L, timer.displayRoom(room).round!!.remainingMs)
        timer.clear()
        nanos = 5_000_000_000L
        timer.onSnapshot(room)
        nanos = 4_000_000_000L
        assertEquals(1_122_000L, timer.displayRoom(room).round!!.remainingMs)
    }

    @Test fun `new room cannot inherit old clock and clear drops the previous anchor`() {
        var nanos = 0L
        val timer = ServerTimer { nanos }
        val room = fixture("room-running")
        timer.onSnapshot(room)
        nanos = 15_000_000_000L
        val other = room.copy(roomId = "different-room")
        assertEquals(other.round, timer.displayRoom(other).round)
        timer.clear()
        assertEquals(room.round, timer.displayRoom(room).round)
        val waiting = room.copy(round = null)
        assertSame(waiting, timer.displayRoom(waiting))
    }

    private fun fixture(name: String): RoomSnapshot = FocusDuoJson.decodeFromString<RoomSnapshotDto>(
        javaClass.getResource("/contracts/$name.json")!!.readText(),
    ).toDomain()
}
