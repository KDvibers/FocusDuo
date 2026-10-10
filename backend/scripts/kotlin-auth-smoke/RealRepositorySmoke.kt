@file:OptIn(io.ktor.utils.io.InternalAPI::class)

package dev.focusduo.smoke

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
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import java.net.URI
import java.net.http.HttpClient as JdkHttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import kotlin.system.exitProcess

/** Observes outgoing authorization in memory; all requests still use the unmodified CIO engine. */
private class ObservedCio(private val cio: HttpClientEngine = CIO.create()) : HttpClientEngine by cio {
    @Volatile private var bearer: String? = null
    fun token(): String = checkNotNull(bearer) { "Authenticated request was not observed" }
    override fun install(client: HttpClient) {
        client.requestPipeline.intercept(HttpRequestPipeline.Before) {
            context.headers[HttpHeaders.Authorization]?.let { value ->
                check(value.startsWith("Bearer ")) { "Unexpected authorization scheme" }
                bearer = value.removePrefix("Bearer ")
            }
        }
        cio.install(client)
    }
    fun forget() { bearer = null }
}

private class Smoke(private val baseUrl: String) : AutoCloseable {
    private val http = JdkHttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
        .followRedirects(JdkHttpClient.Redirect.NEVER).build()
    private val repositories = mutableListOf<RealRepository>()
    private val engines = mutableListOf<ObservedCio>()
    private var roomId: String? = null
    private var roomOwnerId: String? = null
    private var roomCreationAttempted = false
    private var cleanupToken: String? = null

    suspend fun run() {
        val username = "kotauth_" + UUID.randomUUID().toString().replace("-", "").take(20)
        val password = UUID.randomUUID().toString() + "Я!"
        val ownerEngine = observe()
        val owner = keep(RealRepository.register(baseUrl, "  ${username.uppercase()}  ", "  Kotlin owner  ", password, ownerEngine))
        owner.bootstrap()
        check(owner.state.value.currentUser.username == username) { "Username normalization differs" }
        check(owner.state.value.currentUser.displayName == "Kotlin owner") { "Display name normalization differs" }
        check(owner.expiresAt.isAfter(java.time.Instant.now())) { "Session expiry is not in the future" }
        val ownerId = owner.state.value.currentUser.id
        roomOwnerId = ownerId

        expectCode("UNAUTHORIZED") { keep(RealRepository.login(baseUrl, username, password + "wrong")) }
        expectCode("USERNAME_TAKEN") { keep(RealRepository.register(baseUrl, username, "Duplicate", password)) }
        println("PASS auth: normalization, wrong password and duplicate username through real CIO")

        val peerEngine = observe()
        val peer = keep(RealRepository.register(baseUrl, username + "b", "Kotlin peer", password, peerEngine))
        peer.bootstrap()
        cleanupToken = ownerEngine.token()
        roomCreationAttempted = true
        var expected = command("POST", "/rooms", ownerEngine.token(),
            buildJsonObject { put("focusDurationSeconds", 300); put("breakDurationSeconds", 60) })
        roomId = expected.roomId
        expected = command("POST", "/rooms/join", peerEngine.token(), buildJsonObject { put("code", expected.code) })
        cleanupToken = peerEngine.token()
        expected = command("PATCH", "/rooms/${expected.roomId}/me", ownerEngine.token(),
            buildJsonObject { put("goal", "Живая проверка Kotlin") }, expected)
        expected = command("POST", "/rooms/${expected.roomId}/tasks", peerEngine.token(),
            buildJsonObject { put("title", "Прочитать полный snapshot") }, expected)
        check(expected.participants.size == 2 && expected.participants.sumOf { it.tasks.size } == 1) { "Setup is incomplete" }
        sameRoom(owner, expected)
        sameRoom(peer, expected)
        check(owner.refreshRoom(expected.roomId).copy(serverNow = expected.serverNow) == expected) { "Known room differs" }
        println("PASS current room: two participants, goal, task and known-room DTO mapped by RealRepository")

        request("POST", "/auth/logout", ownerEngine.token(), expectedStatus = 204)
        expectCode("UNAUTHORIZED") { owner.refreshCurrentRoom() }
        check(owner.sessionStatus.value == RealSessionStatus.EXPIRED) { "Server 401 did not expire session" }
        check(owner.state.value.room == null && owner.state.value.history.isEmpty()) { "Server 401 retained room/history" }
        check(owner.state.value.currentUser.id == ownerId) { "Server 401 changed identity" }
        sameRoom(peer, expected)
        println("PASS 401: external token revocation expires and clears the repository; peer remains authorized")

        val loginEngine = observe()
        val login = keep(RealRepository.login(baseUrl, username, password, loginEngine))
        login.bootstrap()
        check(login.state.value.currentUser.id == ownerId) { "Login changed identity" }
        sameRoom(login, expected)
        val loggedOutToken = loginEngine.token()
        login.logout()
        check(login.sessionStatus.value == RealSessionStatus.SIGNED_OUT) { "Logout did not sign out" }
        check(login.state.value.room == null && login.state.value.history.isEmpty()) { "Logout retained room/history" }
        val rejected = request("GET", "/me", loggedOutToken, expectedStatus = 401)
        check(rejected.jsonObject["code"]?.jsonPrimitive?.content == "UNAUTHORIZED") { "Logout did not revoke token" }
        sameRoom(peer, expected)
        println("PASS relogin/logout: room restored, local state cleared, token revoked, room remains open")

        val closed = command("POST", "/rooms/${expected.roomId}/close", peerEngine.token(), previous = expected)
        check(closed.status == RoomStatus.CLOSED) { "Room did not close" }
        roomId = null
        roomCreationAttempted = false
        check(peer.refreshCurrentRoom() == null) { "Closed room is still current" }
        peer.logout()
        println("PASS cleanup: own room closed and peer session logged out")
    }

    private fun observe() = ObservedCio().also { engines += it }
    private fun keep(repository: RealRepository) = repository.also { repositories += it }

    private suspend fun sameRoom(repository: RealRepository, expected: RoomSnapshot) {
        val actual = checkNotNull(repository.refreshCurrentRoom()) { "Current room is unexpectedly empty" }
        check(actual.copy(serverNow = expected.serverNow) == expected) { "Current-room contents differ" }
    }

    private suspend fun expectCode(code: String, block: suspend () -> Any?) {
        try { block() } catch (failure: RepositoryException) {
            check(failure.code == code) { "Unexpected repository error code" }
            return
        }
        error("Expected repository error was not returned")
    }

    private fun command(method: String, path: String, token: String, body: JsonObject? = null,
                        previous: RoomSnapshot? = null): RoomSnapshot =
        FocusDuoJson.decodeFromJsonElement<RoomSnapshotDto>(request(method, path, token, body, previous?.revision, true)).toDomain()

    private fun request(method: String, path: String, token: String, body: JsonObject? = null,
                        revision: Long? = null, command: Boolean = false, expectedStatus: Int = 200,
                        timeoutSeconds: Long = 10): JsonElement {
        val builder = HttpRequest.newBuilder(URI.create("$baseUrl/api/v1$path"))
            .timeout(Duration.ofSeconds(timeoutSeconds)).header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
        if (command) builder.header("Idempotency-Key", UUID.randomUUID().toString())
        if (revision != null) builder.header("X-Room-Revision", revision.toString())
        if (body != null) builder.header("Content-Type", "application/json")
        builder.method(method, body?.let { HttpRequest.BodyPublishers.ofString(it.toString()) } ?: HttpRequest.BodyPublishers.noBody())
        val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == expectedStatus) { "Unexpected HTTP status: ${response.statusCode()} (expected $expectedStatus)" }
        return if (response.body().isBlank()) JsonNull else FocusDuoJson.parseToJsonElement(response.body())
    }

    suspend fun cleanup(): Boolean {
        var clean = true
        val token = cleanupToken
        if (roomCreationAttempted && token != null) {
            try {
                // POST /rooms may commit even when its response is lost or cannot be decoded.
                // Both accounts are newly registered by this run; recover membership before logout.
                val current = checkNotNull(request("GET", "/rooms/current", token, timeoutSeconds = 3).jsonObject["room"])
                if (current != JsonNull) {
                    val snapshot = FocusDuoJson.decodeFromJsonElement<RoomSnapshotDto>(current).toDomain()
                    check(snapshot.ownerId == roomOwnerId && (roomId == null || snapshot.roomId == roomId)) {
                        "Cleanup refused an unrelated room"
                    }
                    if (snapshot.status == RoomStatus.OPEN) {
                        request("POST", "/rooms/${snapshot.roomId}/close", token, revision = snapshot.revision,
                            command = true, timeoutSeconds = 3)
                    }
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
        // Never print throwable messages, request bodies, credentials, headers or stack traces.
        System.err.println("FAIL live Kotlin auth smoke: ${failure.javaClass.simpleName}")
        result = if (failure is CancellationException) 130 else 1
    } finally {
        smoke?.let {
            try { if (!runBlocking { it.cleanup() }) result = 1 }
            finally { it.close() }
        }
    }
    if (result == 0) println("PASS FocusDuo RealRepository live auth integration")
    exitProcess(result)
}
