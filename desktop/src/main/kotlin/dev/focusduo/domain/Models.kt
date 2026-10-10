package dev.focusduo.domain

import java.time.Instant

/** Domain names and units follow the shared v1 contract; transport DTOs come later. */
data class User(val id: String, val username: String, val displayName: String)

data class Task(val id: String, val title: String, val completed: Boolean)

data class Participant(
    val userId: String,
    val displayName: String,
    val goal: String,
    val tasks: List<Task>,
)

enum class RoomStatus { OPEN, CLOSED }
enum class RoundKind { FOCUS, BREAK }
enum class RoundStatus { RUNNING, PAUSED, COMPLETED, CANCELLED }
enum class CompletionReason { ELAPSED, MANUAL, ROOM_CLOSED }

data class Round(
    val id: String,
    val kind: RoundKind,
    val status: RoundStatus,
    val durationSeconds: Int,
    val startedAt: Instant,
    val endsAt: Instant?,
    val remainingMs: Long,
    val activeElapsedMs: Long,
    val endedAt: Instant?,
    val completionReason: CompletionReason?,
)

data class RoomSnapshot(
    val roomId: String,
    val code: String,
    val ownerId: String,
    val status: RoomStatus,
    val revision: Long,
    val serverNow: Instant,
    val focusDurationSeconds: Int,
    val breakDurationSeconds: Int,
    val participants: List<Participant>,
    val round: Round?,
)

data class HistoryParticipant(
    val userId: String,
    val displayName: String,
    val goal: String,
    val tasks: List<Task>,
    val totalTaskCount: Int,
    val completedTaskCount: Int,
)

data class HistoryItem(
    val roundId: String,
    val roomId: String,
    val kind: RoundKind,
    val status: RoundStatus,
    val completionReason: CompletionReason,
    val durationSeconds: Int,
    val startedAt: Instant,
    val endedAt: Instant,
    val activeElapsedMs: Long,
    val participants: List<HistoryParticipant>,
)

data class RepositoryState(
    val currentUser: User,
    val room: RoomSnapshot? = null,
    val history: List<HistoryItem> = emptyList(),
)

class RepositoryException(
    val code: String,
    override val message: String,
    val fieldErrors: Map<String, String> = emptyMap(),
) : Exception(message)
