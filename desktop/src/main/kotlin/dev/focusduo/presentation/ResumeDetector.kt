package dev.focusduo.presentation

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * A heartbeat gap means that the displayed server clock needs a fresh anchor. Some platforms count
 * suspended time in nanoTime and others do not, so either clock can request resynchronization.
 * A forward calendar correction may also request a harmless refresh; a backward correction cannot.
 * This detects a need to refresh, rather than claiming to identify an OS power event precisely.
 */
class ResumeDetector(
    private val gapThresholdMs: Long = 10_000L,
    private val foregroundThresholdMs: Long = 5_000L,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    init {
        require(gapThresholdMs > 0L)
        require(foregroundThresholdMs > 0L)
    }

    private data class Stamp(val wallMs: Long, val nanos: Long)
    private var heartbeat: Stamp? = null
    private var background: Stamp? = null

    /** Call on a regular heartbeat. The first call only establishes a baseline. */
    fun sample(): Boolean {
        val now = stamp()
        val previous = heartbeat
        heartbeat = now
        return previous != null && elapsed(previous, now) >= gapThresholdMs
    }

    /** Focus loss and minimization can describe the same absence; keep its earliest timestamp. */
    fun backgrounded() {
        if (background == null) background = stamp()
    }

    /** Initial focus does nothing; restoration and focus gain consume the same absence once. */
    fun foregrounded(): Boolean {
        val previous = background ?: return false
        background = null
        return elapsed(previous, stamp()) >= foregroundThresholdMs
    }

    private fun stamp() = Stamp(currentTimeMillis(), nanoTime())

    private fun elapsed(previous: Stamp, now: Stamp): Long {
        val wallElapsed = if (now.wallMs < previous.wallMs) 0L else {
            // Saturate the subtraction for synthetic or unusual extreme calendar values.
            (now.wallMs - previous.wallMs).takeIf { it >= 0L } ?: Long.MAX_VALUE
        }
        // Subtraction also handles a signed nanoTime wrap for intervals shorter than 292 years.
        val monotonicElapsed = (now.nanos - previous.nanos).coerceAtLeast(0L) / 1_000_000L
        return maxOf(wallElapsed, monotonicElapsed)
    }
}

/** Main's AWT events are hints only. Repositories always confirm state through REST and WS. */
object AppResumeSignals {
    private val mutableEvents = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<Unit> = mutableEvents.asSharedFlow()
    fun notifyForegrounded() { mutableEvents.tryEmit(Unit) }
}
