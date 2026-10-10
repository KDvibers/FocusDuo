@file:OptIn(io.ktor.utils.io.InternalAPI::class)

package dev.focusduo.smoke.restart

import dev.focusduo.data.RealConnectionStatus
import dev.focusduo.data.RealRepository
import dev.focusduo.data.RealSessionStatus
import dev.focusduo.data.remote.FocusDuoJson
import dev.focusduo.data.remote.RoomSnapshotDto
import dev.focusduo.data.remote.toDomain
import dev.focusduo.domain.CompletionReason
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.exitProcess

/** File commands address a separately managed, disposable Java process only. */
private class RestartControl(private val directory: Path) {
    init {
        require(directory.isAbsolute && Files.isDirectory(directory)) { "Absolute control directory required" }
        verifyMarker()
    }

    private fun verifyMarker() {
        val marker = FocusDuoJson.parseToJsonElement(Files.readString(directory.resolve("marker.json"))).jsonObject
        require(marker["kind"]?.jsonPrimitive?.content == "focusduo-isolated-restart-v1") { "Isolated controller marker required" }
        require(marker["stopped"]?.jsonPrimitive?.booleanOrNull != true) { "Controller has already stopped" }
    }

    suspend fun command(action: String) = withTimeout(60_000) {
        require(action == "start" || action == "stop")
        verifyMarker()
        val id = UUID.randomUUID().toString()
        val temporary = directory.resolve("commands.$id.tmp")
        try {
            Files.writeString(temporary, buildJsonObject { put("id", id); put("action", action) }.toString(),
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
            Files.move(temporary, directory.resolve("commands.json"),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary) }
        val resultFile = directory.resolve("result.json")
        while (true) {
            if (Files.isRegularFile(resultFile)) {
                val result = FocusDuoJson.parseToJsonElement(Files.readString(resultFile)).jsonObject
                if (result["id"]?.jsonPrimitive?.content == id) {
                    check(result["status"]?.jsonPrimitive?.content == "ok") { "Controller command failed" }
                    break
                }
            }
            // Only the external controller handshake uses polling; repository waits use StateFlow.
            delay(100)
        }
    }
}

/** Delegates all transport to production CIO; credentials are kept only for bounded cleanup. */
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

private class Smoke(private val baseUrl: String, private val control: RestartControl) : AutoCloseable {
    private val http = JdkHttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
        .followRedirects(JdkHttpClient.Redirect.NEVER).build()
    private val repositories = mutableListOf<RealRepository>()
    private val engines = mutableListOf<ObservedCio>()
    private var roomId: String? = null
    private var ownerId: String? = null
    private var roomCreationAttempted = false
    private var serverMayBeDown = false

    suspend fun run() {
        val suffix = UUID.randomUUID().toString().replace("-", "").take(20)
        val ownerEngine = observe()
        val owner = keep(RealRepository.register(baseUrl, "restart_a_$suffix", "Restart owner",
            UUID.randomUUID().toString() + "Я!", ownerEngine))
        owner.bootstrap()
        ownerId = owner.state.value.currentUser.id
        val peerEngine = observe()
        val peer = keep(RealRepository.register(baseUrl, "restart_b_$suffix", "Restart peer",
            UUID.randomUUID().toString() + "Я!", peerEngine))
        peer.bootstrap()
        roomCreationAttempted = true
        val created = owner.createRoom(900, 60)
        roomId = created.roomId
        val joined = peer.joinRoom(created.code)
        awaitReady(owner, joined)
        awaitReady(peer, joined)
        check(joined.participants.size == 2) { "Room does not have two participants" }
        val withTask = owner.addTask("Сохранить задачу после перезапуска")
        awaitReady(peer, withTask)
        val taskId = withTask.participants.first { it.userId == ownerId }.tasks.single().id
        val checked = owner.updateTask(taskId, completed = true)
        awaitReady(peer, checked)
        val started = owner.startRound(RoundKind.FOCUS)
        awaitReady(peer, started)
        val roundId = checkNotNull(started.round).id
        val paused = owner.pauseRound(roundId)
        samePaused(awaitReady(owner, paused), paused)
        samePaused(awaitReady(peer, paused), paused)

        restartAndRecover(owner, peer, paused, running = false)
        println("PASS paused restart: both clients recover automatically with the same round, task, revision and frozen time")

        val resumed = owner.resumeRound(roundId)
        sameRunning(awaitReady(owner, resumed), resumed)
        sameRunning(awaitReady(peer, resumed), resumed)
        check(resumed.round!!.startedAt == paused.round!!.startedAt) { "Resume changed round start" }
        restartAndRecover(owner, peer, resumed, running = true)
        println("PASS running restart: both clients recover automatically with the original deadline and membership")

        val ownerVersion = owner.recoveryVersion.value
        val peerVersion = peer.recoveryVersion.value
        val mutationCounts = ownerEngine.mutations.get() to peerEngine.mutations.get()
        coroutineScope {
            val ownerSync = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) { awaitSync(owner) }
            val peerSync = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) { awaitSync(peer) }
            owner.recoverAfterResume()
            peer.recoverAfterResume()
            ownerSync.await()
            peerSync.await()
        }
        sameRunning(awaitReady(owner, resumed, ownerVersion), resumed)
        sameRunning(awaitReady(peer, resumed, peerVersion), resumed)
        check(mutationCounts == (ownerEngine.mutations.get() to peerEngine.mutations.get())) {
            "Resume synchronization sent a mutation"
        }
        println("PASS resume signal: explicit recoverAfterResume performs fresh REST/WS synchronization without a mutation")

        val completed = owner.finishRound(roundId)
        awaitReady(peer, completed)
        check(completed.round!!.status == RoundStatus.COMPLETED &&
            completed.round!!.completionReason == CompletionReason.MANUAL) { "Manual finish was not preserved" }
        val ownerResult = owner.loadHistoryItem(roundId)
        val peerResult = peer.loadHistoryItem(roundId)
        check(ownerResult == peerResult) { "History differs between participants" }
        check(ownerResult.participants.first { it.userId == ownerId }.completedTaskCount == 1) { "History lost the task" }
        val ownerHistory = owner.loadHistory()
        val peerHistory = peer.loadHistory()
        check(ownerHistory.size == 1 && peerHistory.size == 1 &&
            ownerHistory.single() == ownerResult && peerHistory.single() == ownerResult) { "History duplicated or changed" }
        println("PASS history: one equal persisted result for both clients after both server restarts")

        val closed = peer.closeRoom()
        withTimeout(60_000) {
            combine(owner.state, owner.connectionState) { state, connection ->
                state.room?.takeIf { it.status == RoomStatus.CLOSED && connection.status == RealConnectionStatus.NONE }
            }.filterNotNull().first()
        }
        check(closed.status == RoomStatus.CLOSED && !owner.canMutate && !peer.canMutate) { "Close did not block mutations" }
        roomCreationAttempted = false
        owner.logout()
        peer.logout()
        println("PASS cleanup: own room closed and both sessions logged out")
    }

    private suspend fun restartAndRecover(owner: RealRepository, peer: RealRepository, expected: RoomSnapshot, running: Boolean) {
        val ownerVersion = owner.recoveryVersion.value
        val peerVersion = peer.recoveryVersion.value
        val mutationsBefore = engines.map { it.mutations.get() }
        serverMayBeDown = true // A lost controller acknowledgement leaves the process state unknown.
        control.command("stop")
        awaitDisconnected(owner)
        awaitDisconnected(peer)
        control.command("start")
        serverMayBeDown = false
        val ownerRoom = awaitReady(owner, expected, ownerVersion)
        val peerRoom = awaitReady(peer, expected, peerVersion)
        check(engines.map { it.mutations.get() } == mutationsBefore) { "Automatic recovery sent a modifying REST command" }
        if (running) {
            sameRunning(ownerRoom, expected)
            sameRunning(peerRoom, expected)
        } else {
            samePaused(ownerRoom, expected)
            samePaused(peerRoom, expected)
        }
    }

    private suspend fun awaitDisconnected(repository: RealRepository) = withTimeout(60_000) {
        combine(repository.connectionState, repository.sessionStatus) { connection, session ->
            session == RealSessionStatus.ACTIVE && connection.status != RealConnectionStatus.CONNECTED && !repository.canMutate
        }.first { it }
    }

    private suspend fun awaitSync(repository: RealRepository) = withTimeout(60_000) {
        repository.connectionState.first {
            it.status == RealConnectionStatus.CONNECTING || it.status == RealConnectionStatus.SYNCHRONIZING
        }
    }

    private suspend fun awaitReady(repository: RealRepository, expected: RoomSnapshot, previousRecovery: Long? = null): RoomSnapshot =
        withTimeout(60_000) {
            combine(repository.state, repository.connectionState, repository.sessionStatus, repository.recoveryVersion) {
                    state, connection, session, recovery ->
                state.room?.takeIf { it.roomId == expected.roomId && it.status == RoomStatus.OPEN &&
                    it.revision >= expected.revision && connection.status == RealConnectionStatus.CONNECTED &&
                    session == RealSessionStatus.ACTIVE && repository.canMutate &&
                    (previousRecovery == null || recovery > previousRecovery) }
            }.filterNotNull().first()
        }

    private fun samePaused(actual: RoomSnapshot, expected: RoomSnapshot) {
        check(actual.round?.status == RoundStatus.PAUSED && actual.round?.endsAt == null) { "Paused round changed state" }
        check(actual.copy(serverNow = expected.serverNow) == expected) { "Paused round or room did not survive restart" }
    }

    private fun sameRunning(actual: RoomSnapshot, expected: RoomSnapshot) {
        val round = checkNotNull(actual.round)
        val previous = checkNotNull(expected.round)
        check(round.status == RoundStatus.RUNNING && round.endsAt != null &&
            round.endsAt!!.isAfter(actual.serverNow.plusSeconds(60))) { "Running deadline is no longer safely in the future" }
        check(round.remainingMs <= previous.remainingMs && round.activeElapsedMs >= previous.activeElapsedMs) { "Running time moved backwards" }
        check(actual.copy(serverNow = expected.serverNow,
            round = round.copy(remainingMs = previous.remainingMs, activeElapsedMs = previous.activeElapsedMs)) == expected) {
            "Running round identity, deadline, membership, task or revision changed"
        }
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
            catch (_: Exception) { clean = false; System.err.println("FAIL cleanup: controller did not confirm server readiness") }
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
                    // A peer whose join failed may have no current room while the owner's room is still open.
                    if (value == JsonNull) { if (token == ownerToken) handled = true; continue }
                    val room = FocusDuoJson.decodeFromJsonElement<RoomSnapshotDto>(value).toDomain()
                    check(room.ownerId == ownerId && (roomId == null || room.roomId == roomId)) { "Unrelated room refused" }
                    if (room.status == RoomStatus.OPEN) check(request("POST", "/rooms/${room.roomId}/close", token, room.revision).statusCode() == 200)
                    handled = true
                    break
                } catch (_: Exception) { /* Another own session can still finish bounded cleanup. */ }
            }
            if (!handled) { clean = false; System.err.println("FAIL cleanup: own room closure could not be confirmed") }
        }
        for (token in tokens) {
            try { check(request("POST", "/auth/logout", token).statusCode() in setOf(204, 401)) }
            catch (_: Exception) { clean = false; System.err.println("FAIL cleanup: session revocation could not be confirmed") }
        }
        repositories.forEach { it.close() }
        engines.forEach { it.close(); it.forget() }
        return clean
    }

    override fun close() { http.shutdownNow() }
}

fun main() {
    var result = 0
    var smoke: Smoke? = null
    try {
        val base = System.getProperty("focusduo.smoke.baseUrl", "http://127.0.0.1:8090").trimEnd('/')
        val uri = URI.create(base)
        require(uri.scheme == "http" && uri.host in setOf("127.0.0.1", "localhost") && uri.port == 8090 &&
            uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null && uri.path.isEmpty())
        val controlDirectory = System.getProperty("focusduo.smoke.controlDir", "").trim()
        if (controlDirectory.isEmpty()) {
            System.err.println("Required: -Dfocusduo.smoke.controlDir=<absolute isolated controller directory>")
            error("Control directory is required")
        }
        val directory = Path.of(controlDirectory).normalize()
        val control = RestartControl(directory)
        smoke = Smoke(base, control)
        runBlocking { withTimeout(600_000) { smoke.run() } }
    } catch (failure: Throwable) {
        // No throwable messages, responses, credentials, headers or stack traces reach output.
        System.err.println("FAIL live Kotlin restart: ${failure.javaClass.simpleName}")
        result = if (failure is CancellationException) 130 else 1
    } finally {
        smoke?.let {
            try { if (!runBlocking { withTimeout(90_000) { it.cleanup() } }) result = 1 }
            catch (failure: Throwable) { System.err.println("FAIL cleanup: ${failure.javaClass.simpleName}"); result = 1 }
            finally { it.close() }
        }
    }
    if (result == 0) println("PASS FocusDuo RealRepository live restart integration")
    exitProcess(result)
}
