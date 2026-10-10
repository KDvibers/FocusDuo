package dev.focusduo.presentation

import kotlinx.coroutines.sync.Mutex

/**
 * Routes heartbeat gaps and qualified window hints through one recovery admission gate.
 * Both clocks must participate: nanoTime can exclude suspend even when the detector sees a wall gap.
 */
internal class ResumeRecoveryCoordinator(
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
    private val nanoTime: () -> Long = System::nanoTime,
    private val minimumIntervalMs: Long = 2_000L,
    private val recover: suspend () -> Unit,
) {
    init { require(minimumIntervalMs > 0L) }

    private val detector = ResumeDetector(currentTimeMillis = currentTimeMillis, nanoTime = nanoTime)
    private val recovery = Mutex()
    private data class Stamp(val wallMs: Long, val nanos: Long)
    private var lastRecovery: Stamp? = null

    /** Called by the workspace's single heartbeat coroutine. */
    suspend fun onHeartbeat() {
        if (detector.sample()) recoverIfDue()
    }

    /** Main has already qualified this hint through its foreground ResumeDetector. */
    suspend fun onForegroundHint() = recoverIfDue()

    private suspend fun recoverIfDue() {
        if (!recovery.tryLock()) return
        try {
            val now = Stamp(currentTimeMillis(), nanoTime())
            if (lastRecovery?.let { elapsedMillis(it, now) < minimumIntervalMs } == true) return
            lastRecovery = now
            recover()
        } finally {
            recovery.unlock()
        }
    }

    private fun elapsedMillis(previous: Stamp, now: Stamp): Long {
        val wallElapsed = if (now.wallMs < previous.wallMs) 0L else {
            (now.wallMs - previous.wallMs).takeIf { it >= 0L } ?: Long.MAX_VALUE
        }
        // Signed subtraction preserves elapsed time across nanoTime wrap for practical intervals.
        val monotonicElapsed = (now.nanos - previous.nanos).coerceAtLeast(0L) / 1_000_000L
        return maxOf(wallElapsed, monotonicElapsed)
    }
}
