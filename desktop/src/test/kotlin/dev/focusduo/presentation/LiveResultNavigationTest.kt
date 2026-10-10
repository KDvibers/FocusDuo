package dev.focusduo.presentation

import dev.focusduo.domain.*
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiveResultNavigationTest {
    @Test fun `peer follows an automatic focus summary into the next break`() {
        val focus = result("focus", RoundKind.FOCUS)
        assertEquals(LiveResultDestination.KEEP_PAGE, destination(room("focus", RoundKind.FOCUS, RoundStatus.COMPLETED), focus))
        assertEquals(LiveResultDestination.ROOM, destination(room("break", RoundKind.BREAK, RoundStatus.RUNNING), focus))
        assertEquals(LiveResultDestination.RESULT, destination(room("break", RoundKind.BREAK, RoundStatus.COMPLETED), focus))
    }

    @Test fun `recovery can miss the entire active break and still replace the old automatic result`() {
        val focus = result("focus", RoundKind.FOCUS)
        val elapsedBreak = room("break", RoundKind.BREAK, RoundStatus.COMPLETED)
        assertEquals(LiveResultDestination.RESULT, destination(elapsedBreak, focus))
        assertEquals(LiveResultDestination.KEEP_PAGE, destination(elapsedBreak, result("break", RoundKind.BREAK)))
    }

    @Test fun `an already paused next round is followed after recovery`() {
        assertEquals(LiveResultDestination.ROOM,
            destination(room("break", RoundKind.BREAK, RoundStatus.PAUSED), result("focus", RoundKind.FOCUS)))
    }

    @Test fun `closing the room during the next round follows its saved cancellation`() {
        val cancelled = room("break", RoundKind.BREAK, RoundStatus.CANCELLED).copy(status = RoomStatus.CLOSED)
        assertEquals(LiveResultDestination.RESULT, destination(cancelled, result("focus", RoundKind.FOCUS)))
    }

    @Test fun `an explicitly opened historical summary remains visible during the next round and result`() {
        val focus = result("focus", RoundKind.FOCUS)
        for (status in listOf(RoundStatus.RUNNING, RoundStatus.COMPLETED)) {
            assertEquals(LiveResultDestination.KEEP_PAGE,
                destination(room("break", RoundKind.BREAK, status), focus, automatic = false))
        }
    }

    @Test fun `history settings and unrelated historical rooms are never redirected`() {
        val next = room("break", RoundKind.BREAK, RoundStatus.COMPLETED)
        for (page in listOf(RealPage.HISTORY, RealPage.SETTINGS)) {
            assertEquals(LiveResultDestination.KEEP_PAGE, destination(next, result("focus", RoundKind.FOCUS), page = page))
        }
        assertEquals(LiveResultDestination.KEEP_PAGE,
            destination(next, result("old-focus", RoundKind.FOCUS).copy(roomId = "previous-room")))
    }

    @Test fun `a form pending replay or conflict keeps the old result until its blocker is cleared`() {
        val focus = result("focus", RoundKind.FOCUS)
        for (status in listOf(RoundStatus.RUNNING, RoundStatus.COMPLETED)) {
            val next = room("break", RoundKind.BREAK, status)
            assertEquals(LiveResultDestination.KEEP_PAGE, destination(next, focus, blocked = true))
            assertEquals(if (status == RoundStatus.RUNNING) LiveResultDestination.ROOM else LiveResultDestination.RESULT,
                destination(next, focus))
        }
    }

    @Test fun `normal room and home completion still opens a result without redirecting active rounds`() {
        for (page in listOf(RealPage.HOME, RealPage.ROOM)) {
            assertEquals(LiveResultDestination.RESULT,
                destination(room("focus", RoundKind.FOCUS, RoundStatus.COMPLETED), null, page = page, automatic = false))
            assertEquals(LiveResultDestination.KEEP_PAGE,
                destination(room("focus", RoundKind.FOCUS, RoundStatus.RUNNING), null, page = page, automatic = false))
            assertEquals(LiveResultDestination.KEEP_PAGE,
                destination(room("focus", RoundKind.FOCUS, RoundStatus.COMPLETED), null, page = page, blocked = true))
        }
    }

    @Test fun `a stale result response cannot replace the newer active round or a manual historical selection`() {
        val focus = result("focus", RoundKind.FOCUS)
        assertTrue(isCurrentTerminalResult(focus, room("focus", RoundKind.FOCUS, RoundStatus.COMPLETED)))
        assertFalse(isCurrentTerminalResult(focus, room("break", RoundKind.BREAK, RoundStatus.RUNNING)))
        assertFalse(isCurrentTerminalResult(focus, room("break", RoundKind.BREAK, RoundStatus.COMPLETED)))
        assertFalse(isCurrentTerminalResult(focus, room("focus", RoundKind.FOCUS, RoundStatus.COMPLETED).copy(roomId = "other-room")))
        assertEquals(LiveResultDestination.KEEP_PAGE,
            destination(room("break", RoundKind.BREAK, RoundStatus.RUNNING), null, page = RealPage.ROOM))
        assertEquals(LiveResultDestination.KEEP_PAGE,
            destination(room("break", RoundKind.BREAK, RoundStatus.COMPLETED), focus, automatic = false))
        assertEquals(LiveResultDestination.KEEP_PAGE, destination(null, focus))
    }

    @Test fun `retry of a failed automatic focus result still follows the peer into break`() {
        val focus = result("focus", RoundKind.FOCUS)
        val completed = room("focus", RoundKind.FOCUS, RoundStatus.COMPLETED)
        // The failed request, rather than a new manual-history intent, is retained for retry.
        val failedRequest = ResultLoadRequest(focus.roundId, completed.roomId)
        assertTrue(failedRequest.isRelevant(completed))
        assertEquals(LiveResultDestination.RESULT,
            failedRequest.destinationAfterLoad(focus, RealPage.ROOM, completed, null, false, false))
        assertEquals(LiveResultDestination.ROOM,
            destination(room("break", RoundKind.BREAK, RoundStatus.RUNNING), focus, automatic = failedRequest.automatic))

        val explicitHistoryRetry = ResultLoadRequest(focus.roundId)
        assertEquals(LiveResultDestination.RESULT,
            explicitHistoryRetry.destinationAfterLoad(focus, RealPage.HISTORY, completed, null, false, false))
        assertEquals(LiveResultDestination.KEEP_PAGE,
            destination(room("break", RoundKind.BREAK, RoundStatus.RUNNING), focus, automatic = explicitHistoryRetry.automatic))
    }

    @Test fun `late automatic success or failure cannot offer an obsolete retry over a newer round or room`() {
        val focus = result("focus", RoundKind.FOCUS)
        val completed = room("focus", RoundKind.FOCUS, RoundStatus.COMPLETED)
        val pendingRead = ResultLoadRequest(focus.roundId, completed.roomId)
        assertTrue(pendingRead.isRelevant(completed), "Failure for the current terminal round needs an explicit retry")
        val newerRooms = listOf(room("break", RoundKind.BREAK, RoundStatus.RUNNING),
            room("break", RoundKind.BREAK, RoundStatus.COMPLETED), completed.copy(roomId = "new-room"), null)
        for (current in newerRooms) {
            assertFalse(pendingRead.isRelevant(current), "A late failure must not expose the previous live result")
            assertEquals(LiveResultDestination.KEEP_PAGE,
                pendingRead.destinationAfterLoad(focus, RealPage.ROOM, current, null, false, false))
            assertTrue(ResultLoadRequest(focus.roundId).isRelevant(current), "Explicit history errors still need retry")
        }
    }

    @Test fun `a new terminal snapshot cannot discard the failed historical read the user chose to retry`() {
        val historicalFocus = result("old-focus", RoundKind.FOCUS).copy(roomId = "previous-room")
        val pendingHistoricalRead = ResultLoadRequest(historicalFocus.roundId)
        val newBreak = room("break", RoundKind.BREAK, RoundStatus.COMPLETED)
        assertTrue(pendingHistoricalRead.isRelevant(newBreak), "A historical failure still belongs to the user's request")
        assertTrue(pendingHistoricalRead.keepsExplicitRetryVisible(RealPage.HISTORY))
        assertTrue(pendingHistoricalRead.keepsExplicitRetryVisible(RealPage.RESULT))
        assertEquals(LiveResultDestination.RESULT, pendingHistoricalRead.destinationAfterLoad(
            historicalFocus, RealPage.HISTORY, newBreak, null, false, false))
        assertEquals(LiveResultDestination.KEEP_PAGE, destination(newBreak, historicalFocus, automatic = false))
        // Leaving the historical context explicitly lets normal room completion resume.
        assertFalse(pendingHistoricalRead.keepsExplicitRetryVisible(RealPage.ROOM))
        assertEquals(LiveResultDestination.RESULT, destination(newBreak, null, page = RealPage.ROOM))
        assertFalse(ResultLoadRequest("break", newBreak.roomId).keepsExplicitRetryVisible(RealPage.HISTORY))
    }

    private fun destination(
        room: RoomSnapshot?, result: HistoryItem?, page: RealPage = RealPage.RESULT,
        automatic: Boolean = true, blocked: Boolean = false,
    ) = liveResultDestination(page, room, result, automatic, blocked)

    private val time = Instant.parse("2026-10-11T02:00:00Z")

    private fun room(id: String, kind: RoundKind, status: RoundStatus): RoomSnapshot {
        val terminal = status in listOf(RoundStatus.COMPLETED, RoundStatus.CANCELLED)
        return RoomSnapshot("room", "FD-ABC123", "owner", RoomStatus.OPEN, 20, time,
            900, 60, emptyList(), Round(id, kind, status, 60, time,
                if (status == RoundStatus.RUNNING) time.plusSeconds(60) else null,
                if (terminal) 0 else 60_000, if (terminal) 60_000 else 0,
                if (terminal) time.plusSeconds(60) else null,
                if (terminal) CompletionReason.ELAPSED else null))
    }

    private fun result(id: String, kind: RoundKind) = HistoryItem(id, "room", kind,
        RoundStatus.COMPLETED, CompletionReason.ELAPSED, 60, time, time.plusSeconds(60),
        60_000, emptyList())
}
