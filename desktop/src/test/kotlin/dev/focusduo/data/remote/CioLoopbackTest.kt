package dev.focusduo.data.remote

import com.sun.net.httpserver.HttpServer
import dev.focusduo.data.RealRepository
import dev.focusduo.data.RealSessionStatus
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Exercises the production CIO engine over TCP, without claiming this stub is the Java backend. */
class CioLoopbackTest {
    @Test
    fun actualCioSendsUtf8BearerAndBodylessLogout() = runBlocking<Unit> {
        data class Request(val path: String, val method: String, val authorization: String?, val body: String, val type: String?)
        val requests = CopyOnWriteArrayList<Request>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val fixture = javaClass.getResource("/contracts/auth-session.json")!!.readText()
        server.createContext("/api/v1/") { exchange ->
            val path = exchange.requestURI.path
            requests += Request(path, exchange.requestMethod, exchange.requestHeaders.getFirst("Authorization"),
                exchange.requestBody.bufferedReader(Charsets.UTF_8).use { it.readText() }, exchange.requestHeaders.getFirst("Content-Type"))
            val response = when (path) {
                "/api/v1/auth/login" -> fixture
                "/api/v1/me" -> FocusDuoJson.decodeFromString<AuthSessionDto>(fixture).user.let { FocusDuoJson.encodeToString(UserDto.serializer(), it) }
                "/api/v1/rooms/current" -> "{\"room\":null}"
                else -> ""
            }
            if (path == "/api/v1/auth/logout") exchange.sendResponseHeaders(204, -1)
            else {
                val bytes = response.toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            exchange.close()
        }
        server.start()
        try {
            val repository = RealRepository.login("http://127.0.0.1:${server.address.port}", " DANIYAR ", " парольСпробелами! ", realtimeEnabled = false)
            try {
                repository.bootstrap()
                assertEquals("Данияр", repository.state.value.currentUser.displayName)
                assertNull(repository.state.value.room)
                repository.logout()
                assertEquals(RealSessionStatus.SIGNED_OUT, repository.sessionStatus.value)
                assertEquals(listOf("/api/v1/auth/login", "/api/v1/me", "/api/v1/rooms/current", "/api/v1/auth/logout"), requests.map { it.path })
                val auth = requests.first()
                assertEquals("POST", auth.method)
                assertNull(auth.authorization)
                assertTrue(auth.type.orEmpty().startsWith("application/json"))
                val body = FocusDuoJson.decodeFromString<LoginRequestDto>(auth.body)
                assertEquals("daniyar", body.username)
                assertTrue(body.password == " парольСпробелами! ", "Password spaces and UTF-8 must reach the server unchanged")
                requests.drop(1).forEach { assertTrue(it.authorization?.startsWith("Bearer ") == true) }
                assertEquals("POST", requests.last().method)
                assertEquals("", requests.last().body)
                assertFalse(requests.any { it.path.contains('?') })
            } finally { repository.close() }
        } finally { server.stop(0) }
    }
}
