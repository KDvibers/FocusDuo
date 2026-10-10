package dev.focusduo.data

import dev.focusduo.domain.RoomSnapshot
import dev.focusduo.domain.RoundStatus
import java.time.Duration

/**
 * A display projection, never an authority for round transitions or history.
 * A server timestamp is anchored to local monotonic time when its snapshot is accepted.
 * Local wall-clock changes cannot move this countdown.
 */
class ServerTimer(private val nanoTime: () -> Long = System::nanoTime) {
    private data class Anchor(val room: RoomSnapshot, val receivedAtNanos: Long, val transitMs: Long)
    private var anchor: Anchor? = null

    /** A measured REST round-trip / 2 may be supplied; WebSocket transit is unknown and defaults to 0. */
    @Synchronized
    fun onSnapshot(room: RoomSnapshot, estimatedTransitMs: Long = 0L) {
        // An ignored duplicate must not restart the local countdown from an old server timestamp.
        if (anchor?.room == room) return
        anchor = Anchor(room, nanoTime(), estimatedTransitMs.coerceAtLeast(0L))
    }

    @Synchronized
    fun clear() {
        anchor = null
    }

    /**
     * Only timing fields of the returned copy change. In particular RUNNING stays RUNNING at zero,
     * and the repository's original snapshot and immutable result records remain untouched.
     */
    @Synchronized
    fun displayRoom(room: RoomSnapshot): RoomSnapshot {
        val round = room.round ?: return room
        val matching = anchor?.takeIf { it.room == room }
        val elapsedMs = matching?.let {
            // Subtraction remains valid across nanoTime's signed wrap for normal application lifetimes.
            val sinceReceipt = (nanoTime() - it.receivedAtNanos).coerceAtLeast(0L) / NANOS_PER_MS
            saturatedAdd(sinceReceipt, it.transitMs)
        } ?: 0L
        val durationMs = (round.durationSeconds.toLong() * 1_000L).coerceAtLeast(0L)
        val displayed = when (round.status) {
            RoundStatus.RUNNING -> {
                val initialRemaining = round.endsAt?.let {
                    try {
                        Duration.between(room.serverNow, it).toMillis()
                    } catch (_: ArithmeticException) {
                        if (it > room.serverNow) Long.MAX_VALUE else Long.MIN_VALUE
                    }
                } ?: round.remainingMs
                round.copy(
                    remainingMs = (initialRemaining.coerceIn(0L, durationMs) - elapsedMs).coerceAtLeast(0L),
                    activeElapsedMs = saturatedAdd(round.activeElapsedMs.coerceAtLeast(0L), elapsedMs)
                        .coerceAtMost(durationMs),
                )
            }
            RoundStatus.PAUSED -> round // The server has frozen both timing fields.
            RoundStatus.COMPLETED, RoundStatus.CANCELLED -> round.copy(remainingMs = 0L)
        }
        return if (displayed == round) room else room.copy(round = displayed)
    }

    private fun saturatedAdd(first: Long, second: Long): Long =
        if (Long.MAX_VALUE - first < second) Long.MAX_VALUE else first + second

    companion object {
        private const val NANOS_PER_MS = 1_000_000L
    }
}
