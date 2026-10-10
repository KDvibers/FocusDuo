package dev.focusduo.data.remote

import dev.focusduo.domain.CompletionReason
import dev.focusduo.domain.HistoryItem
import dev.focusduo.domain.HistoryParticipant
import dev.focusduo.domain.Participant
import dev.focusduo.domain.RoomSnapshot
import dev.focusduo.domain.RoomStatus
import dev.focusduo.domain.Round
import dev.focusduo.domain.RoundKind
import dev.focusduo.domain.RoundStatus
import dev.focusduo.domain.Task
import dev.focusduo.domain.User
import java.time.Instant
import java.util.UUID

fun UserDto.toDomain(): User = User(contractUuid(id), username, displayName)

fun TaskDto.toDomain(): Task = Task(contractUuid(id), title, completed)

fun ParticipantDto.toDomain(): Participant = Participant(
    userId = contractUuid(userId),
    displayName = displayName,
    goal = goal,
    tasks = tasks.map(TaskDto::toDomain),
)

fun RoundDto.toDomain(): Round = Round(
    id = contractUuid(id),
    kind = RoundKind.valueOf(kind),
    status = RoundStatus.valueOf(status),
    durationSeconds = durationSeconds,
    startedAt = Instant.parse(startedAt),
    endsAt = endsAt?.let(Instant::parse),
    remainingMs = remainingMs,
    activeElapsedMs = activeElapsedMs,
    endedAt = endedAt?.let(Instant::parse),
    completionReason = completionReason?.let(CompletionReason::valueOf),
)

fun RoomSnapshotDto.toDomain(): RoomSnapshot = RoomSnapshot(
    roomId = contractUuid(roomId),
    code = code,
    ownerId = contractUuid(ownerId),
    status = RoomStatus.valueOf(status),
    revision = revision,
    serverNow = Instant.parse(serverNow),
    focusDurationSeconds = focusDurationSeconds,
    breakDurationSeconds = breakDurationSeconds,
    participants = participants.map(ParticipantDto::toDomain),
    round = round?.toDomain(),
)

fun HistoryParticipantDto.toDomain(): HistoryParticipant = HistoryParticipant(
    userId = contractUuid(userId),
    displayName = displayName,
    goal = goal,
    tasks = tasks.map(TaskDto::toDomain),
    totalTaskCount = totalTaskCount,
    completedTaskCount = completedTaskCount,
)

fun HistoryItemDto.toDomain(): HistoryItem {
    val terminalStatus = RoundStatus.valueOf(status)
    require(terminalStatus == RoundStatus.COMPLETED || terminalStatus == RoundStatus.CANCELLED) {
        "History status must be terminal"
    }
    return HistoryItem(
        roundId = contractUuid(roundId),
        roomId = contractUuid(roomId),
        kind = RoundKind.valueOf(kind),
        status = terminalStatus,
        completionReason = CompletionReason.valueOf(completionReason),
        durationSeconds = durationSeconds,
        startedAt = Instant.parse(startedAt),
        endedAt = Instant.parse(endedAt),
        activeElapsedMs = activeElapsedMs,
        participants = participants.map(HistoryParticipantDto::toDomain),
    )
}

fun RoomEventDto.toDomain(): RoomSnapshot {
    require(type == "ROOM_SNAPSHOT") { "Unsupported room event type" }
    return data.toDomain()
}

/** Java accepts abbreviated UUIDs; the wire contract requires the complete UUID representation. */
private fun contractUuid(value: String): String {
    val parsed = if (value.length == 36) runCatching { UUID.fromString(value) }.getOrNull() else null
    require(parsed != null && parsed.toString().equals(value, ignoreCase = true)) {
        "Invalid UUID in server response"
    }
    return parsed.toString()
}
