@file:OptIn(io.ktor.utils.io.InternalAPI::class)

package dev.focusduo.smoke.expiredfocus

import dev.focusduo.data.RealConnectionStatus
import dev.focusduo.data.RealRepository
import dev.focusduo.data.RealSessionStatus
import dev.focusduo.data.remote.FocusDuoJson
import dev.focusduo.data.remote.RoomSnapshotDto
import dev.focusduo.data.remote.toDomain
import dev.focusduo.domain.CompletionReason
import dev.focusduo.domain.HistoryItem
import dev.focusduo.domain.HistoryParticipant
import dev.focusduo.domain.RoomSnapshot
import dev.focusduo.domain.RoomStatus
import dev.focusduo.domain.RoundKind
import dev.focusduo.domain.RoundStatus
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.HttpRequestPipeline
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import java.net.URI
import java.net.http.HttpClient as JdkHttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.exitProcess

/** Optional external observation gates. They do not replace transport or timer behavior. */
data class ExpiredFocusHooks(
    val onReady: suspend (RealRepository, RealRepository) -> Unit = { _, _ -> },
    val onExpiredOffline: suspend () -> Unit = {},
    val onRecovered: suspend () -> Unit = {},
)

/** Writes only synthetic IDs, timings, counters and named results; never HTTP bodies or credentials. */
private class Evidence(private val file: Path) {
    private val events = mutableListOf<JsonObject>()
    init {
        Files.writeString(file, "{\"kind\":\"focusduo-expired-focus-v1\",\"events\":[]}",
            StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
    }

    fun record(phase: String, details: JsonObjectBuilder.() -> Unit = {}) {
        val now = Instant.now().toString()
        events += buildJsonObject { put("phase", phase); put("at", now); details() }
        val temporary = file.resolveSibling("${file.fileName}.${UUID.randomUUID()}.tmp")
        try {
            Files.writeString(temporary, buildJsonObject {
                put("kind", "focusduo-expired-focus-v1")
                put("events", JsonArray(events.toList()))
            }.toString(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary) }
        println("PHASE $phase $now")
    }
}

/** This harness is the only command writer while it runs. The controller owns Java and PostgreSQL. */
private class RestartControl(val directory: Path) {
    init {
        require(directory.isAbsolute && Files.isDirectory(directory)) { "Absolute control directory required" }
        verifyMarker()
    }

    private fun verifyMarker() {
        val marker = FocusDuoJson.parseToJsonElement(Files.readString(directory.resolve("marker.json"))).jsonObject
        require(marker["kind"]?.jsonPrimitive?.content == "focusduo-isolated-restart-v1") { "Isolated controller required" }
        require(marker["stopped"]?.jsonPrimitive?.booleanOrNull != true) { "Controller has stopped" }
    }

    suspend fun command(action: String) = withTimeout(60_000) {
        require(action == "stop" || action == "start")
        verifyMarker()
        val id = UUID.randomUUID().toString()
        val temporary = directory.resolve("commands.$id.tmp")
        try {
            Files.writeString(temporary, buildJsonObject { put("id", id); put("action", action) }.toString(),
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
            Files.move(temporary, directory.resolve("commands.json"),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary) }
        while (true) {
            val resultFile = directory.resolve("result.json")
            if (Files.isRegularFile(resultFile)) {
                val result = FocusDuoJson.parseToJsonElement(Files.readString(resultFile)).jsonObject
                if (result["id"]?.jsonPrimitive?.content == id) {
                    check(result["status"]?.jsonPrimitive?.content == "ok") { "Controller command failed" }
                    break
                }
            }
            delay(100)
        }
    }
}

/** Real production CIO, with an in-memory mutation counter and own token retention for cleanup only. */
private class ObservedCio(private val cio: HttpClientEngine = CIO.create()) : HttpClientEngine by cio {
    @Volatile private var bearer: String? = null
    val mutations = AtomicInteger()
    fun token(): String? = bearer
    override fun install(client: HttpClient) {
        client.requestPipeline.intercept(HttpRequestPipeline.Before) {
            if (context.method.value !in setOf("GET", "HEAD", "OPTIONS")) mutations.incrementAndGet()
            context.headers[HttpHeaders.Authorization]?.let { value ->
                check(value.startsWith("Bearer ")) { "Unexpected authorization scheme" }
                bearer = value.removePrefix("Bearer ")
            }
        }
        cio.install(client)
    }
    fun forget() { bearer = null }
}

private class Smoke(
    private val baseUrl: String,
    private val control: RestartControl,
    private val evidence: Evidence,
    private val hooks: ExpiredFocusHooks,
) : AutoCloseable {
    private val http = JdkHttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
        .followRedirects(JdkHttpClient.Redirect.NEVER).build()
    private val repositories = mutableListOf<RealRepository>()
    private val engines = mutableListOf<ObservedCio>()
    private var roomId: String? = null
    private var ownerId: String? = null
    private var roomCreationAttempted = false
    private var serverMayBeDown = false

    suspend fun run() {
        evidence.record("SETUP_STARTED")
        val suffix = UUID.randomUUID().toString().replace("-", "").take(20)
        val owner = keep(RealRepository.register(baseUrl, "expired_a_$suffix", "Expired owner",
            UUID.randomUUID().toString() + "Я!", observe()))
        owner.bootstrap()
        ownerId = owner.state.value.currentUser.id
        val peer = keep(RealRepository.register(baseUrl, "expired_b_$suffix", "Expired peer",
            UUID.randomUUID().toString() + "Я!", observe()))
        peer.bootstrap()
        check(owner.loadHistory().isEmpty() && peer.loadHistory().isEmpty()) { "New users have history" }
        roomCreationAttempted = true
        val created = owner.createRoom(300, 60)
        roomId = created.roomId
        val joined = peer.joinRoom(created.code)
        awaitReady(owner, joined)
        awaitReady(peer, joined)
        awaitReady(peer, owner.updateGoal("Сохранить результат после истечения без Java"))
        awaitReady(owner, peer.updateGoal("Проверить неизменяемую историю"))
        val withOwnerTask = owner.addTask("Задача владельца до срока")
        awaitReady(peer, withOwnerTask)
        val ownerTaskId = withOwnerTask.participants.first { it.userId == ownerId }.tasks.single().id
        awaitReady(peer, owner.updateTask(ownerTaskId, completed = true))
        val withPeerTask = peer.addTask("Исходная задача партнёра")
        awaitReady(owner, withPeerTask)
        val peerTaskId = withPeerTask.participants.first { it.userId != ownerId }.tasks.single().id
        evidence.record("ROOM_READY") { put("roomId", created.roomId); put("roomCode", created.code) }
        val started = owner.startRound(RoundKind.FOCUS)
        awaitReady(owner, started)
        awaitReady(peer, started)
        val round = checkNotNull(started.round)
        val deadline = checkNotNull(round.endsAt)
        check(round.kind == RoundKind.FOCUS && round.status == RoundStatus.RUNNING && round.durationSeconds == 300)
        check(Duration.between(round.startedAt, deadline) == Duration.ofSeconds(300)) { "Unexpected focus deadline" }
        evidence.record("RUNNING_READY") {
            put("roomId", started.roomId); put("roomCode", started.code); put("ownerId", started.ownerId)
            put("peerId", peer.state.value.currentUser.id); put("roundId", round.id)
            put("ownerUsername", owner.state.value.currentUser.username)
            put("peerUsername", peer.state.value.currentUser.username)
            put("startedAt", round.startedAt.toString()); put("endsAt", deadline.toString())
            put("originalEndsAt", deadline.toString())
            put("revision", started.revision); put("durationSeconds", 300)
        }
        hooks.onReady(owner, peer)
        check(deadline.isAfter(Instant.now().plusSeconds(60))) { "Insufficient time to stop Java before expiry" }
        val ownerVersion = owner.recoveryVersion.value
        val peerVersion = peer.recoveryVersion.value
        val mutationsBefore = engines.map { it.mutations.get() }
        serverMayBeDown = true // Includes the uncertain outcome of a lost stop acknowledgement.
        control.command("stop")
        val stoppedAt = Instant.now()
        check(stoppedAt.isBefore(deadline)) { "Java was not stopped before the deadline" }
        evidence.record("JAVA_STOPPED") { put("endsAt", deadline.toString()) }
        awaitDisconnected(owner)
        awaitDisconnected(peer)
        evidence.record("CLIENTS_OFFLINE")
        waitPastDeadline(owner, peer, started, deadline.plusSeconds(2))
        check(engines.map { it.mutations.get() } == mutationsBefore) { "Offline recovery sent a mutation" }
        evidence.record("OFFLINE_DEADLINE_PASSED") {
            put("endsAt", deadline.toString())
            put("ownerProjectedRemainingMs", owner.projectedRoom()!!.round!!.remainingMs)
            put("peerProjectedRemainingMs", peer.projectedRoom()!!.round!!.remainingMs)
            put("authoritativeStatus", "RUNNING"); put("localHistoryCount", 0)
        }
        println("PASS offline deadline: both clients keep RUNNING and empty history while the display reaches zero")
        hooks.onExpiredOffline()
        assertOfflineProjection(owner, started)
        assertOfflineProjection(peer, started)

        evidence.record("JAVA_START_REQUESTED")
        control.command("start")
        serverMayBeDown = false
        evidence.record("JAVA_READY")
        // No reconnect, refresh, bootstrap or recoverAfterResume is called after the initial setup.
        val completedOwner = awaitReady(owner, started, ownerVersion, completed = true)
        val completedPeer = awaitReady(peer, started, peerVersion, completed = true)
        assertCompleted(completedOwner, started)
        assertCompleted(completedPeer, started)
        check(engines.map { it.mutations.get() } == mutationsBefore) { "Automatic recovery sent a mutation" }
        evidence.record("AUTO_RECOVERY_COMPLETED") {
            put("roomId", started.roomId); put("roundId", round.id); put("revision", completedOwner.revision)
            put("startedAt", completedOwner.round!!.startedAt.toString())
            put("endedAt", completedOwner.round!!.endedAt.toString())
            put("status", "COMPLETED"); put("completionReason", "ELAPSED")
            put("endsAt", JsonNull); put("remainingMs", 0); put("activeElapsedMs", 300_000)
            put("ownerRecoveryBefore", ownerVersion); put("ownerRecoveryAfter", owner.recoveryVersion.value)
            put("peerRecoveryBefore", peerVersion); put("peerRecoveryAfter", peer.recoveryVersion.value)
        }
        println("PASS auto recovery: both clients receive COMPLETED/ELAPSED at the original deadline after Java restart")

        val expectedHistory = HistoryItem(round.id, started.roomId, RoundKind.FOCUS, RoundStatus.COMPLETED,
            CompletionReason.ELAPSED, 300, round.startedAt, deadline, 300_000,
            started.participants.map { HistoryParticipant(it.userId, it.displayName, it.goal, it.tasks,
                it.tasks.size, it.tasks.count { task -> task.completed }) })
        assertHistory(owner, peer, expectedHistory)
        evidence.record("HISTORY_CONFIRMED") { put("roundId", round.id); put("itemsPerUser", 1) }
        println("PASS history: exactly one equal persisted ELAPSED result with the original goals and tasks for each user")
        hooks.onRecovered()

        val changed = peer.updateTask(peerTaskId, title = "Правка после истечения", completed = true)
        awaitReady(owner, changed)
        check(changed.participants.first { it.userId != ownerId }.tasks.single().title == "Правка после истечения")
        assertHistory(owner, peer, expectedHistory)
        evidence.record("IMMUTABLE_HISTORY_CONFIRMED") { put("roundId", round.id); put("roomRevision", changed.revision) }
        println("PASS immutable history: a later task edit does not change either saved result")

        val closed = peer.closeRoom()
        withTimeout(60_000) {
            combine(owner.state, owner.connectionState) { state, connection ->
                state.room?.takeIf { it.status == RoomStatus.CLOSED && connection.status == RealConnectionStatus.NONE }
            }.filterNotNull().first()
        }
        check(closed.status == RoomStatus.CLOSED && !owner.canMutate && !peer.canMutate)
        assertHistory(owner, peer, expectedHistory)
        roomCreationAttempted = false
        owner.logout()
        peer.logout()
        evidence.record("SCENARIO_COMPLETE") { put("roomId", started.roomId); put("itemsPerUser", 1) }
        println("PASS cleanup: own room is closed, terminal history unchanged and both sessions logged out")
    }

    private suspend fun waitPastDeadline(owner: RealRepository, peer: RealRepository, expected: RoomSnapshot, until: Instant) =
        coroutineScope {
            // Observe every emitted state in addition to sampling the production countdown projection.
            val monitors = listOf(owner, peer).map { repository ->
                launch(start = CoroutineStart.UNDISPATCHED) {
                    combine(repository.state, repository.connectionState, repository.sessionStatus) { state, connection, session ->
                        check(state.room?.roomId == expected.roomId && state.room?.round?.id == expected.round!!.id)
                        check(state.room?.round?.status == RoundStatus.RUNNING && state.room?.round?.completionReason == null &&
                            state.room?.round?.endedAt == null && state.history.isEmpty()) { "Client invented an offline result" }
                        check(connection.status != RealConnectionStatus.CONNECTED && session == RealSessionStatus.ACTIVE &&
                            !repository.canMutate) { "Offline client permits mutation or lost its session" }
                    }.collect()
                }
            }
            try {
                var nextProgress = Instant.now()
                while (Instant.now().isBefore(until)) {
                    assertOfflineProjection(owner, expected)
                    assertOfflineProjection(peer, expected)
                    val now = Instant.now()
                    if (!now.isBefore(nextProgress)) {
                        evidence.record("WAITING_WITH_JAVA_OFF") { put("waitUntil", until.toString()) }
                        nextProgress = now.plusSeconds(30)
                    }
                    delay(Duration.between(now, until).toMillis().coerceIn(1, 1_000))
                }
                assertOfflineProjection(owner, expected)
                assertOfflineProjection(peer, expected)
                check(owner.projectedRoom()!!.round!!.remainingMs == 0L && peer.projectedRoom()!!.round!!.remainingMs == 0L) {
                    "Projected countdown did not reach zero after the real deadline"
                }
            } finally { monitors.forEach { it.cancelAndJoin() } }
        }

    private fun assertOfflineProjection(repository: RealRepository, expected: RoomSnapshot) {
        val projected = checkNotNull(repository.projectedRoom())
        val round = checkNotNull(projected.round)
        check(projected.roomId == expected.roomId && projected.revision == expected.revision &&
            projected.participants == expected.participants && round.id == expected.round!!.id &&
            round.status == RoundStatus.RUNNING && round.completionReason == null && round.endedAt == null &&
            round.endsAt == expected.round!!.endsAt && round.remainingMs >= 0 && repository.state.value.history.isEmpty()) {
            "Offline display changed authoritative round data or history"
        }
    }

    private fun assertCompleted(actual: RoomSnapshot, expected: RoomSnapshot) {
        val previous = checkNotNull(expected.round)
        val terminal = previous.copy(status = RoundStatus.COMPLETED, endsAt = null, remainingMs = 0,
            activeElapsedMs = 300_000, endedAt = previous.endsAt, completionReason = CompletionReason.ELAPSED)
        check(actual == expected.copy(revision = expected.revision + 1, serverNow = actual.serverNow, round = terminal)) {
            "Recovered round identity, deadline, time, revision, membership, goals or tasks is incorrect"
        }
    }

    private suspend fun assertHistory(owner: RealRepository, peer: RealRepository, expected: HistoryItem) {
        check(owner.loadHistoryItem(expected.roundId) == expected && peer.loadHistoryItem(expected.roundId) == expected) {
            "History detail differs from the saved round snapshot"
        }
        check(owner.loadHistory() == listOf(expected) && peer.loadHistory() == listOf(expected)) { "History duplicated or changed" }
    }

    private suspend fun awaitDisconnected(repository: RealRepository) = withTimeout(60_000) {
        combine(repository.connectionState, repository.sessionStatus) { connection, session ->
            session == RealSessionStatus.ACTIVE && connection.status != RealConnectionStatus.CONNECTED && !repository.canMutate
        }.first { it }
    }

    private suspend fun awaitReady(repository: RealRepository, expected: RoomSnapshot, previousRecovery: Long? = null,
                                   completed: Boolean = false): RoomSnapshot = withTimeout(90_000) {
        combine(repository.state, repository.connectionState, repository.sessionStatus, repository.recoveryVersion) {
                state, connection, session, recovery ->
            state.room?.takeIf { it.roomId == expected.roomId && it.status == RoomStatus.OPEN &&
                it.revision >= expected.revision && connection.status == RealConnectionStatus.CONNECTED &&
                session == RealSessionStatus.ACTIVE && repository.canMutate &&
                (previousRecovery == null || recovery > previousRecovery) &&
                (!completed || it.round?.status == RoundStatus.COMPLETED) }
        }.filterNotNull().first()
    }

    private fun observe() = ObservedCio().also { engines += it }
    private fun keep(repository: RealRepository) = repository.also { repositories += it }

    private fun request(method: String, path: String, token: String, revision: Long? = null): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI.create("$baseUrl/api/v1$path"))
            .timeout(Duration.ofSeconds(3)).header("Authorization", "Bearer $token").header("Accept", "application/json")
        if (revision != null) builder.header("X-Room-Revision", revision.toString())
            .header("Idempotency-Key", UUID.randomUUID().toString())
        return http.send(builder.method(method, HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString())
    }

    suspend fun cleanup(): Boolean {
        var clean = true
        if (serverMayBeDown) {
            try { control.command("start"); serverMayBeDown = false }
            catch (_: Exception) { clean = false; System.err.println("FAIL cleanup: controller readiness unconfirmed") }
        }
        val tokens = engines.mapNotNull { it.token() }.distinct()
        val ownerToken = engines.firstOrNull()?.token()
        if (roomCreationAttempted) {
            var handled = false
            for (token in tokens) {
                try {
                    val response = request("GET", "/rooms/current", token)
                    if (response.statusCode() == 401) continue
                    check(response.statusCode() == 200)
                    val value = checkNotNull(FocusDuoJson.parseToJsonElement(response.body()).jsonObject["room"])
                    if (value == JsonNull) { if (token == ownerToken) handled = true; continue }
                    val room = FocusDuoJson.decodeFromJsonElement<RoomSnapshotDto>(value).toDomain()
                    check(room.ownerId == ownerId && (roomId == null || room.roomId == roomId)) { "Unrelated room refused" }
                    if (room.status == RoomStatus.OPEN) check(request("POST", "/rooms/${room.roomId}/close", token, room.revision).statusCode() == 200)
                    handled = true
                    break
                } catch (_: Exception) { /* A second own token can still finish bounded cleanup. */ }
            }
            if (!handled) { clean = false; System.err.println("FAIL cleanup: own room closure unconfirmed") }
        }
        for (token in tokens) {
            try { check(request("POST", "/auth/logout", token).statusCode() in setOf(204, 401)) }
            catch (_: Exception) { clean = false; System.err.println("FAIL cleanup: session revocation unconfirmed") }
        }
        repositories.forEach { it.close() }
        engines.forEach { it.close(); it.forget() }
        return clean
    }

    override fun close() {
        repositories.forEach { it.close() }
        engines.forEach { it.close(); it.forget() }
        http.shutdownNow()
    }
}

suspend fun runExpiredFocusSmoke(hooks: ExpiredFocusHooks = ExpiredFocusHooks()) {
    var failure: Throwable? = null
    var smoke: Smoke? = null
    var evidence: Evidence? = null
    try {
        val base = System.getProperty("focusduo.smoke.baseUrl", "http://127.0.0.1:8090").trimEnd('/')
        val uri = URI.create(base)
        require(uri.scheme == "http" && uri.host in setOf("127.0.0.1", "localhost") && uri.port == 8090 &&
            uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null && uri.path.isEmpty())
        val directory = System.getProperty("focusduo.smoke.controlDir", "").trim()
        require(directory.isNotEmpty()) { "Absolute focusduo.smoke.controlDir required" }
        val control = RestartControl(Path.of(directory).normalize())
        evidence = Evidence(control.directory.resolve("expired-focus-evidence.json"))
        smoke = Smoke(base, control, evidence, hooks)
        withTimeout(1_080_000) { smoke.run() }
    } catch (caught: Throwable) {
        // Exception text may contain request data. Print only its class and a fixed label.
        System.err.println("FAIL live Kotlin expired focus: ${caught.javaClass.simpleName}")
        try { evidence?.record("SCENARIO_FAILED") { put("errorType", caught.javaClass.simpleName) } } catch (_: Exception) { }
        failure = caught
    } finally {
        smoke?.let {
            // An observation window may be closed; cancellation still permits bounded own-resource cleanup.
            withContext(NonCancellable) {
                try {
                    val clean = withTimeout(90_000) { it.cleanup() }
                    evidence?.record("CLEANUP_FINISHED") { put("confirmed", clean) }
                    check(clean) { "Own-resource cleanup unconfirmed" }
                } catch (caught: Throwable) {
                    System.err.println("FAIL cleanup: ${caught.javaClass.simpleName}")
                    if (failure == null) failure = caught
                } finally { it.close() }
            }
        }
    }
    failure?.let { throw it }
    evidence?.record("PASS")
    println("PASS FocusDuo RealRepository live expired FOCUS integration")
}

fun main() {
    val result = try {
        runBlocking { runExpiredFocusSmoke() }
        0
    } catch (failure: Throwable) {
        // No throwable message or stack trace, including when cleanup itself failed.
        System.err.println("FAIL expired FOCUS runner: ${failure.javaClass.simpleName}")
        if (failure is CancellationException) 130 else 1
    }
    exitProcess(result)
}
