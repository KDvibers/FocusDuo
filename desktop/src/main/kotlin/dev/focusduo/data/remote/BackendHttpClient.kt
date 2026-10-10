package dev.focusduo.data.remote

import dev.focusduo.data.ServerAddress
import dev.focusduo.domain.RepositoryException
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.accept
import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import java.io.ByteArrayOutputStream
import java.io.IOException

/** Small, single-origin REST transport. No logging, redirects, retries or persisted credentials. */
internal class BackendHttpClient(baseUrl: String, engine: HttpClientEngine? = null) : AutoCloseable {
    val baseUrl = ServerAddress.normalize(baseUrl)
    @Volatile private var accessToken: String? = null
    // HttpClient(engine instance) does not own/close that engine; this transport owns both.
    private val ownedEngine = engine ?: CIO.create()
    private val client = HttpClient(ownedEngine) {
        expectSuccess = false
        followRedirects = false
        install(HttpTimeout) {
            connectTimeoutMillis = 5_000
            requestTimeoutMillis = 15_000
            socketTimeoutMillis = 15_000
        }
    }

    suspend fun login(request: LoginRequestDto): AuthSessionDto = exchange(
        HttpMethod.Post, "/auth/login", 200, FocusDuoJson.encodeToString(request), authorized = false,
    )

    suspend fun register(request: RegisterRequestDto): AuthSessionDto = exchange(
        HttpMethod.Post, "/auth/register", 201, FocusDuoJson.encodeToString(request), authorized = false,
    )

    fun installToken(token: String) {
        // The token is opaque; reject header delimiters rather than assuming JWT or its exact length.
        if (token.isBlank() || token.length > 4096 || token.any { it.isWhitespace() || it.code < 0x21 || it.code > 0x7e }) {
            throw protocolError()
        }
        accessToken = token
    }

    fun clearToken() { accessToken = null }
    internal fun tokenForHandshake(): String = accessToken ?: throw unauthorized()

    suspend fun me(): UserDto = exchange(HttpMethod.Get, "/me", 200)
    suspend fun currentRoom(): CurrentRoomDto = exchange(HttpMethod.Get, "/rooms/current", 200)

    suspend fun room(roomId: String): RoomSnapshotDto {
        requireUuid(roomId)
        return exchange(HttpMethod.Get, "/rooms/$roomId", 200)
    }

    suspend fun logout() { exchange<Unit>(HttpMethod.Post, "/auth/logout", 204) }

    suspend fun history(): HistoryDto = exchange(HttpMethod.Get, "/history", 200)
    suspend fun historyItem(roundId: String): HistoryItemDto {
        requireUuid(roundId)
        return exchange(HttpMethod.Get, "/history/$roundId", 200)
    }

    suspend fun command(
        method: HttpMethod,
        path: String,
        body: String?,
        key: String,
        revision: Long? = null,
    ): RoomSnapshotDto = exchange(method, path, 200, body, idempotencyKey = key, revision = revision)

    private suspend inline fun <reified T> exchange(
        method: HttpMethod,
        path: String,
        successStatus: Int,
        jsonBody: String? = null,
        authorized: Boolean = true,
        idempotencyKey: String? = null,
        revision: Long? = null,
    ): T = safeRequest {
        val bearer = if (authorized) accessToken ?: throw unauthorized() else null
        client.prepareRequest("$baseUrl/api/v1$path") {
            this.method = method
            accept(ContentType.Application.Json)
            if (bearer != null) header(HttpHeaders.Authorization, "Bearer $bearer")
            if (idempotencyKey != null) header("Idempotency-Key", idempotencyKey)
            if (revision != null) header("X-Room-Revision", revision.toString())
            if (jsonBody != null) {
                contentType(ContentType.Application.Json)
                setBody(jsonBody)
            }
        }.execute { response ->
            // The execute block streams the body; request() would buffer it before our size check.
            val status = response.status.value
            // Invalidate immediately, even if an invalid or disconnected error response cannot be read.
            if (status == 401) {
                clearToken()
                throw if (authorized) unauthorized() else RepositoryException("UNAUTHORIZED", "Неверный логин или пароль.")
            }
            val body = readBounded(response.bodyAsChannel())
            if (status != successStatus) {
                if (status in 200..299) throw protocolError()
                throw errorFor(status, body)
            }
            if (T::class == Unit::class) {
                @Suppress("UNCHECKED_CAST")
                Unit as T
            } else {
                try { FocusDuoJson.decodeFromString<T>(body) } catch (_: SerializationException) { throw protocolError() }
            }
        }
    }

    private suspend fun readBounded(channel: io.ktor.utils.io.ByteReadChannel): String {
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = channel.readAvailable(buffer)
            if (count < 0) break
            if (bytes.size() + count > MAX_RESPONSE_BYTES) {
                channel.cancel(null)
                throw protocolError()
            }
            bytes.write(buffer, 0, count)
        }
        return bytes.toString(Charsets.UTF_8)
    }

    private suspend inline fun <T> safeRequest(block: () -> T): T = try { block() } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (expected: RepositoryException) {
        throw expected
    } catch (_: HttpRequestTimeoutException) {
        throw RepositoryException("NETWORK_TIMEOUT", "Сервер не ответил вовремя. Повторите попытку.")
    } catch (_: IOException) {
        throw RepositoryException("NETWORK_ERROR", "Не удалось связаться с сервером. Проверьте адрес и подключение.")
    } catch (_: Exception) {
        // Exception text can contain headers, request values or low-level traces.
        throw RepositoryException("NETWORK_ERROR", "Не удалось выполнить запрос к серверу. Повторите попытку.")
    }

    private fun errorFor(status: Int, body: String): RepositoryException {
        val dto = try { FocusDuoJson.decodeFromString<ApiErrorDto>(body) } catch (_: Exception) { null }
        val code = when (status) {
            400 -> "VALIDATION_ERROR"
            403 -> "FORBIDDEN"
            404 -> "NOT_FOUND"
            409 -> dto?.code?.takeIf { it in CONFLICT_CODES } ?: "HTTP_ERROR"
            in 500..599 -> "INTERNAL_ERROR"
            else -> "HTTP_ERROR"
        }
        // Never expose an arbitrary server response/message (HTML, stack trace, echoed password/token).
        val fields = if (code == "VALIDATION_ERROR") dto?.fieldErrors?.keys.orEmpty().mapNotNull { field ->
            SAFE_FIELD_ERRORS[field]?.let { field to it }
        }.toMap() else emptyMap()
        val snapshot = if (code == "REVISION_CONFLICT") {
            try { dto?.snapshot?.toDomain() } catch (_: Exception) { throw protocolError() }
        } else null
        return RepositoryException(code, ERROR_MESSAGES[code] ?: "Сервер отклонил запрос. Повторите попытку позже.", fields, snapshot)
    }

    override fun close() {
        clearToken()
        client.close()
        client.coroutineContext.cancel()
        ownedEngine.close()
    }

    companion object {
        private const val MAX_RESPONSE_BYTES = 4 * 1024 * 1024
        private val CONFLICT_CODES = setOf("USERNAME_TAKEN", "ROOM_FULL", "ALREADY_IN_ROOM", "ROOM_CLOSED", "INVALID_STATE",
            "ROUND_NOT_CURRENT", "REVISION_CONFLICT", "IDEMPOTENCY_KEY_REUSED")
        private val SAFE_FIELD_ERRORS = mapOf(
            "username" to "Проверьте логин: от 3 до 32 латинских букв, цифр или _.",
            "password" to "Пароль должен содержать от 10 до 128 символов. Пробелы учитываются.",
            "displayName" to "Имя должно содержать от 2 до 40 символов после удаления крайних пробелов.",
            "goal" to "Цель должна содержать не более 240 символов.",
            "title" to "Название задачи должно содержать от 1 до 160 символов.",
            "tasks" to "У участника может быть не более 20 задач.",
            "code" to "Проверьте код комнаты.",
            "focusDurationSeconds" to "Фокус: от 5 до 180 минут, целое число минут.",
            "breakDurationSeconds" to "Перерыв: от 1 до 60 минут, целое число минут.",
        )
        private val ERROR_MESSAGES = mapOf(
            "VALIDATION_ERROR" to "Проверьте заполненные поля.",
            "FORBIDDEN" to "Недостаточно прав для этого действия.",
            "NOT_FOUND" to "Запрошенные данные не найдены.",
            "USERNAME_TAKEN" to "Этот логин уже занят. Выберите другой.",
            "ROOM_FULL" to "В комнате уже два участника.",
            "ALREADY_IN_ROOM" to "Вы уже состоите в другой открытой комнате.",
            "ROOM_CLOSED" to "Комната уже закрыта.",
            "INVALID_STATE" to "Действие недоступно в текущем состоянии комнаты.",
            "ROUND_NOT_CURRENT" to "Раунд уже изменился. Обновите комнату.",
            "REVISION_CONFLICT" to "Комната изменилась. Обновите данные перед новым действием.",
            "IDEMPOTENCY_KEY_REUSED" to "Запрос с этим ключом уже был выполнен иначе.",
            "INTERNAL_ERROR" to "На сервере произошла ошибка. Повторите попытку позже.",
        )

        internal fun protocolError() = RepositoryException("PROTOCOL_ERROR", "Ответ сервера не соответствует контракту FocusDuo v1.")
        internal fun unauthorized() = RepositoryException("UNAUTHORIZED", "Сессия недействительна. Войдите снова.")
        internal fun requireUuid(value: String) {
            val parsed = if (value.length == 36) runCatching { java.util.UUID.fromString(value) }.getOrNull() else null
            if (parsed == null || !parsed.toString().equals(value, ignoreCase = true)) throw protocolError()
        }
    }
}
