package dev.focusduo.presentation

import kotlin.test.Test
import kotlin.test.assertEquals

class TimerFormattingTest {
    @Test fun `countdown keeps every positive remainder visible until the deadline`() {
        val cases = mapOf(
            0L to "00:00",
            1L to "00:01",
            999L to "00:01",
            1_000L to "00:01",
            1_001L to "00:02",
            59_001L to "01:00",
            60_001L to "01:01",
        )
        cases.forEach { (remainingMs, expected) -> assertEquals(expected, formatCountdown(remainingMs), "remainingMs=$remainingMs") }
    }

    @Test fun `countdown clamps expired time and supports the longest contract focus`() {
        assertEquals("00:00", formatCountdown(-1L))
        assertEquals("00:00", formatCountdown(Long.MIN_VALUE))
        assertEquals("180:00", formatCountdown(10_800_000L))
    }

    @Test fun `early result never invents a whole second of active time`() {
        listOf(Long.MIN_VALUE, -1L, 0L, 1L, 999L).forEach { activeMs ->
            assertEquals("Менее секунды", formatActiveTime(activeMs), "activeElapsedMs=$activeMs")
        }
        assertEquals("1 сек", formatActiveTime(1_000L))
        assertEquals("1 сек", formatActiveTime(1_999L))
    }

    @Test fun `result displays actual minutes and seconds without rounding to planned duration`() {
        assertEquals("59 сек", formatActiveTime(59_999L))
        assertEquals("1 мин", formatActiveTime(60_000L))
        assertEquals("1 мин 1 сек", formatActiveTime(61_999L))
        assertEquals("180 мин", formatActiveTime(10_800_000L))
    }
}
