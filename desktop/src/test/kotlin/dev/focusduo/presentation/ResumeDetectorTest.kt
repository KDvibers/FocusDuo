package dev.focusduo.presentation

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class ResumeDetectorTest {
    private class Clocks(var wall: Long = 100_000L, var nanos: Long = 500_000_000L) {
        fun detector() = ResumeDetector(currentTimeMillis = { wall }, nanoTime = { nanos })
        fun advance(ms: Long) { wall += ms; nanos += ms * 1_000_000L }
    }

    @Test fun `initial sample and regular heartbeat do not request synchronization`() {
        val clocks = Clocks()
        val detector = clocks.detector()
        assertFalse(detector.sample())
        repeat(100) {
            clocks.advance(1_000)
            assertFalse(detector.sample())
        }
    }

    @Test fun `suspend counted by both clocks requests one refresh and reanchors heartbeat`() {
        val clocks = Clocks()
        val detector = clocks.detector()
        detector.sample()
        clocks.advance(60_000)
        assertTrue(detector.sample())
        assertFalse(detector.sample())
        clocks.advance(1_000)
        assertFalse(detector.sample())
    }

    @Test fun `suspend omitted from monotonic clock is detected by forward wall gap`() {
        val clocks = Clocks()
        val detector = clocks.detector()
        detector.sample()
        clocks.wall += 60_000
        clocks.nanos += 1_000_000_000
        assertTrue(detector.sample())
    }

    @Test fun `backward calendar correction alone does not look like resume`() {
        val clocks = Clocks()
        val detector = clocks.detector()
        detector.sample()
        clocks.wall -= 3_600_000
        clocks.nanos += 1_000_000_000
        assertFalse(detector.sample())
        clocks.advance(1_000)
        assertFalse(detector.sample())
    }

    @Test fun `real monotonic gap still refreshes despite backward calendar change`() {
        val clocks = Clocks()
        val detector = clocks.detector()
        detector.sample()
        clocks.wall -= 3_600_000
        clocks.nanos += 60_000_000_000
        assertTrue(detector.sample())
    }

    @Test fun `threshold boundary refreshes but ordinary scheduler jitter does not`() {
        val clocks = Clocks()
        val detector = clocks.detector()
        detector.sample()
        clocks.advance(9_999)
        assertFalse(detector.sample())
        clocks.advance(10_000)
        assertTrue(detector.sample())
    }

    @Test fun `nanoTime signed wrap preserves elapsed detection`() {
        val clocks = Clocks(nanos = Long.MAX_VALUE - 5_000_000_000)
        val detector = clocks.detector()
        detector.sample()
        clocks.nanos += 10_000_000_000
        assertTrue(detector.sample())
    }

    @Test fun `initial focus and short dialog focus switches do not refresh`() {
        val clocks = Clocks()
        val detector = clocks.detector()
        assertFalse(detector.foregrounded())
        detector.backgrounded()
        clocks.advance(1_000)
        assertFalse(detector.foregrounded())
    }

    @Test fun `focus restoration and deiconification share one debounced refresh`() {
        val clocks = Clocks()
        val detector = clocks.detector()
        detector.backgrounded()
        clocks.advance(4_000)
        detector.backgrounded()
        clocks.advance(1_000)
        assertTrue(detector.foregrounded())
        assertFalse(detector.foregrounded())
    }

    @Test fun `foreground hint detects clock stopped during sleep without duplicate heartbeat`() {
        val clocks = Clocks()
        val detector = clocks.detector()
        detector.backgrounded()
        clocks.wall += 60_000
        assertTrue(detector.foregrounded())
        assertFalse(detector.foregrounded())
    }

    @Test fun `backward wall adjustment does not turn a brief focus change into resume`() {
        val clocks = Clocks()
        val detector = clocks.detector()
        detector.backgrounded()
        clocks.wall -= 30_000
        clocks.nanos += 1_000_000_000
        assertFalse(detector.foregrounded())
    }

    @Test fun `calendar elapsed saturates and invalid thresholds are rejected`() {
        val clocks = Clocks(wall = Long.MIN_VALUE)
        val detector = clocks.detector()
        detector.sample()
        clocks.wall = Long.MAX_VALUE
        assertTrue(detector.sample())
        assertFailsWith<IllegalArgumentException> { ResumeDetector(gapThresholdMs = 0L) }
        assertFailsWith<IllegalArgumentException> { ResumeDetector(foregroundThresholdMs = 0L) }
    }
}
