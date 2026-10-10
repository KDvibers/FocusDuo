package dev.focusduo.data.remote

import dev.focusduo.domain.CompletionReason
import dev.focusduo.domain.RoomStatus
import dev.focusduo.domain.RoundKind
import dev.focusduo.domain.RoundStatus
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.time.Instant
import java.time.format.DateTimeParseException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Copies live in desktop resources so this build never needs a backend checkout. */
class ContractFixturesTest {
    @Test fun `auth session fixture preserves opaque token and UTC expiry`() {
        val session = roundTrip<AuthSessionDto>("auth-session.json")
        assertEquals("daniyar", session.user.toDomain().username)
        assertEquals("Данияр", session.user.toDomain().displayName)
        assertEquals("example-token-for-contract-tests-only", session.accessToken)
        assertEquals(Instant.parse("2026-10-09T16:00:00Z"), Instant.parse(session.expiresAt))
    }

    @Test fun `running fixture maps milliseconds without converting to seconds`() {
        val room = roundTrip<RoomSnapshotDto>("room-running.json").toDomain()
        val round = assertNotNull(room.round)
        assertEquals(RoomStatus.OPEN, room.status)
        assertEquals(17L, room.revision)
        assertEquals(2, room.participants.size)
        assertEquals("FD-7K9M2Q", room.code)
        assertEquals(RoundKind.FOCUS, round.kind)
        assertEquals(RoundStatus.RUNNING, round.status)
        assertEquals(1500, round.durationSeconds)
        assertEquals(1_122_000L, round.remainingMs)
        assertEquals(378_000L, round.activeElapsedMs)
        assertEquals(Instant.parse("2026-10-08T16:25:00Z"), round.endsAt)
        assertNull(round.endedAt)
        assertNull(round.completionReason)
    }

    @Test fun `paused fixture retains frozen milliseconds and null endsAt`() {
        val room = roundTrip<RoomSnapshotDto>("room-paused.json").toDomain()
        val round = assertNotNull(room.round)
        assertEquals(18L, room.revision)
        assertEquals(RoundStatus.PAUSED, round.status)
        assertNull(round.endsAt)
        assertEquals(1_122_000L, round.remainingMs)
        assertEquals(378_000L, round.activeElapsedMs)
    }

    @Test fun `resumed fixture preserves startedAt and moves only deadline`() {
        val resumed = roundTrip<RoomSnapshotDto>("room-resumed.json").toDomain()
        val original = FocusDuoJson.decodeFromString<RoomSnapshotDto>(fixture("room-running.json")).toDomain()
        val round = assertNotNull(resumed.round)
        assertEquals(19L, resumed.revision)
        assertEquals(RoundStatus.RUNNING, round.status)
        assertEquals(original.round!!.startedAt, round.startedAt)
        assertEquals(original.round.remainingMs, round.remainingMs)
        assertEquals(Instant.parse("2026-10-08T16:25:20Z"), round.endsAt)
    }

    @Test fun `completed fixture keeps terminal duration elapsed and task copies`() {
        val room = roundTrip<RoomSnapshotDto>("room-completed.json").toDomain()
        val round = assertNotNull(room.round)
        assertEquals(22L, room.revision)
        assertEquals(RoundStatus.COMPLETED, round.status)
        assertEquals(CompletionReason.ELAPSED, round.completionReason)
        assertEquals(0L, round.remainingMs)
        assertEquals(1_500_000L, round.activeElapsedMs)
        assertNull(round.endsAt)
        assertEquals(Instant.parse("2026-10-08T16:25:20Z"), round.endedAt)
        assertEquals(listOf(true, true, false), room.participants.first().tasks.map { it.completed })
    }

    @Test fun `current room fixture keeps nullable wrapper separate from room`() {
        val current = roundTrip<CurrentRoomDto>("room-current.json")
        val expected = FocusDuoJson.decodeFromString<RoomSnapshotDto>(fixture("room-running.json")).toDomain()
        assertEquals(expected, assertNotNull(current.room).toDomain())
    }

    @Test fun `websocket fixture is one full raw JSON room snapshot`() {
        val event = roundTrip<RoomEventDto>("room-event.json")
        assertEquals("ROOM_SNAPSHOT", event.type)
        val expected = FocusDuoJson.decodeFromString<RoomSnapshotDto>(fixture("room-running.json")).toDomain()
        assertEquals(expected, event.toDomain())
    }

    @Test fun `revision conflict fixture retains server snapshot and request id`() {
        val error = roundTrip<ApiErrorDto>("revision-conflict.json")
        assertEquals("REVISION_CONFLICT", error.code)
        assertEquals("50000000-0000-4000-8000-000000000001", error.requestId)
        assertTrue(error.fieldErrors.isEmpty())
        assertEquals(17L, assertNotNull(error.snapshot).toDomain().revision)
        assertTrue(error.message.contains("Повторите"))
    }

    @Test fun `history item fixture retains immutable goals and task counts`() {
        val item = roundTrip<HistoryItemDto>("history-item.json").toDomain()
        assertEquals(RoundStatus.COMPLETED, item.status)
        assertEquals(CompletionReason.ELAPSED, item.completionReason)
        assertEquals(RoundKind.FOCUS, item.kind)
        assertEquals(1_500_000L, item.activeElapsedMs)
        assertEquals("Закончить API", item.participants.first().goal)
        assertEquals(listOf(3, 2), item.participants.map { it.totalTaskCount })
        assertEquals(listOf(2, 2), item.participants.map { it.completedTaskCount })
    }

    @Test fun `history fixture maps server list without reordering`() {
        val history = roundTrip<HistoryDto>("history.json")
        val item = FocusDuoJson.decodeFromString<HistoryItemDto>(fixture("history-item.json")).toDomain()
        assertEquals(listOf(item), history.items.map(HistoryItemDto::toDomain))
    }

    @Test fun `new room and absent current room allow explicit nulls`() {
        assertNull(FocusDuoJson.decodeFromString<CurrentRoomDto>("""{"room":null}""").room)
        assertEquals("""{"room":null}""", FocusDuoJson.encodeToString(CurrentRoomDto(null)))
        val room = FocusDuoJson.decodeFromString<RoomSnapshotDto>(fixture("room-running.json"))
        assertNull(room.copy(round = null).toDomain().round)
    }

    @Test fun `forward compatible fields do not hide required field omissions`() {
        val user = FocusDuoJson.decodeFromString<UserDto>(
            """{"id":"10000000-0000-4000-8000-000000000001","username":"anna","displayName":"Анна","futureField":true}""",
        )
        assertEquals("Анна", user.toDomain().displayName)
        assertFailsWith<SerializationException> {
            FocusDuoJson.decodeFromString<UserDto>("""{"id":"10000000-0000-4000-8000-000000000001","username":"anna"}""")
        }
    }

    @Test fun `malformed dates and unsupported enum values fail mapping`() {
        val room = FocusDuoJson.decodeFromString<RoomSnapshotDto>(fixture("room-running.json"))
        assertFailsWith<DateTimeParseException> { room.copy(serverNow = "yesterday").toDomain() }
        assertFailsWith<IllegalArgumentException> { room.copy(status = "UNKNOWN").toDomain() }
        val round = assertNotNull(room.round)
        assertFailsWith<DateTimeParseException> { round.copy(endsAt = "invalid").toDomain() }
        assertFailsWith<IllegalArgumentException> { round.copy(kind = "LUNCH").toDomain() }
        assertFailsWith<IllegalArgumentException> { round.copy(status = "READY").toDomain() }
        assertFailsWith<IllegalArgumentException> { round.copy(completionReason = "UNKNOWN").toDomain() }
    }

    @Test fun `nonterminal history and unknown room event cannot become domain data`() {
        val history = FocusDuoJson.decodeFromString<HistoryItemDto>(fixture("history-item.json"))
        assertFailsWith<IllegalArgumentException> { history.copy(status = "RUNNING").toDomain() }
        assertFailsWith<DateTimeParseException> { history.copy(endedAt = "invalid").toDomain() }
        val event = FocusDuoJson.decodeFromString<RoomEventDto>(fixture("room-event.json"))
        assertFailsWith<IllegalArgumentException> { event.copy(type = "TASK_PATCH").toDomain() }
    }

    @Test fun `long revisions and elapsed milliseconds do not overflow int`() {
        val room = FocusDuoJson.decodeFromString<RoomSnapshotDto>(fixture("room-running.json"))
        val largeRevision = Int.MAX_VALUE.toLong() + 1
        val largeElapsed = Int.MAX_VALUE.toLong() + 42
        val dto = room.copy(revision = largeRevision, round = room.round!!.copy(activeElapsedMs = largeElapsed))
        val restored = FocusDuoJson.decodeFromString<RoomSnapshotDto>(FocusDuoJson.encodeToString(dto)).toDomain()
        assertEquals(largeRevision, restored.revision)
        assertEquals(largeElapsed, restored.round!!.activeElapsedMs)
    }

    @Test fun `malformed or abbreviated UUIDs cannot become domain identifiers`() {
        val room = FocusDuoJson.decodeFromString<RoomSnapshotDto>(fixture("room-running.json"))
        assertFailsWith<IllegalArgumentException> { room.copy(roomId = "../auth/logout").toDomain() }
        assertFailsWith<IllegalArgumentException> { room.copy(ownerId = "1-1-1-1-1").toDomain() }
        assertFailsWith<IllegalArgumentException> { room.participants.first().copy(userId = "invalid").toDomain() }
        assertFailsWith<IllegalArgumentException> { room.participants.first().tasks.first().copy(id = "").toDomain() }
        assertFailsWith<IllegalArgumentException> { room.round!!.copy(id = "not-a-uuid").toDomain() }
        assertFailsWith<IllegalArgumentException> { UserDto("invalid", "anna", "Анна").toDomain() }
        val history = FocusDuoJson.decodeFromString<HistoryItemDto>(fixture("history-item.json"))
        assertFailsWith<IllegalArgumentException> { history.copy(roundId = "invalid").toDomain() }
        assertFailsWith<IllegalArgumentException> { history.copy(roomId = "invalid").toDomain() }
        assertFailsWith<IllegalArgumentException> { history.participants.first().copy(userId = "invalid").toDomain() }
        assertEquals(
            "abcdef00-0000-4000-8000-000000000001",
            UserDto("ABCDEF00-0000-4000-8000-000000000001", "anna", "Анна").toDomain().id,
        )
    }

    @Test fun `request JSON preserves password exactly while string output redacts secrets`() {
        val password = "  Password1!  "
        val login = LoginRequestDto("anna", password)
        val registration = RegisterRequestDto("anna", "Анна", password)
        val expectedLogin = """{"username":"anna","password":"  Password1!  "}"""
        assertEquals(expectedLogin, FocusDuoJson.encodeToString(login))
        assertEquals(password, FocusDuoJson.decodeFromString<RegisterRequestDto>(FocusDuoJson.encodeToString(registration)).password)
        assertFalse(login.toString().contains(password))
        assertFalse(registration.toString().contains(password))
        val session = FocusDuoJson.decodeFromString<AuthSessionDto>(fixture("auth-session.json"))
        assertFalse(session.toString().contains(session.accessToken))
    }

    private inline fun <reified T> roundTrip(name: String): T {
        val original = fixture(name)
        val decoded = FocusDuoJson.decodeFromString<T>(original)
        val encoded = FocusDuoJson.encodeToString(decoded)
        assertEquals(FocusDuoJson.parseToJsonElement(original), FocusDuoJson.parseToJsonElement(encoded), name)
        return decoded
    }

    private fun fixture(name: String): String = checkNotNull(javaClass.getResourceAsStream("/contracts/$name")) {
        "Missing standalone desktop contract fixture: $name"
    }.bufferedReader(Charsets.UTF_8).use { it.readText() }
}
