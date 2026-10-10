package dev.focusduo.data.remote

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

@Serializable
internal data class CreateRoomRequest(val focusDurationSeconds: Int, val breakDurationSeconds: Int)

@Serializable
internal data class JoinRoomRequest(val code: String)

@Serializable
internal data class GoalRequest(val goal: String)

@Serializable
internal data class TaskRequest(val title: String)

/** Absent PATCH values must stay absent, rather than becoming JSON nulls. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
internal data class TaskPatchRequest(
    @EncodeDefault(EncodeDefault.Mode.NEVER) val title: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val completed: Boolean? = null,
)

@Serializable
internal data class StartRoundRequest(val kind: String)
