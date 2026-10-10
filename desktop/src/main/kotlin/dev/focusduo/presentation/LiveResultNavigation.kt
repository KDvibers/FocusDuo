package dev.focusduo.presentation

import dev.focusduo.domain.HistoryItem
import dev.focusduo.domain.RoomSnapshot
import dev.focusduo.domain.RoundStatus

internal enum class RealPage { HOME, ROOM, HISTORY, RESULT, SETTINGS }
internal enum class LiveResultDestination { KEEP_PAGE, ROOM, RESULT }

/** Keep the original intent on a failed read, including when the user presses retry. */
internal data class ResultLoadRequest(val roundId: String, val automaticRoomId: String? = null) {
    val automatic: Boolean get() = automaticRoomId != null

    fun keepsExplicitRetryVisible(page: RealPage): Boolean = !automatic &&
        page in listOf(RealPage.HISTORY, RealPage.RESULT)

    fun isRelevant(room: RoomSnapshot?): Boolean = !automatic ||
        (room != null && room.roomId == automaticRoomId && room.round?.id == roundId &&
            room.round.status in listOf(RoundStatus.COMPLETED, RoundStatus.CANCELLED))

    fun destinationAfterLoad(
        result: HistoryItem, page: RealPage, room: RoomSnapshot?, selectedResult: HistoryItem?,
        selectedResultOpenedAutomatically: Boolean, navigationBlocked: Boolean,
    ): LiveResultDestination = when {
        !automatic -> LiveResultDestination.RESULT
        !isRelevant(room) || !isCurrentTerminalResult(result, room) -> LiveResultDestination.KEEP_PAGE
        else -> liveResultDestination(page, room, selectedResult,
            selectedResultOpenedAutomatically, navigationBlocked)
    }
}

/** Automatic summaries may follow their live room; explicitly opened history never does. */
internal fun liveResultDestination(
    page: RealPage,
    room: RoomSnapshot?,
    selectedResult: HistoryItem?,
    selectedResultOpenedAutomatically: Boolean,
    navigationBlocked: Boolean,
): LiveResultDestination {
    if (navigationBlocked) return LiveResultDestination.KEEP_PAGE
    val round = room?.round ?: return LiveResultDestination.KEEP_PAGE
    val terminal = round.status in listOf(RoundStatus.COMPLETED, RoundStatus.CANCELLED)
    if (terminal && page in listOf(RealPage.HOME, RealPage.ROOM)) return LiveResultDestination.RESULT
    if (page == RealPage.RESULT && selectedResultOpenedAutomatically &&
        selectedResult?.roomId == room.roomId && selectedResult.roundId != round.id) {
        return if (terminal) LiveResultDestination.RESULT else LiveResultDestination.ROOM
    }
    return LiveResultDestination.KEEP_PAGE
}

/** A slow history response must not send the user back from a newer live round. */
internal fun isCurrentTerminalResult(result: HistoryItem, room: RoomSnapshot?): Boolean =
    room?.roomId == result.roomId && room.round?.id == result.roundId &&
        room.round.status in listOf(RoundStatus.COMPLETED, RoundStatus.CANCELLED)
