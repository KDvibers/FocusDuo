package dev.focusduo.presentation

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ResumeRecoveryCoordinatorTest {
    private class Clocks(var wall: Long = 1_000_000L, var nanos: Long = 100_000_000_000L) {
        fun advance(wallMs: Long, monotonicMs: Long = wallMs) {
            wall += wallMs
            nanos += monotonicMs * 1_000_000L
        }

        fun mainDetector() = ResumeDetector(currentTimeMillis = { wall }, nanoTime = { nanos })

        fun coordinator(recover: suspend () -> Unit) = ResumeRecoveryCoordinator(
            currentTimeMillis = { wall }, nanoTime = { nanos }, recover = recover,
        )
    }

    // Main validates the absence before sending a foreground hint to the workspace coordinator.
    private suspend fun restoreFocus(main: ResumeDetector, coordinator: ResumeRecoveryCoordinator) {
        if (main.foregrounded()) coordinator.onForegroundHint()
    }

    @Test fun `calendar wake reaches recovery when last recovery was only one monotonic second ago`() = runBlocking<Unit> {
        val clocks = Clocks(wall = 995_000L, nanos = 95_000_000_000L)
        val main = clocks.mainDetector()
        var recoveries = 0
        val coordinator = clocks.coordinator { recoveries++ }
        coordinator.onHeartbeat()
        main.backgrounded()
        clocks.advance(5_000)
        restoreFocus(main, coordinator)
        assertEquals(1, recoveries)
        assertEquals(1_000_000L, clocks.wall)
        assertEquals(100_000_000_000L, clocks.nanos)
        coordinator.onHeartbeat()

        clocks.advance(wallMs = 61_000, monotonicMs = 1_000)
        coordinator.onHeartbeat()
        assertEquals(2, recoveries, "A wall-clock sleep gap must survive the recovery rate limit")
        clocks.advance(1_000)
        coordinator.onHeartbeat()
        assertEquals(2, recoveries, "The consumed heartbeat gap must not request a third recovery")
    }

    @Test fun `foreground then heartbeat coalesce a wake with stopped monotonic clock`() = runBlocking<Unit> {
        val clocks = Clocks()
        val main = clocks.mainDetector()
        var recoveries = 0
        val coordinator = clocks.coordinator { recoveries++ }
        coordinator.onHeartbeat()
        main.backgrounded()
        clocks.advance(5_000)
        restoreFocus(main, coordinator)
        coordinator.onHeartbeat()
        assertEquals(1, recoveries)

        main.backgrounded()
        clocks.advance(wallMs = 61_000, monotonicMs = 1_000)
        restoreFocus(main, coordinator)
        assertEquals(2, recoveries)
        coordinator.onHeartbeat()
        restoreFocus(main, coordinator)
        assertEquals(2, recoveries, "Focus, restore and heartbeat describe one wake")
    }

    @Test fun `heartbeat then foreground coalesce a wake with stopped monotonic clock`() = runBlocking<Unit> {
        val clocks = Clocks()
        val main = clocks.mainDetector()
        var recoveries = 0
        val coordinator = clocks.coordinator { recoveries++ }
        coordinator.onHeartbeat()
        main.backgrounded()
        clocks.advance(5_000)
        restoreFocus(main, coordinator)
        coordinator.onHeartbeat()

        main.backgrounded()
        clocks.advance(wallMs = 61_000, monotonicMs = 1_000)
        coordinator.onHeartbeat()
        assertEquals(2, recoveries)
        restoreFocus(main, coordinator)
        assertEquals(2, recoveries, "The later validated foreground hint must not start another cycle")
    }

    @Test fun `ordinary heartbeats initial focus and short absences do not recover`() = runBlocking<Unit> {
        val clocks = Clocks()
        val main = clocks.mainDetector()
        var recoveries = 0
        val coordinator = clocks.coordinator { recoveries++ }
        coordinator.onHeartbeat()
        restoreFocus(main, coordinator)
        repeat(20) {
            main.backgrounded()
            clocks.advance(1_000)
            coordinator.onHeartbeat()
            restoreFocus(main, coordinator)
        }
        assertEquals(0, recoveries)

        main.backgrounded()
        repeat(5) {
            clocks.advance(1_000)
            coordinator.onHeartbeat()
        }
        restoreFocus(main, coordinator)
        assertEquals(1, recoveries)
        assertFalse(main.foregrounded(), "Native focus and restore consume the same absence once")
    }

    @Test fun `rate limit accepts exact boundary and rejected hints do not extend it`() = runBlocking<Unit> {
        val clocks = Clocks()
        var recoveries = 0
        val coordinator = clocks.coordinator { recoveries++ }
        coordinator.onForegroundHint()
        clocks.advance(1_999)
        coordinator.onForegroundHint()
        assertEquals(1, recoveries)
        clocks.advance(1)
        coordinator.onForegroundHint()
        assertEquals(2, recoveries)
        coordinator.onForegroundHint()
        assertEquals(2, recoveries)
    }

    @Test fun `wall-only interval permits recovery at exact boundary`() = runBlocking<Unit> {
        val clocks = Clocks()
        var recoveries = 0
        val coordinator = clocks.coordinator { recoveries++ }
        coordinator.onForegroundHint()
        clocks.advance(wallMs = 1_999, monotonicMs = 0)
        coordinator.onForegroundHint()
        assertEquals(1, recoveries)
        clocks.advance(wallMs = 1, monotonicMs = 0)
        coordinator.onForegroundHint()
        assertEquals(2, recoveries)
    }

    @Test fun `backward wall correction does not admit a duplicate but monotonic interval still works`() = runBlocking<Unit> {
        val clocks = Clocks()
        var recoveries = 0
        val coordinator = clocks.coordinator { recoveries++ }
        coordinator.onForegroundHint()
        clocks.advance(wallMs = -60_000, monotonicMs = 1_999)
        coordinator.onForegroundHint()
        assertEquals(1, recoveries)
        clocks.advance(wallMs = 0, monotonicMs = 1)
        coordinator.onForegroundHint()
        assertEquals(2, recoveries)
    }

    @Test fun `heartbeat detected by monotonic clock survives backward calendar correction`() = runBlocking<Unit> {
        val clocks = Clocks()
        var recoveries = 0
        val coordinator = clocks.coordinator { recoveries++ }
        coordinator.onHeartbeat()
        clocks.advance(10_000)
        coordinator.onHeartbeat()
        assertEquals(1, recoveries)
        clocks.advance(wallMs = -60_000, monotonicMs = 10_000)
        coordinator.onHeartbeat()
        assertEquals(2, recoveries)
    }

    @Test fun `signed monotonic wrap preserves the recovery interval`() = runBlocking<Unit> {
        val clocks = Clocks(nanos = Long.MAX_VALUE - 1_000_000_000L)
        var recoveries = 0
        val coordinator = clocks.coordinator { recoveries++ }
        coordinator.onForegroundHint()
        clocks.advance(wallMs = 0, monotonicMs = 1_999)
        assertTrue(clocks.nanos < 0L, "The synthetic clock crossed the signed boundary")
        coordinator.onForegroundHint()
        assertEquals(1, recoveries)
        clocks.advance(wallMs = 0, monotonicMs = 1)
        coordinator.onForegroundHint()
        assertEquals(2, recoveries)
    }

    @Test fun `extreme forward calendar change reaches recovery without subtraction overflow`() = runBlocking<Unit> {
        val clocks = Clocks(wall = Long.MIN_VALUE)
        var recoveries = 0
        val coordinator = clocks.coordinator { recoveries++ }
        coordinator.onHeartbeat()
        coordinator.onForegroundHint()
        clocks.wall = Long.MAX_VALUE
        coordinator.onHeartbeat()
        assertEquals(2, recoveries)
        coordinator.onForegroundHint()
        assertEquals(2, recoveries)
    }

    @Test fun `overlapping signals do not invoke recovery concurrently`() = runBlocking<Unit> {
        val clocks = Clocks()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var recoveries = 0
        val coordinator = clocks.coordinator {
            recoveries++
            if (recoveries == 1) {
                entered.complete(Unit)
                release.await()
            }
        }
        val first = launch(start = CoroutineStart.UNDISPATCHED) { coordinator.onForegroundHint() }
        try {
            withTimeout(1_000) { entered.await() }
            clocks.advance(10_000)
            withTimeout(1_000) { coordinator.onForegroundHint() }
            assertEquals(1, recoveries, "An active recovery must not overlap a second callback")
        } finally {
            release.complete(Unit)
            first.join()
        }
        coordinator.onForegroundHint()
        assertEquals(2, recoveries, "The gate must unlock and retain the last accepted timestamp")
    }

    @Test fun `cancellation releases recovery gate and propagates to caller`() = runBlocking<Unit> {
        val clocks = Clocks()
        val entered = CompletableDeferred<Unit>()
        val neverCompletes = CompletableDeferred<Unit>()
        var recoveries = 0
        val coordinator = clocks.coordinator {
            recoveries++
            if (recoveries == 1) {
                entered.complete(Unit)
                neverCompletes.await()
            }
        }
        val first = launch(start = CoroutineStart.UNDISPATCHED) { coordinator.onForegroundHint() }
        withTimeout(1_000) { entered.await() }
        first.cancelAndJoin()
        assertTrue(first.isCancelled)
        clocks.advance(2_000)
        coordinator.onForegroundHint()
        assertEquals(2, recoveries)
    }

    @Test fun `recovery failure does not leave the gate locked`() = runBlocking<Unit> {
        val clocks = Clocks()
        var recoveries = 0
        val coordinator = clocks.coordinator {
            recoveries++
            if (recoveries == 1) throw IOException("Synthetic recovery failure")
        }
        assertFailsWith<IOException> { coordinator.onForegroundHint() }
        clocks.advance(2_000)
        coordinator.onForegroundHint()
        assertEquals(2, recoveries)
    }
}
