package dev.focusduo.data.remote

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Raw v1 JSON types. Domain objects do not depend on a JSON implementation. */
val FocusDuoJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = true
    encodeDefaults = true
}

@Serializable
data class LoginRequestDto(val username: String, val password: String) {
    override fun toString(): String = "LoginRequestDto(username=$username, password=<redacted>)"
}

@Serializable
data class RegisterRequestDto(val username: String, val displayName: String, val password: String) {
    override fun toString(): String =
        "RegisterRequestDto(username=$username, displayName=$displayName, password=<redacted>)"
}

@Serializable
data class UserDto(val id: String, val username: String, val displayName: String)

@Serializable
data class AuthSessionDto(val user: UserDto, val accessToken: String, val expiresAt: String) {
    override fun toString(): String =
        "AuthSessionDto(user=$user, accessToken=<redacted>, expiresAt=$expiresAt)"
}

@Serializable
data class TaskDto(val id: String, val title: String, val completed: Boolean)

@Serializable
data class ParticipantDto(
    val userId: String,
    val displayName: String,
    val goal: String,
    val tasks: List<TaskDto>,
)

@Serializable
data class RoundDto(
    val id: String,
    val kind: String,
    val status: String,
    val durationSeconds: Int,
    val startedAt: String,
    val endsAt: String?,
    val remainingMs: Long,
    val activeElapsedMs: Long,
    val endedAt: String?,
    val completionReason: String?,
)

@Serializable
data class RoomSnapshotDto(
    val roomId: String,
    val code: String,
    val ownerId: String,
    val status: String,
    val revision: Long,
    val serverNow: String,
    val focusDurationSeconds: Int,
    val breakDurationSeconds: Int,
    val participants: List<ParticipantDto>,
    val round: RoundDto?,
)

@Serializable
data class CurrentRoomDto(val room: RoomSnapshotDto?)

@Serializable
data class HistoryParticipantDto(
    val userId: String,
    val displayName: String,
    val goal: String,
    val tasks: List<TaskDto>,
    val totalTaskCount: Int,
    val completedTaskCount: Int,
)

@Serializable
data class HistoryItemDto(
    val roundId: String,
    val roomId: String,
    val kind: String,
    val status: String,
    val completionReason: String,
    val durationSeconds: Int,
    val startedAt: String,
    val endedAt: String,
    val activeElapsedMs: Long,
    val participants: List<HistoryParticipantDto>,
)

@Serializable
data class HistoryDto(val items: List<HistoryItemDto>)

@Serializable
data class ApiErrorDto(
    val code: String,
    val message: String,
    val requestId: String,
    val fieldErrors: Map<String, String>,
    val snapshot: RoomSnapshotDto?,
)

@Serializable
data class RoomEventDto(val type: String, val data: RoomSnapshotDto)
