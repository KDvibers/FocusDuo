package dev.focusduo.data

import dev.focusduo.data.remote.FocusDuoJson
import dev.focusduo.data.remote.RoomSnapshotDto
import dev.focusduo.data.remote.toDomain
import dev.focusduo.domain.RoomSnapshot
import dev.focusduo.domain.RoundStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class RoomSnapshotReducerTest {
    @Test fun `initial snapshot and different room are accepted regardless of revision`() {
        val current = fixture()
        assertSame(current, RoomSnapshotReducer.reduce(null, current))
        val otherRoom = current.copy(roomId = "another-room", revision = 1L)
        assertSame(otherRoom, RoomSnapshotReducer.reduce(current, otherRoom))
    }

    @Test fun `older idempotent replay cannot replace tasks even with later server time`() {
        val current = fixture()
        val replay = current.copy(revision = current.revision - 1, serverNow = current.serverNow.plusSeconds(60), participants = emptyList())
        assertSame(current, RoomSnapshotReducer.reduce(current, replay))
    }

    @Test fun `higher revision replaces the full business snapshot even with older timestamp`() {
        val current = fixture()
        val changed = current.copy(revision = current.revision + 1, serverNow = current.serverNow.minusSeconds(1), participants = emptyList())
        assertSame(changed, RoomSnapshotReducer.reduce(current, changed))
    }

    @Test fun `equal revision ignores duplicate and older server timestamps`() {
        val current = fixture()
        assertSame(current, RoomSnapshotReducer.reduce(current, current.copy(participants = emptyList())))
        assertSame(current, RoomSnapshotReducer.reduce(current, current.copy(serverNow = current.serverNow.minusMillis(1))))
    }

    @Test fun `equal revision newer server time refines only computed timer fields`() {
        val current = fixture()
        val round = current.round!!
        val fresh = current.copy(
            serverNow = current.serverNow.plusSeconds(5), code = "ILLEGAL-CHANGE", participants = emptyList(),
            focusDurationSeconds = 300,
            round = round.copy(remainingMs = round.remainingMs - 5_000, activeElapsedMs = round.activeElapsedMs + 5_000,
                startedAt = round.startedAt.plusSeconds(100), endsAt = round.endsAt!!.plusSeconds(100), durationSeconds = 300),
        )
        val applied = RoomSnapshotReducer.reduce(current, fresh)
        assertEquals(current.copy(serverNow = fresh.serverNow,
            round = round.copy(remainingMs = fresh.round!!.remainingMs, activeElapsedMs = fresh.round.activeElapsedMs)), applied)
    }

    @Test fun `equal revision cannot switch round identity or state`() {
        val current = fixture()
        for (different in listOf(current.round!!.copy(id = "another-round"), current.round.copy(status = RoundStatus.PAUSED))) {
            val applied = RoomSnapshotReducer.reduce(current, current.copy(serverNow = current.serverNow.plusSeconds(5), round = different))
            assertEquals(current.round, applied.round)
            assertEquals(current.serverNow.plusSeconds(5), applied.serverNow)
        }
    }

    @Test fun `equal revision cannot create or remove a round`() {
        val current = fixture()
        assertEquals(current.round, RoomSnapshotReducer.reduce(current,
            current.copy(serverNow = current.serverNow.plusSeconds(1), round = null)).round)
        val waiting = current.copy(round = null)
        assertEquals(null, RoomSnapshotReducer.reduce(waiting, current.copy(serverNow = current.serverNow.plusSeconds(1))).round)
    }

    private fun fixture(): RoomSnapshot = FocusDuoJson.decodeFromString<RoomSnapshotDto>(
        javaClass.getResource("/contracts/room-running.json")!!.readText(),
    ).toDomain()
}
