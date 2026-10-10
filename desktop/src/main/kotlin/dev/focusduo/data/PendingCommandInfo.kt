package dev.focusduo.data

/** Safe presentation of a request kept only in RAM. Payload, key and credentials are never exposed. */
data class PendingCommandInfo(
    val description: String,
    val roomId: String?,
    val originalRevision: Long?,
    val retryAvailable: Boolean = false,
)
