package dev.focusduo.data

import dev.focusduo.domain.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Collections
import java.util.Locale
import java.util.UUID

/** Explicit, in-memory demo. This class has no network connection or fallback behavior. */
class FakeRepository(
    initialDisplayName: String = "Ты",
    private val clock: Clock = DemoMonotonicClock(),
) : FocusDuoRepository {
    private var localUser = User(newId(), "demo_user", validateDisplayName(initialDisplayName))
    private var friendUser = User(newId(), "demo_friend", "Друг")
    private val mutex = Mutex()
    private var demoOffsetMs = 0L
    private val mutableState = MutableStateFlow(RepositoryState(localUser))
    override val state: StateFlow<RepositoryState> = mutableState.asStateFlow()

    override suspend fun createRoom(focusDurationSeconds: Int, breakDurationSeconds: Int): RoomSnapshot =
        mutex.withLock {
            val now = demoNow()
            normalizeTime(now)
            ensureNoOpenRoom()
            validateDurations(focusDurationSeconds, breakDurationSeconds)
            val user = state.value.currentUser
            publish(
                RoomSnapshot(
                    roomId = newId(), code = newCode(), ownerId = user.id, status = RoomStatus.OPEN,
                    revision = 1, serverNow = now,
                    focusDurationSeconds = focusDurationSeconds, breakDurationSeconds = breakDurationSeconds,
                    participants = listOf(Participant(user.id, user.displayName, "", emptyList())).frozen(),
                    round = null,
                ),
            )
        }

    override suspend fun joinRoom(code: String): RoomSnapshot = mutex.withLock {
        val now = demoNow()
        normalizeTime(now)
        val normalized = code.trim().uppercase(Locale.ROOT)
        if (normalized.isEmpty()) invalid("code", "Введите код комнаты.")
        val current = state.value.room
        if (current?.status == RoomStatus.CLOSED && current.code == normalized) {
            fail("ROOM_CLOSED", "Комната закрыта. Создайте новую комнату.")
        }
        if (current?.status == RoomStatus.OPEN && current.code == normalized) return@withLock current
        ensureNoOpenRoom()
        // A known code is the only simulated remote room. Real codes are opaque to the UI.
        if (normalized != SAMPLE_ROOM_CODE) {
            fail("NOT_FOUND", "Комната не найдена. В деморежиме используйте код $SAMPLE_ROOM_CODE.")
        }
        val user = state.value.currentUser
        publish(
            RoomSnapshot(
                roomId = newId(), code = SAMPLE_ROOM_CODE, ownerId = otherDemoUser().id, status = RoomStatus.OPEN,
                revision = 2, serverNow = now, focusDurationSeconds = 1500, breakDurationSeconds = 300,
                participants = listOf(
                    friendParticipant(),
                    Participant(
                        user.id, user.displayName, "закончить API",
                        listOf(
                            Task(newId(), "Создать комнату", true),
                            Task(newId(), "Добавить подключение", false),
                            Task(newId(), "Проверить ошибки", false),
                        ).frozen(),
                    ),
                ).frozen(),
                round = null,
            ),
        )
    }

    override suspend fun updateGoal(goal: String): RoomSnapshot = edit { room ->
        if (goal.length > 240) invalid("goal", "Цель — не более 240 символов.")
        room.updateCurrentParticipant { it.copy(goal = goal) }
    }

    override suspend fun addTask(title: String): RoomSnapshot = edit { room ->
        val normalized = validateTitle(title)
        room.updateCurrentParticipant { participant ->
            if (participant.tasks.size >= 20) invalid("title", "Можно добавить не более 20 задач.")
            participant.copy(tasks = (participant.tasks + Task(newId(), normalized, false)).frozen())
        }
    }

    override suspend fun updateTask(taskId: String, title: String?, completed: Boolean?): RoomSnapshot = edit { room ->
        if (title == null && completed == null) invalid("task", "Измените название или статус задачи.")
        val normalized = title?.let(::validateTitle)
        requireOwnTask(room, taskId)
        room.updateCurrentParticipant { participant ->
            participant.copy(tasks = participant.tasks.map { task ->
                if (task.id == taskId) task.copy(title = normalized ?: task.title, completed = completed ?: task.completed)
                else task
            }.frozen())
        }
    }

    override suspend fun deleteTask(taskId: String): RoomSnapshot = edit { room ->
        requireOwnTask(room, taskId)
        room.updateCurrentParticipant { participant ->
            participant.copy(tasks = participant.tasks.filterNot { it.id == taskId }.frozen())
        }
    }

    override suspend fun updateSettings(focusDurationSeconds: Int, breakDurationSeconds: Int): RoomSnapshot = edit { room ->
        if (room.ownerId != state.value.currentUser.id) fail("FORBIDDEN", "Длительности меняет только создатель комнаты.")
        if (room.round?.status in listOf(RoundStatus.RUNNING, RoundStatus.PAUSED)) {
            fail("INVALID_STATE", "Нельзя менять длительности во время активного раунда.")
        }
        validateDurations(focusDurationSeconds, breakDurationSeconds)
        room.copy(focusDurationSeconds = focusDurationSeconds, breakDurationSeconds = breakDurationSeconds)
    }

    override suspend fun startRound(kind: RoundKind): RoomSnapshot = command { room, now ->
        requireOwner(room)
        if (room.round?.status in ACTIVE_STATUSES) fail("INVALID_STATE", "В комнате уже есть активный раунд.")
        when (kind) {
            RoundKind.FOCUS -> if (room.participants.size != 2) {
                fail("INVALID_STATE", "Для начала фокуса нужны два участника.")
            }
            RoundKind.BREAK -> if (room.round?.kind != RoundKind.FOCUS || room.round.status != RoundStatus.COMPLETED) {
                fail("INVALID_STATE", "Перерыв доступен после завершённого раунда фокуса.")
            }
        }
        val duration = if (kind == RoundKind.FOCUS) room.focusDurationSeconds else room.breakDurationSeconds
        room.copy(round = Round(
            id = newId(), kind = kind, status = RoundStatus.RUNNING, durationSeconds = duration,
            startedAt = now, endsAt = now.plusSeconds(duration.toLong()), remainingMs = duration * 1000L,
            activeElapsedMs = 0, endedAt = null, completionReason = null,
        ))
    }

    override suspend fun pauseRound(roundId: String): RoomSnapshot = command { room, _ ->
        val round = requireCurrentRound(room, roundId)
        if (round.status != RoundStatus.RUNNING) fail("INVALID_STATE", "Приостановить можно только идущий раунд.")
        room.copy(round = round.copy(status = RoundStatus.PAUSED, endsAt = null))
    }

    override suspend fun resumeRound(roundId: String): RoomSnapshot = command { room, now ->
        val round = requireCurrentRound(room, roundId)
        if (round.status != RoundStatus.PAUSED) fail("INVALID_STATE", "Продолжить можно только приостановленный раунд.")
        room.copy(round = round.copy(status = RoundStatus.RUNNING, endsAt = now.plusMillis(round.remainingMs)))
    }

    override suspend fun finishRound(roundId: String): RoomSnapshot = command { room, now ->
        val round = requireCurrentRound(room, roundId)
        if (round.status !in ACTIVE_STATUSES) fail("INVALID_STATE", "Завершить можно только активный раунд.")
        room.copy(round = terminalRound(round, RoundStatus.COMPLETED, CompletionReason.MANUAL, now))
    }

    override suspend fun closeRoom(): RoomSnapshot = command { room, now ->
        room.copy(
            status = RoomStatus.CLOSED,
            round = room.round?.let { round ->
                if (round.status in ACTIVE_STATUSES) terminalRound(round, RoundStatus.CANCELLED, CompletionReason.ROOM_CLOSED, now)
                else round
            },
        )
    }

    /** The fake server owns automatic completion; the UI only asks it for a fresh snapshot. */
    suspend fun refreshDemoTime() = mutex.withLock { normalizeTime(demoNow()) }

    /** Explicit demo control for testing long rounds without changing contractual durations. */
    suspend fun advanceDemoTime(seconds: Long) = mutex.withLock {
        normalizeTime(demoNow())
        requireRoom()
        if (seconds !in 1..86400) invalid("seconds", "Демо-время можно продвинуть на 1–86400 секунд.")
        demoOffsetMs = Math.addExact(demoOffsetMs, seconds * 1000)
        normalizeTime(demoNow())
    }

    /** Demo control, not an API endpoint: simulate one partner accepting an invitation. */
    suspend fun simulatePartnerJoin(): RoomSnapshot = edit { room ->
        if (room.participants.size >= 2) fail("ROOM_FULL", "В комнате уже два участника.")
        room.copy(participants = (room.participants + friendParticipant()).frozen())
    }

    /** Demo control: inspect the same room as its other participant; no ownership changes. */
    suspend fun switchPerspective() = mutex.withLock {
        normalizeTime(demoNow())
        val room = requireRoom(allowClosed = true)
        val other = room.participants.firstOrNull { it.userId != state.value.currentUser.id }
            ?: fail("INVALID_STATE", "Сначала добавьте второго участника в демокомнату.")
        val nextUser = if (other.userId == localUser.id) localUser else friendUser
        mutableState.value = state.value.copy(currentUser = nextUser)
    }

    /** Demo profile is local only; the server contract does not expose a profile edit endpoint. */
    suspend fun updateDemoProfile(displayName: String) = mutex.withLock {
        val now = demoNow()
        normalizeTime(now)
        if (state.value.room != null) requireRoom()
        val normalized = validateDisplayName(displayName)
        val user = state.value.currentUser.copy(displayName = normalized)
        if (user.id == localUser.id) localUser = user else friendUser = user
        val room = state.value.room?.let { room ->
            room.copy(
                participants = room.participants.map { participant ->
                    if (participant.userId == user.id) participant.copy(displayName = normalized) else participant
                }.frozen(),
                revision = room.revision + 1, serverNow = now,
            )
        }
        mutableState.value = state.value.copy(currentUser = user, room = room)
    }

    /** Discard this local demo session; this is neither leave nor close on a real server. */
    suspend fun resetDemo() = mutex.withLock {
        demoOffsetMs = 0
        friendUser = friendUser.copy(displayName = "Друг")
        mutableState.value = RepositoryState(localUser)
    }

    private suspend fun edit(change: (RoomSnapshot) -> RoomSnapshot): RoomSnapshot = command { room, _ -> change(room) }

    private suspend fun command(change: (RoomSnapshot, Instant) -> RoomSnapshot): RoomSnapshot = mutex.withLock {
        val now = demoNow()
        // Publish expiry before validation so a rejected command cannot roll it back.
        normalizeTime(now)
        val current = requireRoom()
        val changed = change(current, now)
        publish(changed.copy(revision = current.revision + 1, serverNow = now))
    }

    private fun requireRoom(allowClosed: Boolean = false): RoomSnapshot {
        val room = state.value.room ?: fail("NOT_FOUND", "Сначала создайте комнату или войдите по коду.")
        if (!allowClosed && room.status == RoomStatus.CLOSED) fail("ROOM_CLOSED", "Комната закрыта.")
        if (room.participants.none { it.userId == state.value.currentUser.id }) fail("FORBIDDEN", "Вы не участник этой комнаты.")
        return room
    }

    private fun ensureNoOpenRoom() {
        if (state.value.room?.status == RoomStatus.OPEN) fail("ALREADY_IN_ROOM", "Вы уже состоите в открытой комнате. Сначала закройте её.")
    }

    private fun publish(room: RoomSnapshot): RoomSnapshot {
        val round = room.round
        val history = if (round != null && round.status !in ACTIVE_STATUSES &&
            state.value.history.none { it.roundId == round.id }) {
            val item = HistoryItem(
                roundId = round.id, roomId = room.roomId, kind = round.kind, status = round.status,
                completionReason = requireNotNull(round.completionReason), durationSeconds = round.durationSeconds,
                startedAt = round.startedAt, endedAt = requireNotNull(round.endedAt), activeElapsedMs = round.activeElapsedMs,
                participants = room.participants.map { participant ->
                    HistoryParticipant(
                        participant.userId, participant.displayName, participant.goal, participant.tasks.toList().frozen(),
                        participant.tasks.size, participant.tasks.count { it.completed },
                    )
                }.frozen(),
            )
            (state.value.history + item)
                .sortedWith(compareByDescending<HistoryItem> { it.endedAt }.thenByDescending { it.roundId })
                .take(50).frozen()
        } else state.value.history
        mutableState.value = state.value.copy(room = room, history = history)
        return room
    }

    private fun demoNow(): Instant = clock.instant().plusMillis(demoOffsetMs)

    private fun normalizeTime(now: Instant) {
        val room = state.value.room ?: return
        if (room.status != RoomStatus.OPEN) return
        val round = room.round ?: return
        if (round.status != RoundStatus.RUNNING) return
        val deadline = requireNotNull(round.endsAt)
        if (!now.isBefore(deadline)) {
            val completed = terminalRound(
                round.copy(activeElapsedMs = round.durationSeconds * 1000L),
                RoundStatus.COMPLETED, CompletionReason.ELAPSED, deadline,
            )
            publish(room.copy(round = completed, serverNow = now, revision = room.revision + 1))
        } else {
            val remaining = Duration.between(now, deadline).toMillis().coerceIn(0, round.durationSeconds * 1000L)
            publish(room.copy(
                serverNow = now,
                round = round.copy(remainingMs = remaining, activeElapsedMs = round.durationSeconds * 1000L - remaining),
            ))
        }
    }

    private fun terminalRound(round: Round, status: RoundStatus, reason: CompletionReason, endedAt: Instant) = round.copy(
        status = status, endsAt = null, remainingMs = 0, endedAt = endedAt, completionReason = reason,
    )

    private fun requireOwner(room: RoomSnapshot) {
        if (room.ownerId != state.value.currentUser.id) fail("FORBIDDEN", "Таймером управляет только создатель комнаты.")
    }

    private fun requireCurrentRound(room: RoomSnapshot, roundId: String): Round {
        requireOwner(room)
        val round = room.round
        if (round == null || round.id != roundId) fail("ROUND_NOT_CURRENT", "Этот раунд больше не является текущим.")
        return round
    }

    private fun RoomSnapshot.updateCurrentParticipant(change: (Participant) -> Participant): RoomSnapshot =
        copy(participants = participants.map { participant ->
            if (participant.userId == state.value.currentUser.id) change(participant) else participant
        }.frozen())

    private fun requireOwnTask(room: RoomSnapshot, taskId: String) {
        val owner = room.participants.firstOrNull { participant -> participant.tasks.any { it.id == taskId } }
            ?: fail("NOT_FOUND", "Задача не найдена.")
        if (owner.userId != state.value.currentUser.id) fail("FORBIDDEN", "Можно менять только свои задачи.")
    }

    private fun otherDemoUser(): User = if (state.value.currentUser.id == localUser.id) friendUser else localUser

    private fun friendParticipant(user: User = otherDemoUser()) = Participant(
        user.id, user.displayName, "собрать интерфейс",
        listOf(Task(newId(), "Сделать экран комнаты", true), Task(newId(), "Добавить таймер", false)).frozen(),
    )

    companion object {
        const val SAMPLE_ROOM_CODE = "FD-DEMO42"
        private val ACTIVE_STATUSES = setOf(RoundStatus.RUNNING, RoundStatus.PAUSED)

        private fun newId() = UUID.randomUUID().toString()
        private fun newCode(): String {
            val alphabet = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
            return "FD-" + (1..6).map { alphabet.random() }.joinToString("")
        }

        private fun validateDisplayName(name: String): String = name.trim().also {
            if (it.length !in 2..40) invalid("displayName", "Имя — от 2 до 40 символов.")
        }

        private fun validateTitle(title: String): String = title.trim().also {
            if (it.length !in 1..160) invalid("title", "Название задачи — от 1 до 160 символов.")
        }

        private fun validateDurations(focus: Int, pause: Int) {
            val errors = buildMap {
                if (focus !in 300..10800 || focus % 60 != 0) put("focusDurationSeconds", "Фокус — от 5 до 180 целых минут.")
                if (pause !in 60..3600 || pause % 60 != 0) put("breakDurationSeconds", "Перерыв — от 1 до 60 целых минут.")
            }
            if (errors.isNotEmpty()) throw RepositoryException("VALIDATION_ERROR", "Проверьте длительности.", errors)
        }

        private fun invalid(field: String, message: String): Nothing =
            throw RepositoryException("VALIDATION_ERROR", message, mapOf(field to message))

        private fun fail(code: String, message: String): Nothing = throw RepositoryException(code, message)

        private fun <T> List<T>.frozen(): List<T> = Collections.unmodifiableList(ArrayList(this))
    }
}

/** Demo elapsed time remains stable if the OS wall clock is corrected while a round is running. */
private class DemoMonotonicClock(
    private val zone: ZoneId = ZoneOffset.UTC,
    private val anchor: Instant = Instant.now(),
    private val anchorNanos: Long = System.nanoTime(),
) : Clock() {
    override fun getZone(): ZoneId = zone
    override fun withZone(zone: ZoneId): Clock = DemoMonotonicClock(zone, anchor, anchorNanos)
    override fun instant(): Instant = anchor.plusNanos((System.nanoTime() - anchorNanos).coerceAtLeast(0))
}
