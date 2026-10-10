package dev.focusduo.domain

import kotlinx.coroutines.flow.StateFlow

/** Room and round operations shared by the explicit demo and future network implementation. */
interface FocusDuoRepository {
    val state: StateFlow<RepositoryState>

    suspend fun createRoom(focusDurationSeconds: Int = 1500, breakDurationSeconds: Int = 300): RoomSnapshot
    suspend fun joinRoom(code: String): RoomSnapshot
    suspend fun updateGoal(goal: String): RoomSnapshot
    suspend fun addTask(title: String): RoomSnapshot
    suspend fun updateTask(taskId: String, title: String? = null, completed: Boolean? = null): RoomSnapshot
    suspend fun deleteTask(taskId: String): RoomSnapshot
    suspend fun updateSettings(focusDurationSeconds: Int, breakDurationSeconds: Int): RoomSnapshot
    suspend fun startRound(kind: RoundKind): RoomSnapshot
    suspend fun pauseRound(roundId: String): RoomSnapshot
    suspend fun resumeRound(roundId: String): RoomSnapshot
    suspend fun finishRound(roundId: String): RoomSnapshot
    suspend fun closeRoom(): RoomSnapshot
}
