package dev.focusduo.qa

import dev.focusduo.data.remote.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.channels.consumeEach
import kotlinx.serialization.json.*
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Explicit loopback-only fixture server for inspecting the actual Compose/RealRepository UI.
 * This is a test-source utility, never an application fallback or evidence of Java integration.
 * All accounts, commands and snapshots are synthetic and live only until this process stops.
 */
object ContractUiServer {
    private val lock = Any()
    private val authFixture = FocusDuoJson.decodeFromString<AuthSessionDto>(fixture("auth-session"))
    private val user = authFixture.user.copy(username = "qa_desktop", displayName = "QA: локальный стенд")
    private val token = "local-ui-fixture-only-not-a-real-session"
    private var room = FocusDuoJson.decodeFromString<RoomSnapshotDto>(fixture("room-running")).let {
        it.copy(round = null, code = "FD-QA18090", serverNow = Instant.now().toString(), participants =
            it.participants.map { participant -> if (participant.userId == user.id)
                participant.copy(displayName = user.displayName, goal = "Локальная проверка интерфейса — тестовые данные")
            else participant })
    }
    private val sessions = CopyOnWriteArrayList<DefaultWebSocketServerSession>()
    private data class Cached(val fingerprint: String, val snapshot: RoomSnapshotDto)
    private val replies = mutableMapOf<String, Cached>()
    @Volatile private var online = true
    @Volatile private var expired = false
    private var nextMutation: String? = null
    private var commandCount = 0
    private var replayCount = 0

    @JvmStatic fun main(args: Array<String>) {
        println("LOCAL UI FIXTURE SERVER ONLY: http://127.0.0.1:18090")
        println("Synthetic login: qa_desktop, any locally valid test password. No Java backend or database is used.")
        println("POST /_qa/mode/{lost|conflict|offline|online|disconnect|expired|close}; GET /_qa/state")
        embeddedServer(CIO, port = 18090, host = "127.0.0.1") {
            install(WebSockets)
            routing {
                get("/_qa/state") {
                    val snapshot = synchronized(lock) {
                        buildJsonObject {
                            put("fixtureServer", true); put("online", online); put("expired", expired)
                            put("revision", room.revision); put("roomStatus", room.status)
                            put("commandCount", commandCount); put("replayCount", replayCount)
                            put("ownTaskCount", room.participants.first { it.userId == user.id }.tasks.size)
                            put("activeSockets", sessions.size)
                        }
                    }
                    call.respondText(snapshot.toString(), ContentType.Application.Json)
                }
                post("/_qa/mode/{mode}") {
                    val mode = call.parameters["mode"]
                    when (mode) {
                        "lost", "conflict" -> synchronized(lock) { nextMutation = mode }
                        "offline" -> { online = false; closeSockets(CloseReason.Codes.GOING_AWAY.code, "QA offline") }
                        "online" -> { online = true; expired = false }
                        "disconnect" -> closeSockets(CloseReason.Codes.GOING_AWAY.code, "QA connection interruption")
                        "expired" -> { expired = true; closeSockets(4401, "QA session expired") }
                        "close" -> {
                            val snapshot = synchronized(lock) { room = room.copy(status = "CLOSED", revision = room.revision + 1,
                                serverNow = Instant.now().toString(), round = null); room }
                            broadcast(snapshot)
                        }
                        else -> { call.respondText("Unknown QA mode", status = HttpStatusCode.BadRequest); return@post }
                    }
                    call.respondText("QA mode: $mode", ContentType.Text.Plain)
                }
                route("/api/v1/{rest...}") {
                    handle {
                        val path = call.request.path()
                        if (!online) { call.respondText(error("INTERNAL_ERROR"), ContentType.Application.Json, HttpStatusCode.ServiceUnavailable); return@handle }
                        if (path == "/api/v1/auth/login") {
                            // This explicit test utility accepts synthetic credentials only; nothing is persisted or logged.
                            call.receiveText()
                            expired = false
                            call.respondText(FocusDuoJson.encodeToString(AuthSessionDto(user, token, Instant.now().plusSeconds(3600).toString())), ContentType.Application.Json)
                            return@handle
                        }
                        if (expired || call.request.headers[HttpHeaders.Authorization] != "Bearer $token") {
                            call.respondText(error("UNAUTHORIZED"), ContentType.Application.Json, HttpStatusCode.Unauthorized); return@handle
                        }
                        val snapshot = synchronized(lock) { room.copy(serverNow = Instant.now().toString()).also { room = it } }
                        when {
                            path == "/api/v1/auth/logout" -> call.respond(HttpStatusCode.NoContent)
                            path == "/api/v1/me" -> call.respondText(FocusDuoJson.encodeToString(user), ContentType.Application.Json)
                            path == "/api/v1/rooms/current" -> call.respondText(FocusDuoJson.encodeToString(CurrentRoomDto(snapshot.takeIf { it.status == "OPEN" })), ContentType.Application.Json)
                            path == "/api/v1/history" -> call.respondText(fixture("history"), ContentType.Application.Json)
                            path.startsWith("/api/v1/history/") -> call.respondText(fixture("history-item"), ContentType.Application.Json)
                            path == "/api/v1/rooms/${snapshot.roomId}" && call.request.httpMethod == HttpMethod.Get ->
                                call.respondText(FocusDuoJson.encodeToString(snapshot), ContentType.Application.Json)
                            path == "/api/v1/rooms/${snapshot.roomId}/tasks" && call.request.httpMethod == HttpMethod.Post -> addTask(call)
                            else -> call.respondText(error("NOT_FOUND"), ContentType.Application.Json, HttpStatusCode.NotFound)
                        }
                    }
                }
                webSocket("/ws/rooms/{roomId}") {
                    if (expired || call.request.headers[HttpHeaders.Authorization] != "Bearer $token") {
                        close(CloseReason(4401, "QA session expired")); return@webSocket
                    }
                    if (!online) { close(CloseReason(1013, "QA offline")); return@webSocket }
                    if (call.parameters["roomId"] != synchronized(lock) { room.roomId }) {
                        close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Unknown QA room")); return@webSocket
                    }
                    sessions += this
                    try {
                        val snapshot = synchronized(lock) { room.copy(serverNow = Instant.now().toString()).also { room = it } }
                        send(Frame.Text(FocusDuoJson.encodeToString(RoomEventDto("ROOM_SNAPSHOT", snapshot))))
                        incoming.consumeEach { }
                    } finally { sessions -= this }
                }
            }
        }.start(wait = true)
    }

    private suspend fun addTask(call: ApplicationCall) {
        val body = call.receiveText()
        val key = call.request.headers["Idempotency-Key"]
        val revision = call.request.headers["X-Room-Revision"]
        val fingerprint = "POST|${call.request.path()}|$body|$revision"
        val title = runCatching { FocusDuoJson.parseToJsonElement(body).jsonObject["title"]!!.jsonPrimitive.content }.getOrNull()
        var published: RoomSnapshotDto? = null
        val result: Pair<HttpStatusCode, String> = synchronized(lock) {
            val cached = key?.let(replies::get)
            when {
                cached != null && cached.fingerprint != fingerprint -> HttpStatusCode.Conflict to error("IDEMPOTENCY_KEY_REUSED")
                cached != null -> { replayCount++; HttpStatusCode.OK to FocusDuoJson.encodeToString(cached.snapshot) }
                key == null || title == null || title.codePointCount(0, title.length) !in 1..160 ->
                    HttpStatusCode.BadRequest to error("VALIDATION_ERROR")
                room.status != "OPEN" -> HttpStatusCode.Conflict to error("ROOM_CLOSED")
                nextMutation == "conflict" || revision != room.revision.toString() -> {
                    nextMutation = null
                    room = room.copy(revision = room.revision + 1, serverNow = Instant.now().toString(), participants =
                        room.participants.map { if (it.userId == user.id) it.copy(goal = "Цель изменена на сервере во время проверки конфликта") else it })
                    published = room
                    HttpStatusCode.Conflict to error("REVISION_CONFLICT", room)
                }
                else -> {
                    room = room.copy(revision = room.revision + 1, serverNow = Instant.now().toString(), participants =
                        room.participants.map { if (it.userId == user.id) it.copy(tasks = it.tasks + TaskDto(UUID.randomUUID().toString(), title, false)) else it })
                    replies[key] = Cached(fingerprint, room)
                    commandCount++
                    published = room
                    if (nextMutation == "lost") {
                        nextMutation = null
                        HttpStatusCode.InternalServerError to error("INTERNAL_ERROR")
                    } else HttpStatusCode.OK to FocusDuoJson.encodeToString(room)
                }
            }
        }
        published?.let { broadcast(it) }
        call.respondText(result.second, ContentType.Application.Json, result.first)
    }

    private suspend fun broadcast(snapshot: RoomSnapshotDto) {
        val frame = FocusDuoJson.encodeToString(RoomEventDto("ROOM_SNAPSHOT", snapshot))
        sessions.forEach { runCatching { it.send(Frame.Text(frame)) } }
    }

    private suspend fun closeSockets(code: Short, reason: String) {
        sessions.forEach { runCatching { it.close(CloseReason(code, reason)) } }
    }

    private fun error(code: String, snapshot: RoomSnapshotDto? = null) = FocusDuoJson.encodeToString(
        ApiErrorDto(code, "Synthetic local UI QA response", "local-ui-qa", emptyMap(), snapshot))

    private fun fixture(name: String): String = requireNotNull(ContractUiServer::class.java.getResource("/contracts/$name.json")).readText()
}
