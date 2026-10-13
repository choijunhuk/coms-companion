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

class ApiFailure(val status: Int, message: String, val currentJson: String = "", val code: String = "") : IOException(message) {
    val definitiveNoSave: Boolean get() = status in 400..499 && status != 408 && status != 429
}

fun checkedBaseUrl(value: String, debug: Boolean): String {
    val uri = URI(value.trim())
    require(uri.userInfo == null && uri.query == null && uri.fragment == null && (uri.path.isNullOrEmpty() || uri.path == "/")) {
        "서버 주소는 경로가 없는 원본 주소로 입력해 주세요"
    }
    require(uri.host != null && (uri.scheme == "https" || debug && uri.scheme == "http" && uri.host == "10.0.2.2")) {
        "HTTPS 주소가 필요해요. 에뮬레이터 디버그에서는 http://10.0.2.2만 허용해요"
    }
    return value.trim().trimEnd('/')
}

fun publishPayload(event: Event, operationId: String, clubName: String = "COM’s", initialResponses: JSONArray = JSONArray()): JSONObject {
    fun time(value: String): Any = if (value.isBlank()) JSONObject.NULL else OffsetDateTime.parse(value).toInstant().toString()
    val eventView = JSONObject()
        .put("id", event.id).put("clubName", clubName).put("title", event.title)
        .put("startsAt", time(event.whenText)).put("location", event.place)
        .put("feeAmount", event.feeWon).put("capacity", event.capacity ?: JSONObject.NULL)
        .put("deadline", time(event.deadline)).put("notice", event.details)
        .put("cancelled", event.cancelled).put("version", event.serverVersion)
        .put("pollOptions", JSONArray(event.pollOptionsJson)).put("pollDeadline", time(event.pollDeadline))
    return JSONObject().put("operationId", operationId).put("expectedVersion", event.serverVersion).put("event", eventView)
        .apply { if (event.serverVersion == 0 && initialResponses.length() > 0) put("initialResponses", initialResponses) }
}

fun linkPayload(invite: Invite): JSONObject = JSONObject()
    .put("operationId", invite.operationId).put("kind", if (invite.memberId.isBlank()) "common" else "personal")
    .put("expiresAt", invite.expiresAt)
    .apply { if (invite.memberId.isNotBlank()) put("memberId", invite.memberId) }

class RemoteApi(private val baseUrl: String, private val adminToken: String) {
    init { require(adminToken.isNotBlank()) { "운영자 토큰을 입력해 주세요" } }

    suspend fun publish(payload: String): Int = publishDetails(payload).getJSONObject("event").getInt("version")
    suspend fun publishDetails(payload: String): JSONObject = request("POST", "/api/admin/events", payload)
    suspend fun createLink(eventId: String, invite: Invite): String = request("POST", "/api/admin/events/$eventId/links", linkPayload(invite).toString()).getString("url")
    suspend fun revokeLink(eventId: String, payload: String): JSONObject =
        request("POST", "/api/admin/events/$eventId/links/revoke", payload)
    suspend fun revokeMemberLinks(eventId: String, payload: String): JSONObject =
        request("POST", "/api/admin/events/$eventId/links/revoke-member", payload)
    suspend fun responses(eventId: String): String = request("GET", "/api/admin/events/$eventId/responses").toString()
    suspend fun changes(cursor: Long): JSONObject = request("GET", "/api/admin/changes?cursor=$cursor")
    suspend fun linkSubmission(eventId: String, submissionId: String, payload: String): JSONObject =
        request("POST", "/api/admin/events/$eventId/submissions/$submissionId/link", payload)
    suspend fun updateSubmission(eventId: String, submissionId: String, payload: String): JSONObject =
        request("POST", "/api/admin/events/$eventId/submissions/$submissionId", payload)
    suspend fun createSubmission(eventId: String, payload: String): JSONObject =
        request("POST", "/api/admin/events/$eventId/submissions", payload)
    suspend fun reviewPayment(eventId: String, requestId: String, payload: String): JSONObject =
        request("POST", "/api/admin/events/$eventId/payments/$requestId/review", payload)
    suspend fun preparePayment(eventId: String, requestId: String, payload: String): JSONObject =
        request("POST", "/api/admin/events/$eventId/payments/$requestId/review/prepare", payload)
    suspend fun commitPayment(eventId: String, requestId: String, payload: String): JSONObject =
        request("POST", "/api/admin/events/$eventId/payments/$requestId/review/commit", payload)

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
                val current = runCatching { JSONObject(text).optJSONObject("current")?.toString().orEmpty() }.getOrDefault("")
                val code = runCatching { JSONObject(text).getJSONObject("error").getString("code") }.getOrDefault("")
                throw ApiFailure(status, "$message ($status)", current, code)
            }
            JSONObject(text)
        } finally { connection.disconnect() }
    }
}
