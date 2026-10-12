package kr.coms.companion

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.time.OffsetDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class ApiFailure(val status: Int, message: String) : IOException(message) {
    val definitiveNoSave: Boolean get() = status in 400..499 && status != 408 && status != 429
}

fun checkedBaseUrl(value: String, debug: Boolean): String {
    val uri = URI(value.trim())
    require(uri.userInfo == null && uri.query == null && uri.fragment == null) { "주소에는 계정·쿼리·조각을 넣지 마세요" }
    require(uri.host != null && (uri.scheme == "https" || debug && uri.scheme == "http" && uri.host == "10.0.2.2")) {
        "HTTPS 주소가 필요해요. 에뮬레이터 디버그에서는 http://10.0.2.2만 허용해요"
    }
    return value.trim().trimEnd('/')
}

fun publishPayload(event: Event, operationId: String): JSONObject {
    fun time(value: String): Any = if (value.isBlank()) JSONObject.NULL else OffsetDateTime.parse(value).toInstant().toString()
    val eventView = JSONObject()
        .put("id", event.id).put("clubName", "COM’s").put("title", event.title)
        .put("startsAt", time(event.whenText)).put("location", event.place)
        .put("feeAmount", event.feeWon).put("capacity", event.capacity ?: JSONObject.NULL)
        .put("deadline", time(event.deadline)).put("notice", event.details)
        .put("cancelled", event.cancelled).put("version", event.serverVersion)
        .put("pollOptions", JSONArray()).put("pollDeadline", JSONObject.NULL)
    return JSONObject().put("operationId", operationId).put("expectedVersion", event.serverVersion).put("event", eventView)
}

fun linkPayload(invite: Invite): JSONObject = JSONObject()
    .put("operationId", invite.operationId).put("kind", if (invite.memberId.isBlank()) "common" else "personal")
    .put("expiresAt", invite.expiresAt)
    .apply { if (invite.memberId.isNotBlank()) put("memberId", invite.memberId) }

class RemoteApi(private val baseUrl: String, private val adminToken: String) {
    init { require(adminToken.isNotBlank()) { "운영자 토큰을 입력해 주세요" } }

    suspend fun publish(payload: String): Int = request("POST", "/api/admin/events", payload).getJSONObject("event").getInt("version")
    suspend fun createLink(eventId: String, invite: Invite): String = request("POST", "/api/admin/events/$eventId/links", linkPayload(invite).toString()).getString("url")
    suspend fun responses(eventId: String): String = request("GET", "/api/admin/events/$eventId/responses").toString()

    private suspend fun request(method: String, path: String, body: String? = null): JSONObject = withContext(Dispatchers.IO) {
        val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection)
        try {
            connection.requestMethod = method
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            connection.setRequestProperty("Authorization", "Bearer $adminToken")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Cache-Control", "no-store")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val text = (if (status in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val message = runCatching { JSONObject(text).getJSONObject("error").getString("message") }.getOrDefault("서버 오류 $status")
                throw ApiFailure(status, "$message ($status)")
            }
            JSONObject(text)
        } finally { connection.disconnect() }
    }
}
