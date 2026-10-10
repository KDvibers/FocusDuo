package dev.focusduo.data

import dev.focusduo.domain.RoomSnapshot

/**
 * REST replies, idempotent replays and WebSocket messages use the same ordering rules.
 * The caller must discard messages from an obsolete connection before reaching this reducer.
 */
object RoomSnapshotReducer {
    fun reduce(current: RoomSnapshot?, incoming: RoomSnapshot): RoomSnapshot = when {
        current == null || incoming.roomId != current.roomId -> incoming
        incoming.revision < current.revision -> current
        incoming.revision > current.revision -> incoming
        incoming.serverNow <= current.serverNow -> current
        else -> current.copy(
            serverNow = incoming.serverNow,
            // Time passes without a new revision. Business data may only change with a revision.
            round = current.round?.let { old ->
                incoming.round?.takeIf { it.id == old.id && it.status == old.status }?.let { fresh ->
                    old.copy(remainingMs = fresh.remainingMs, activeElapsedMs = fresh.activeElapsedMs)
                } ?: old
            },
        )
    }
}
