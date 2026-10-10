@file:OptIn(io.ktor.utils.io.InternalAPI::class)

package dev.focusduo.smoke.recovery

import dev.focusduo.data.RealConnectionStatus
import dev.focusduo.data.RealRepository
import dev.focusduo.data.RealSessionStatus
import dev.focusduo.data.remote.FocusDuoJson
import dev.focusduo.data.remote.RoomSnapshotDto
import dev.focusduo.data.remote.toDomain
import dev.focusduo.domain.RepositoryException
import dev.focusduo.domain.RoomSnapshot
import dev.focusduo.domain.RoomStatus
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.HttpRequestPipeline
import io.ktor.client.statement.HttpReceivePipeline
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient as JdkHttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlin.system.exitProcess

private data class WireCommand(val method: String, val path: String, val body: String?, val key: String, val revision: String?) {
    override fun toString() = "WireCommand(<redacted>)"
}
private data class Reply(val key: String, val status: Int)
private data class Dropped(val key: String, val status: Int, val byteCount: Int)
private class Hold(val path: String) {
    val prepared = CompletableDeferred<WireCommand>()
    val release = CompletableDeferred<Unit>()
}

/** Real CIO only: observe command metadata, hold one send, or discard one fully read real reply. */
private class FaultCio(private val cio: HttpClientEngine = CIO.create()) : HttpClientEngine by cio {
    @Volatile private var bearer: String? = null
    private val nextDrop = AtomicReference<String?>()
    private val dropKeys = ConcurrentHashMap.newKeySet<String>()
    private val nextHold = AtomicReference<Hold?>()
    private val holds = CopyOnWriteArrayList<Hold>()
    val commands = CopyOnWriteArrayList<WireCommand>()
    val replies = CopyOnWriteArrayList<Reply>()
    val dropped = CopyOnWriteArrayList<Dropped>()
    fun token(): String = checkNotNull(bearer) { "Authenticated request was not observed" }
    fun loseNext(path: String) { check(nextDrop.compareAndSet(null, path)) { "A response loss is already armed" } }
    fun holdNext(path: String): Hold = Hold(path).also {
        check(nextHold.compareAndSet(null, it)) { "A send is already held" }
        holds += it
    }

    override fun install(client: HttpClient) {
        client.requestPipeline.intercept(HttpRequestPipeline.Before) {
            context.headers[HttpHeaders.Authorization]?.let { value ->
                check(value.startsWith("Bearer ")) { "Unexpected authorization scheme" }
                bearer = value.removePrefix("Bearer ")
            }
            // Auth request bodies are never recorded. Idempotency-Key identifies room commands.
            context.headers["Idempotency-Key"]?.let { key ->
                val command = WireCommand(context.method.value, context.url.build().encodedPath,
                    context.body as? String, key, context.headers["X-Room-Revision"])
                commands += command
                nextDrop.get()?.let { path ->
                    if (path == command.path && nextDrop.compareAndSet(path, null)) dropKeys += key
                }
                nextHold.get()?.let { hold ->
                    if (hold.path == command.path && nextHold.compareAndSet(hold, null)) {
                        hold.prepared.complete(command)
                        withTimeout(10_000) { hold.release.await() }
                    }
                }
            }
        }
        client.receivePipeline.intercept(HttpReceivePipeline.After) {
            val response = subject
            response.call.request.headers["Idempotency-Key"]?.let { key ->
                replies += Reply(key, response.status.value)
                if (dropKeys.remove(key)) {
                    val channel = response.bodyAsChannel()
                    val buffer = ByteArray(8192)
                    var count = 0
                    withTimeout(10_000) {
                        while (true) {
                            val read = channel.readAvailable(buffer)
                            if (read < 0) break
                            count += read
                            check(count <= 4 * 1024 * 1024) { "Reply exceeds the transport limit" }
                        }
                    }
                    dropped += Dropped(key, response.status.value, count)
                    check(response.status.value == 200 && count > 0) { "Expected a complete successful server reply" }
                    throw IOException("Injected loss after receiving the complete server reply")
                }
            }
        }
        cio.install(client)
    }

    fun disarm() {
        nextDrop.set(null)
        dropKeys.clear()
        nextHold.set(null)
        holds.forEach { it.release.complete(Unit) }
    }
    fun forget() { disarm(); bearer = null; commands.clear(); replies.clear(); dropped.clear(); holds.clear() }
}

private class Smoke(private val baseUrl: String) : AutoCloseable {
    private val http = JdkHttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
        .followRedirects(JdkHttpClient.Redirect.NEVER).build()
    private val repositories = mutableListOf<RealRepository>()
    private val engines = mutableListOf<FaultCio>()
    private var roomId: String? = null
    private var roomOwnerId: String? = null
    private var roomCreationAttempted = false
    private var cleanupToken: String? = null

    suspend fun run() {
        val username = "kotretry_" + UUID.randomUUID().toString().replace("-", "").take(19)
        val password = UUID.randomUUID().toString() + "Я!"
        val ownerEngine = observe()
        val owner = keep(RealRepository.register(baseUrl, username, "Recovery owner", password, ownerEngine))
        owner.bootstrap()
        val ownerId = owner.state.value.currentUser.id
        roomOwnerId = ownerId
        val peerEngine = observe()
        val peer = keep(RealRepository.register(baseUrl, username + "b", "Recovery peer", password, peerEngine))
        peer.bootstrap()
        cleanupToken = ownerEngine.token()

        ownerEngine.loseNext("/api/v1/rooms")
        roomCreationAttempted = true
        expectCode("NETWORK_ERROR") { owner.createRoom(300, 60) }
        val createRequest = ownerEngine.commands.single()
        check(createRequest.method == "POST" && createRequest.revision == null && !createRequest.body.isNullOrBlank())
        assertDropped(ownerEngine, createRequest)
        awaitPending(owner)
        val recovered = checkNotNull(owner.state.value.room) { "Create did not recover membership" }
        roomId = recovered.roomId
        check(recovered.ownerId == ownerId && recovered.revision == 1L && recovered.participants.size == 1)
        sameBusiness(rawCurrent(ownerEngine.token()), recovered)
        check(owner.pendingCommand.value?.originalRevision == null && !owner.canEnterRoom)
        expectCode("PENDING_COMMAND") { owner.createRoom(300, 60) }
        check(ownerEngine.commands.size == 1) { "Recovery or a blocked action repeated creation" }
        owner.retryPendingCommand()
        check(ownerEngine.commands.size == 2 && ownerEngine.commands.last() == createRequest)
        sameBusiness(awaitReady(owner, recovered), recovered)
        sameBusiness(rawCurrent(ownerEngine.token()), recovered)
        check(owner.pendingCommand.value == null)
        println("PASS lost create: real 200 discarded, membership recovered, identical manual replay creates no second room")

        val joined = peer.joinRoom(recovered.code)
        cleanupToken = peerEngine.token()
        awaitReady(owner, joined)
        awaitReady(peer, joined)
        val taskTitle = "Единственная задача после потерянного ответа"
        val taskPath = "/api/v1/rooms/${joined.roomId}/tasks"
        ownerEngine.loseNext(taskPath)
        expectCode("NETWORK_ERROR") { owner.addTask(taskTitle) }
        val taskRequest = ownerEngine.commands.last()
        check(taskRequest.method == "POST" && taskRequest.path == taskPath && taskRequest.revision == joined.revision.toString())
        assertDropped(ownerEngine, taskRequest)
        awaitPending(owner)
        val committed = rawCurrent(peerEngine.token())
        check(committed.revision == joined.revision + 1)
        assertSingleTask(committed, ownerId, taskTitle)
        check(owner.pendingCommand.value?.originalRevision == joined.revision && !owner.canMutate)
        val safePending = owner.pendingCommand.value.toString()
        check(!safePending.contains(taskTitle) && !safePending.contains(taskRequest.key) && !safePending.contains(ownerEngine.token()))
        val beforeBlocked = ownerEngine.commands.size
        expectCode("PENDING_COMMAND") { owner.updateGoal("Не отправлять новую команду") }
        check(ownerEngine.commands.size == beforeBlocked)
        awaitReady(peer, committed)
        val edited = peer.updateGoal("Более свежий снимок перед повтором")
        awaitSnapshot(owner, edited)
        check(ownerEngine.commands.count { it.key == taskRequest.key } == 1) { "Recovery repeated a mutation automatically" }
        val replayed = owner.retryPendingCommand()
        sameBusiness(replayed, edited)
        sameBusiness(awaitReady(owner, edited), edited)
        val sameKey = ownerEngine.commands.filter { it.key == taskRequest.key }
        check(sameKey.size == 2 && sameKey.all { it == taskRequest }) { "Replay changed original command metadata" }
        val persisted = rawCurrent(peerEngine.token())
        sameBusiness(persisted, edited)
        assertSingleTask(persisted, ownerId, taskTitle)
        check(owner.pendingCommand.value == null)
        println("PASS lost task: pending gates new commands; replay keeps method/path/body/key/revision, one effect and newer peer snapshot")

        val goalPath = "/api/v1/rooms/${joined.roomId}/me"
        val desiredGoal = "Новое явное действие после конфликта"
        val hold = ownerEngine.holdNext(goalPath)
        lateinit var conflictRequest: WireCommand
        lateinit var fresh: RoomSnapshot
        coroutineScope {
            val result = async { expectCode("REVISION_CONFLICT") { owner.updateGoal(desiredGoal) } }
            try {
                conflictRequest = withTimeout(10_000) { hold.prepared.await() }
                check(conflictRequest.revision == edited.revision.toString())
                fresh = peer.updateGoal("Правка между подготовкой и отправкой команды")
                check(fresh.revision == edited.revision + 1)
                hold.release.complete(Unit)
                val error = result.await()
                sameBusiness(checkNotNull(error.snapshot), fresh)
            } finally { hold.release.complete(Unit) }
        }
        check(ownerEngine.replies.any { it.key == conflictRequest.key && it.status == 409 }) { "Conflict did not come from the real server" }
        check(owner.pendingCommand.value == null)
        sameBusiness(awaitReady(owner, fresh), fresh)
        sameBusiness(rawCurrent(peerEngine.token()), fresh)
        val succeeded = owner.updateGoal(desiredGoal)
        val nextRequest = ownerEngine.commands.last()
        check(nextRequest.key != conflictRequest.key && nextRequest.revision == fresh.revision.toString())
        check(nextRequest.method == conflictRequest.method && nextRequest.path == conflictRequest.path && nextRequest.body == conflictRequest.body)
        check(succeeded.revision == fresh.revision + 1 && succeeded.participants.first { it.userId == ownerId }.goal == desiredGoal)
        sameBusiness(rawCurrent(peerEngine.token()), succeeded)
        assertSingleTask(succeeded, ownerId, taskTitle)
        println("PASS conflict: delayed send gets real 409 and authoritative snapshot; new explicit action uses a new key and fresh revision")

        awaitReady(peer, succeeded)
        val closed = peer.closeRoom()
        check(closed.status == RoomStatus.CLOSED)
        roomCreationAttempted = false
        roomId = null
        owner.logout()
        peer.logout()
        println("PASS cleanup: own room closed and active sessions logged out")
    }

    private fun observe() = FaultCio().also { engines += it }
    private fun keep(repository: RealRepository) = repository.also { repositories += it }

    private suspend fun awaitPending(repository: RealRepository) {
        withTimeout(10_000) { repository.pendingCommand.first { it?.retryAvailable == true } }
    }
    private suspend fun awaitSnapshot(repository: RealRepository, expected: RoomSnapshot): RoomSnapshot = withTimeout(10_000) {
        checkNotNull(repository.state.first { it.room?.let { room -> room.roomId == expected.roomId && room.revision >= expected.revision } == true }.room)
    }
    private suspend fun awaitReady(repository: RealRepository, expected: RoomSnapshot): RoomSnapshot = withTimeout(10_000) {
        combine(repository.state, repository.connectionState, repository.pendingCommand, repository.sessionStatus) { state, connection, pending, session ->
            state.room?.takeIf { it.roomId == expected.roomId && it.status == RoomStatus.OPEN && it.revision >= expected.revision &&
                connection.status == RealConnectionStatus.CONNECTED && pending == null && session == RealSessionStatus.ACTIVE }
        }.filterNotNull().first()
    }
    private fun sameBusiness(actual: RoomSnapshot, expected: RoomSnapshot) {
        check(actual.copy(serverNow = expected.serverNow) == expected) { "Room contents or revision differ" }
    }
    private fun assertSingleTask(room: RoomSnapshot, userId: String, title: String) {
        val tasks = room.participants.first { it.userId == userId }.tasks
        check(tasks.size == 1 && tasks.single().title == title && !tasks.single().completed) { "Task was lost or duplicated" }
    }
    private fun assertDropped(engine: FaultCio, command: WireCommand) {
        check(engine.dropped.single { it.key == command.key }.let { it.status == 200 && it.byteCount > 0 })
    }
    private suspend fun expectCode(code: String, block: suspend () -> Any?): RepositoryException {
        try { block() } catch (failure: RepositoryException) {
            check(failure.code == code) { "Unexpected repository error code" }
            return failure
        }
        error("Expected repository error was not returned")
    }
    private fun rawCurrent(token: String): RoomSnapshot = FocusDuoJson.decodeFromJsonElement<RoomSnapshotDto>(
        checkNotNull(request("GET", "/rooms/current", token).jsonObject["room"])).toDomain()

    private fun request(method: String, path: String, token: String, revision: Long? = null,
                        timeoutSeconds: Long = 10): JsonElement {
        val builder = HttpRequest.newBuilder(URI.create("$baseUrl/api/v1$path"))
            .timeout(Duration.ofSeconds(timeoutSeconds)).header("Authorization", "Bearer $token").header("Accept", "application/json")
        if (revision != null) builder.header("X-Room-Revision", revision.toString()).header("Idempotency-Key", UUID.randomUUID().toString())
        val response = http.send(builder.method(method, HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == 200) { "Unexpected HTTP status" }
        return FocusDuoJson.parseToJsonElement(response.body())
    }

    suspend fun cleanup(): Boolean {
        var clean = true
        engines.forEach { it.disarm() }
        val token = cleanupToken
        if (roomCreationAttempted && token != null) {
            try {
                val current = checkNotNull(request("GET", "/rooms/current", token, timeoutSeconds = 3).jsonObject["room"])
                if (current != JsonNull) {
                    val snapshot = FocusDuoJson.decodeFromJsonElement<RoomSnapshotDto>(current).toDomain()
                    check(snapshot.ownerId == roomOwnerId && (roomId == null || snapshot.roomId == roomId)) { "Cleanup refused an unrelated room" }
                    if (snapshot.status == RoomStatus.OPEN) request("POST", "/rooms/${snapshot.roomId}/close", token,
                        revision = snapshot.revision, timeoutSeconds = 3)
                }
            } catch (_: Exception) { clean = false; System.err.println("FAIL cleanup: own room could not be closed") }
        }
        repositories.forEach { repository ->
            try {
                if (repository.sessionStatus.value == RealSessionStatus.ACTIVE) withTimeout(5_000) { repository.logout() }
            } catch (_: Exception) { clean = false; System.err.println("FAIL cleanup: session logout was not confirmed") }
            finally { repository.close() }
        }
        engines.forEach { it.close(); it.forget() }
        cleanupToken = null
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
        require(uri.scheme in setOf("http", "https") && uri.host in setOf("127.0.0.1", "localhost", "[::1]") &&
            uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null && uri.path.isEmpty())
        smoke = Smoke(base)
        runBlocking { withTimeout(120_000) { smoke.run() } }
    } catch (failure: Throwable) {
        // Never print request/response bodies, throwable messages, credentials, headers or stack traces.
        System.err.println("FAIL live Kotlin safe retry: ${failure.javaClass.simpleName}")
        result = if (failure is CancellationException) 130 else 1
    } finally {
        smoke?.let {
            try { if (!runBlocking { it.cleanup() }) result = 1 }
            finally { it.close() }
        }
    }
    if (result == 0) println("PASS FocusDuo RealRepository live safe retry")
    exitProcess(result)
}
