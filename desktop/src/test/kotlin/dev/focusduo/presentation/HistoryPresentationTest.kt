package dev.focusduo.presentation

import dev.focusduo.domain.*
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

class HistoryPresentationTest {
    @Test fun `kind filters retain manual and cancelled focus in the repository order`() {
        val manual = result("manual", RoundKind.FOCUS, CompletionReason.MANUAL)
        val rest = result("rest", RoundKind.BREAK)
        val cancelled = result("cancelled", RoundKind.FOCUS, CompletionReason.ROOM_CLOSED, RoundStatus.CANCELLED)
        val items = listOf(manual, rest, cancelled)

        assertEquals(items, filterHistory(items, HistoryFilter.ALL))
        assertEquals(listOf(manual, cancelled), filterHistory(items, HistoryFilter.FOCUS))
        assertEquals(listOf(rest), filterHistory(items, HistoryFilter.BREAK))
        assertEquals(items, listOf(manual, rest, cancelled))
    }

    @Test fun `empty kind filter does not manufacture results`() {
        assertEquals(emptyList(), filterHistory(listOf(result("focus", RoundKind.FOCUS)), HistoryFilter.BREAK))
        HistoryFilter.entries.forEach { assertEquals(emptyList(), filterHistory(emptyList(), it)) }
    }

    @Test fun `status distinguishes elapsed manual and cancellation`() {
        assertEquals("Завершено по времени", historyStatusLabel(result("elapsed", RoundKind.FOCUS)))
        assertEquals("Завершено досрочно", historyStatusLabel(result("manual", RoundKind.BREAK, CompletionReason.MANUAL)))
        assertEquals("Прервано · комната закрыта", historyStatusLabel(result("closed", RoundKind.FOCUS, CompletionReason.ROOM_CLOSED, RoundStatus.CANCELLED)))
    }

    @Test fun `end date uses explicit local zone including change of calendar day`() {
        val instant = Instant.parse("2026-10-10T20:30:00Z")
        val utcDate = formatHistoryDate(instant, ZoneId.of("UTC"))
        val localDate = formatHistoryDate(instant, ZoneId.of("Asia/Qyzylorda"))

        assertEquals("10 окт. 2026 · 20:30", utcDate)
        assertEquals("11 окт. 2026 · 01:30", localDate)
    }

    private fun result(
        id: String,
        kind: RoundKind,
        reason: CompletionReason = CompletionReason.ELAPSED,
        status: RoundStatus = RoundStatus.COMPLETED,
    ) = HistoryItem(
        roundId = id,
        roomId = "room",
        kind = kind,
        status = status,
        completionReason = reason,
        durationSeconds = if (kind == RoundKind.FOCUS) 1_500 else 300,
        startedAt = Instant.parse("2026-10-10T08:00:00Z"),
        endedAt = Instant.parse("2026-10-10T08:25:00Z"),
        activeElapsedMs = 1_500_000,
        participants = emptyList(),
    )
}
