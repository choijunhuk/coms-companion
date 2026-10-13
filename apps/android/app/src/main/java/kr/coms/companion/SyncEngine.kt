package kr.coms.companion

import android.content.Context
import androidx.room.withTransaction
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.TimeUnit

private val syncMutex = Mutex()

suspend fun <T> withSyncPausedForRestore(block: suspend () -> T): T = syncMutex.withLock { block() }

fun responseCode(value: String): String = when (value) {
    "참석", "attending" -> "attending"
    "불참", "declined" -> "declined"
    "미정", "undecided" -> "undecided"
    else -> throw IllegalArgumentException("참석 응답을 선택해 주세요")
}

fun manualResponsesForPublish(rows: List<Rsvp>): List<Rsvp> = rows.filter { it.response != "미응답" }

fun recoveredServerEvent(snapshot: JSONObject, eventId: String): Event {
    for (key in listOf("submissions", "paymentRequests")) {
        val rows = snapshot.getJSONArray(key)
        for (index in 0 until rows.length()) require(rows.get(index) is JSONObject) { "서버 $key 목록을 확인해 주세요" }
    }
    val source = snapshot.getJSONObject("event")
    fun text(key: String): String = (source.get(key) as? String) ?: error("서버 행사 $key 형식을 확인해 주세요")
    fun number(key: String): Long {
        val value = source.get(key)
        require(value is Int || value is Long) { "서버 행사 $key 정수를 확인해 주세요" }
        return (value as Number).toLong()
    }
    fun optional(key: String): String {
        require(source.has(key)) { "서버 행사 $key 항목이 없어요" }
        return if (source.isNull(key)) "" else text(key).also { OffsetDateTime.parse(it) }
    }
    val version = number("version")
    require(runCatching { UUID.fromString(eventId).toString().equals(eventId, ignoreCase = true) }.getOrDefault(false)) { "서버 행사 ID 형식을 확인해 주세요" }
    require(text("id") == eventId && version in 1..Int.MAX_VALUE) { "복구할 서버 행사 ID·버전이 달라요" }
    require(text("title").isNotBlank() && text("clubName").isNotBlank()) { "서버 행사 이름이 비었어요" }
    val fee = number("feeAmount")
    require(fee in 0..1_000_000_000L) { "서버 행사 금액을 확인해 주세요" }
    require(source.has("capacity")) { "서버 행사 정원 항목이 없어요" }
    val capacity = if (source.isNull("capacity")) null else number("capacity").also {
        require(it in 1..100_000) { "서버 행사 정원을 확인해 주세요" }
    }.toInt()
    val options = source.getJSONArray("pollOptions")
    require(options.length() <= 20) { "일정 후보가 너무 많아요" }
    val optionIds = mutableSetOf<String>()
    for (index in 0 until options.length()) {
        val option = options.getJSONObject(index)
        val id = option.get("id") as? String
        val label = option.get("label") as? String
        require(!id.isNullOrBlank() && !label.isNullOrBlank() && optionIds.add(id)) { "일정 후보를 확인해 주세요" }
    }
    val cancelled = source.get("cancelled") as? Boolean ?: error("서버 행사 취소 상태를 확인해 주세요")
    return Event(id = eventId, title = text("title"), whenText = optional("startsAt"),
        place = text("location"), details = text("notice"), deadline = optional("deadline"),
        capacity = capacity, feeWon = fee, cancelled = cancelled,
        serverVersion = version.toInt(), serverDirty = false, responsesJson = snapshot.toString(),
        pollOptionsJson = options.toString(),
        pollDeadline = optional("pollDeadline"))
}

fun validateEventRecovery(mutation: PendingMutation, serverOrigin: String): Event {
    require(mutation.kind == "event-recover" && mutation.status == "conflict" &&
        mutation.eventId == mutation.targetId && serverOrigin.isNotBlank()) { "행사 복구 대기 기록이 잘못됐어요" }
    val payload = JSONObject(mutation.payload)
    require(payload.get("operationId") == mutation.operationId &&
        payload.get("serverOrigin") == serverOrigin) { "행사 복구 출처가 연결된 서버와 달라요" }
    return recoveredServerEvent(payload.getJSONObject("snapshot"), mutation.eventId).also {
        require(it.serverVersion == mutation.baseVersion) { "행사 복구 미리보기 버전이 달라요" }
    }
}

private fun sameServerEventMetadata(first: JSONObject, second: JSONObject): Boolean {
    val keys = listOf("id", "clubName", "title", "startsAt", "location", "feeAmount", "capacity",
        "deadline", "notice", "cancelled", "version", "pollOptions", "pollDeadline")
    return keys.all { key -> first.get(key).toString() == second.get(key).toString() }
}

class SyncEngine(context: Context) {
    private val appContext = context.applicationContext
    private val db = AppDatabase.get(appContext)
    private val dao = db.dao()

    private suspend fun api(): RemoteApi {
        val connection = appContext.readConnection() ?: error("서버 주소와 운영자 토큰을 설정해 주세요")
        val config = dao.clubConfigSnapshot()
        if (config != null && config.serverOrigin.isNotBlank() && config.serverOrigin != connection.baseUrl && dao.onlineStateCount() > 0)
            error("이 기기의 온라인 기록은 다른 서버에 연결돼 있어요. 연결을 확인해 주세요")
        if (config == null || config.serverOrigin != connection.baseUrl)
            dao.put((config ?: ClubConfig()).copy(serverOrigin = connection.baseUrl))
        return RemoteApi(connection.baseUrl, connection.adminToken)
    }

    suspend fun publishEvent(eventId: String) = syncMutex.withLock {
        val remote = api()
        val payload = db.withTransaction {
            val event = requireNotNull(dao.eventById(eventId)) { "행사를 찾지 못했어요" }
            require(event.conflictJson.isBlank()) { "행사 충돌을 먼저 검토해 주세요" }
            if (event.pendingPayload.isNotBlank()) return@withTransaction event.pendingPayload
            val manual = if (event.serverVersion == 0) manualResponsesForPublish(dao.rsvpsFor(eventId)) else emptyList()
            val initial = JSONArray()
            for (rsvp in manual) {
                val member = requireNotNull(dao.memberById(rsvp.memberId)) { "수동 응답의 회원을 찾지 못했어요" }
                initial.put(JSONObject().put("id", rsvp.id).put("operationId", rsvp.id)
                    .put("memberId", member.id).put("name", member.name)
                    .put("rsvp", responseCode(rsvp.response))
                    .put("noticeAcknowledged", false).put("selectedOptions", JSONArray()))
            }
            val operationId = newId()
            val body = publishPayload(event, operationId, dao.clubConfigSnapshot()?.clubName ?: "COM’s", initial).toString()
            require(dao.markPublishPending(eventId, operationId, body) == 1) { "행사 저장 상태가 변경됐어요. 다시 확인해 주세요" }
            body
        }
        try {
            val result = remote.publishDetails(payload)
            acknowledgePublication(eventId, result)
        } catch (failure: ApiFailure) {
            if (failure.status == 409) dao.markEventConflict(eventId, failure.currentJson.ifBlank { "{}" })
            if (failure.definitiveNoSave && failure.status != 409) dao.clearPublishPending(eventId)
            throw failure
        }
    }

    suspend fun createLink(eventId: String, memberId: String? = null): String = syncMutex.withLock {
        val event = requireNotNull(dao.eventById(eventId)) { "행사를 찾지 못했어요" }
        require(event.serverVersion > 0 && !event.serverDirty && event.pendingOperationId.isBlank() && event.conflictJson.isBlank()) {
            "현재 행사 내용을 먼저 서버에 게시해 주세요"
        }
        require(!event.cancelled) { "취소한 행사에는 새 초대를 발급할 수 없어요" }
        val targetId = memberId.orEmpty()
        if (targetId.isNotBlank()) {
            val member = requireNotNull(dao.memberById(targetId)) { "회원을 찾지 못했어요" }
            require(member.status != "탈퇴") { "탈퇴 회원에게 새 링크를 발급할 수 없어요" }
            require(dao.mutationCount(eventId, "revoke-member", targetId) == 0) { "이 회원의 링크 취소가 대기 중이에요" }
        }
        val existing = dao.inviteFor(eventId, targetId)?.takeIf {
            runCatching { Instant.parse(it.expiresAt).isAfter(Instant.now()) }.getOrDefault(false)
        }
        if (existing != null) require(dao.mutationCount(eventId, "revoke-link", existing.id) == 0) { "링크 취소가 대기 중이에요" }
        val invite = existing ?: Invite(eventId = eventId, memberId = targetId,
            expiresAt = Instant.now().plusSeconds(30L * 86400).toString()).also { dao.put(it) }
        if (invite.url.isNotBlank()) return@withLock invite.url
        val url = api().createLink(eventId, invite)
        dao.setInviteUrl(invite.id, url)
        url
    }

    suspend fun revokeInvite(inviteId: String) = syncMutex.withLock {
        val invite = requireNotNull(dao.inviteById(inviteId)) { "초대 링크를 찾지 못했어요" }
        if (dao.mutationCount(invite.eventId, "revoke-link", invite.id) > 0) return@withLock
        val operationId = newId()
        val payload = JSONObject().put("operationId", operationId)
            .put("issuedOperationId", invite.operationId).toString()
        dao.put(PendingMutation(operationId = operationId, kind = "revoke-link", eventId = invite.eventId,
            targetId = invite.id, payload = payload))
    }

    suspend fun saveMember(member: Member) = syncMutex.withLock {
        require(member.status in setOf("활동", "휴면", "탈퇴")) { "회원 상태를 확인해 주세요" }
        db.withTransaction {
            if (dao.memberById(member.id) == null) dao.put(member)
            else require(dao.updateMemberDetails(member.id, member.name, member.phone, member.cohort,
                member.groupName, member.status, member.optedOut, member.semester, member.role) == 1) {
                "회원 저장 상태가 변경됐어요"
            }
            if (member.status == "탈퇴") queueMemberRevocations(member.id)
        }
    }

    suspend fun bulkUpdateMembers(ids: List<String>, status: String?, groupName: String?) = syncMutex.withLock {
        db.withTransaction {
            dao.bulkUpdateMembers(ids, status, groupName)
            if (status == "탈퇴") ids.forEach { queueMemberRevocations(it) }
        }
    }

    suspend fun revokeMemberInvites(memberId: String) = syncMutex.withLock {
        db.withTransaction { queueMemberRevocations(memberId) }
    }

    private suspend fun queueMemberRevocations(memberId: String) {
        val member = requireNotNull(dao.memberById(memberId)) { "회원을 찾지 못했어요" }
        require(member.status == "탈퇴") { "탈퇴 처리된 회원의 링크만 일괄 취소할 수 있어요" }
        val events = dao.onlineEvents().filter { it.serverVersion > 0 }
        if (events.isNotEmpty()) {
            val config = dao.clubConfigSnapshot() ?: ClubConfig()
            if (config.serverOrigin.isBlank()) {
                val stored = appContext.operatorSettings.data.first()[SettingsKeys.serverUrl].orEmpty()
                require(stored.isNotBlank()) { "기존 서버 주소를 확인해야 링크 취소를 대기할 수 있어요" }
                dao.put(config.copy(serverOrigin = checkedBaseUrl(stored, BuildConfig.DEBUG)))
            }
        }
        for (event in events) {
            if (dao.mutationCount(event.id, "revoke-member", memberId) > 0) continue
            val operationId = newId()
            val payload = JSONObject().put("operationId", operationId).put("memberId", memberId).toString()
            dao.put(PendingMutation(operationId = operationId, kind = "revoke-member", eventId = event.id,
                targetId = memberId, payload = payload))
        }
    }

    suspend fun resolveEventConflict(eventId: String, useServer: Boolean) = syncMutex.withLock {
        val event = requireNotNull(dao.eventById(eventId)) { "행사를 찾지 못했어요" }
        require(event.conflictJson.isNotBlank()) { "검토할 행사 충돌이 없어요" }
        val current = JSONObject(api().responses(eventId)).getJSONObject("event")
        val version = current.getInt("version")
        if (useServer) {
            fun optional(key: String) = if (current.isNull(key)) "" else current.getString(key)
            dao.acceptServerEvent(eventId, current.getString("title"), optional("startsAt"),
                current.getString("location"), current.getString("notice"), optional("deadline"),
                current.optInt("capacity").takeIf { !current.isNull("capacity") }, current.getLong("feeAmount"),
                current.getBoolean("cancelled"), current.getJSONArray("pollOptions").toString(),
                optional("pollDeadline"), version)
        } else {
            val operationId = newId()
            val payload = publishPayload(event.copy(serverVersion = version), operationId,
                dao.clubConfigSnapshot()?.clubName ?: "COM’s").toString()
            dao.rebaseEvent(eventId, version, operationId, payload)
        }
    }

    suspend fun recoverServerEvent(mutationId: String) = syncMutex.withLock {
        val mutation = requireNotNull(dao.mutationById(mutationId)) { "복구 대기 행사를 찾지 못했어요" }
        val origin = requireNotNull(dao.clubConfigSnapshot()).serverOrigin
        validateEventRecovery(mutation, origin)
        require(dao.eventById(mutation.eventId) == null) { "이미 이 기기에 행사가 있어요. 내용을 덮어쓰지 않아요" }
        val preview = JSONObject(mutation.payload).getJSONObject("snapshot")
        val fresh = JSONObject(api().responses(mutation.eventId))
        val recovered = recoveredServerEvent(fresh, mutation.eventId)
        if (!sameServerEventMetadata(preview.getJSONObject("event"), fresh.getJSONObject("event"))) {
            queueEventRecovery(mutation.eventId, fresh, origin)
            error("서버 행사 내용이 바뀌었어요. 갱신된 미리보기를 다시 확인해 주세요")
        }
        db.withTransaction {
            require(dao.eventById(mutation.eventId) == null) { "복구 중 이 기기 행사가 변경됐어요" }
            dao.put(recovered.copy(lastSyncAt = System.currentTimeMillis()))
            dao.deleteMutation(mutation.id)
        }
    }

    suspend fun queueResponse(eventId: String, memberId: String, response: String) = syncMutex.withLock {
        val event = requireNotNull(dao.eventById(eventId)) { "행사를 찾지 못했어요" }
        require(event.serverVersion > 0) { "행사를 먼저 게시해 주세요" }
        val member = requireNotNull(dao.memberById(memberId)) { "회원을 찾지 못했어요" }
        val snapshot = event.responsesJson.takeIf(String::isNotBlank)?.let(::JSONObject)
        val submissions = snapshot?.optJSONArray("submissions") ?: JSONArray()
        val current = (0 until submissions.length()).asSequence().map { submissions.getJSONObject(it) }
            .firstOrNull { it.optString("memberId") == memberId }
        val targetId = current?.optString("id") ?: "member:$memberId"
        require(dao.mutationCount(eventId, "response", targetId) == 0) { "이 회원의 응답 변경이 동기화 대기 중이에요" }
        val operationId = newId()
        val payload = JSONObject().put("operationId", operationId)
            .put("id", if (current == null) newId() else current.getString("id"))
            .put("eventVersion", snapshot?.optJSONObject("event")?.optInt("version") ?: event.serverVersion)
            .put("expectedVersion", current?.optInt("version") ?: 0).put("memberId", memberId)
            .put("name", member.name).put("rsvp", responseCode(response))
            .put("noticeAcknowledged", current?.optBoolean("noticeAcknowledged") ?: false)
            .put("selectedOptions", current?.optJSONArray("selectedOptions") ?: JSONArray()).toString()
        dao.put(PendingMutation(operationId = operationId, kind = "response", eventId = eventId,
            targetId = targetId, baseVersion = current?.optInt("version") ?: 0, payload = payload))
    }

    suspend fun linkSubmission(eventId: String, submissionId: String, memberId: String, baseVersion: Int) = syncMutex.withLock {
        requireNotNull(dao.memberById(memberId)) { "회원을 찾지 못했어요" }
        require(dao.mutationCount(eventId, "link", submissionId) == 0) { "이 응답은 회원 연결이 동기화 대기 중이에요" }
        val operationId = newId()
        val payload = JSONObject().put("operationId", operationId).put("expectedVersion", baseVersion)
            .put("memberId", memberId).toString()
        dao.put(PendingMutation(operationId = operationId, kind = "link", eventId = eventId,
            targetId = submissionId, baseVersion = baseVersion, payload = payload))
    }

    suspend fun rejectPayment(eventId: String, requestId: String, reason: String) = syncMutex.withLock {
        require(reason.isNotBlank()) { "반려 이유를 입력해 주세요" }
        require(dao.ledgerForRequest(requestId) == null) { "이미 원장에 연결된 요청이에요" }
        require(listOf("payment-prepare", "payment-confirm", "payment-reject").all {
            dao.mutationCount(eventId, it, requestId) == 0
        }) { "이 요청의 검토 작업이 이미 대기 중이에요" }
        val operationId = newId()
        val payload = JSONObject().put("operationId", operationId).put("status", "rejected")
            .put("reason", reason.trim()).toString()
        dao.put(PendingMutation(operationId = operationId, kind = "payment-reject", eventId = eventId,
            targetId = requestId, payload = payload))
    }

    suspend fun confirmPayment(eventId: String, requestId: String, chargeId: String, amount: Long, reason: String): Unit = syncMutex.withLock {
        val delta = requireLedgerEntry("납부 확인", amount, reason.trim())
        val charge = requireNotNull(dao.chargeById(chargeId)) { "회비 항목을 찾지 못했어요" }
        require(charge.eventId == null || charge.eventId == eventId) { "다른 행사에 연결된 회비예요" }
        require(dao.ledgerForRequest(requestId) == null) { "이미 원장에 연결된 요청이에요" }
        require(listOf("payment-prepare", "payment-confirm", "payment-reject").all {
            dao.mutationCount(eventId, it, requestId) == 0
        }) { "이 요청의 검토 작업이 이미 대기 중이에요" }
        val config = dao.clubConfigSnapshot() ?: ClubConfig().also { dao.put(it) }
        val remote = api()
        val request = paymentRequest(JSONObject(remote.responses(eventId)), requestId)
        require(request.getString("status") == "pending") { "이미 검토된 입금 요청이에요" }
        require(request.optJSONObject("review") == null) { "서버에 예약된 검토가 있어요. 영수증 복구를 사용해 주세요" }
        val memberId = request.getString("memberId")
        requireNotNull(dao.memberById(memberId)) { "연결된 회원을 찾지 못했어요" }
        require(-delta <= balance(chargeId, memberId, dao.ledgerSnapshot())) { "확인 금액이 현재 남은 회비보다 커요" }
        val operationId = newId()
        val payload = JSONObject().put("operationId", operationId).put("reviewId", newId())
            .put("ledgerEntryId", newId()).put("chargeId", chargeId)
            .put("confirmedAmount", -delta).put("ownerId", config.ownerId).put("reason", reason.trim()).toString()
        val pending = PendingMutation(operationId = operationId, kind = "payment-prepare", eventId = eventId,
            targetId = requestId, payload = payload)
        dao.put(pending)
        processMutation(remote, pending)
        dao.mutationById(pending.id)?.let { error(it.error.ifBlank { "서버 검토 준비를 마치지 못했어요" }) }
        dao.readyMutations().firstOrNull { it.kind == "payment-confirm" && it.targetId == requestId }
            ?.let {
                processMutation(remote, it)
                dao.mutationById(it.id)?.let { remaining ->
                    error(remaining.error.ifBlank { "서버에 납부 확인 결과를 전달하지 못했어요" })
                }
            }
        Unit
    }

    /** Explicit receipt recovery after restoring an older backup; never called by background sync. */
    suspend fun reconcilePayment(eventId: String, requestId: String) = syncMutex.withLock {
        val remote = api()
        val snapshot = JSONObject(remote.responses(eventId))
        val request = paymentRequest(snapshot, requestId)
        var review = requireNotNull(request.optJSONObject("review")) { "검토 영수증이 없어요" }
        var state = review.getString("state")
        require((state == "reserved" && request.getString("status") == "pending") ||
            (state == "confirmed" && request.getString("status") == "confirmed")) { "서버 검토 상태와 영수증이 맞지 않아요" }
        val config = requireNotNull(dao.clubConfigSnapshot()) { "운영자 설정이 없어요" }
        require(review.getString("ownerId") == config.ownerId) { "다른 운영자 기기의 검토 기록이에요" }
        if (state == "reserved") {
            val proof = JSONObject().put("operationId", newId())
            for (key in listOf("reviewId", "ledgerEntryId", "chargeId", "confirmedAmount", "ownerId", "reason"))
                proof.put(key, review.get(key))
            val checked = remote.preparePayment(eventId, requestId, proof.toString())
            review = checked.getJSONObject("review")
            for (key in listOf("reviewId", "ledgerEntryId", "chargeId", "confirmedAmount", "ownerId", "reason"))
                require(review.get(key).toString() == proof.get(key).toString()) { "서버 검토 영수증이 변경됐어요" }
            state = review.getString("state")
            require((state == "reserved" && checked.getJSONObject("request").getString("status") == "pending") ||
                (state == "confirmed" && checked.getJSONObject("request").getString("status") == "confirmed")) {
                "서버 검토 영수증이 변경됐어요"
            }
        }
        val memberId = request.getString("memberId")
        requireNotNull(dao.memberById(memberId)) { "연결된 회원을 찾지 못했어요" }
        val chargeId = review.getString("chargeId")
        val charge = requireNotNull(dao.chargeById(chargeId)) { "연결된 회비 항목을 찾지 못했어요" }
        require(charge.eventId == null || charge.eventId == eventId) { "회비 항목의 행사가 달라요" }
        val amount = review.getLong("confirmedAmount")
        require(amount > 0) { "검토 영수증의 금액을 확인해 주세요" }
        val ledgerId = review.getString("ledgerEntryId")
        val prepare = dao.mutationForTarget(eventId, "payment-prepare", requestId)
        val existingCommit = dao.mutationForTarget(eventId, "payment-confirm", requestId)
        if (prepare != null) {
            val original = JSONObject(prepare.payload)
            for (key in listOf("reviewId", "ledgerEntryId", "chargeId", "confirmedAmount", "ownerId", "reason"))
                require(original.get(key).toString() == review.get(key).toString()) { "저장된 검토 의도와 서버 영수증이 달라요" }
        }
        if (existingCommit != null) {
            val body = JSONObject(existingCommit.payload)
            require(body.getString("reviewId") == review.getString("reviewId") && body.getString("ledgerEntryId") == ledgerId) {
                "확정 대기 작업과 서버 영수증이 달라요"
            }
        }
        db.withTransaction {
            val existing = dao.ledgerForRequest(requestId)
            if (existing == null) {
                require(dao.entryById(ledgerId) == null) { "영수증 원장 ID가 다른 기록에 사용됐어요" }
                require(amount <= balance(chargeId, memberId, dao.ledgerSnapshot())) { "현재 원장 잔액과 영수증을 확인해 주세요" }
                dao.put(LedgerEntry(id = ledgerId, chargeId = chargeId, memberId = memberId,
                    kind = "납부 확인", deltaWon = -amount, reason = review.getString("reason"), actor = config.actor,
                    paymentRequestId = requestId))
            } else {
                require(existing.id == ledgerId && existing.chargeId == chargeId && existing.memberId == memberId &&
                    existing.kind == "납부 확인" && existing.deltaWon == -amount && existing.reason == review.getString("reason")) {
                    "로컬 원장과 서버 영수증이 달라요"
                }
            }
            if (prepare != null) dao.deleteMutation(prepare.id)
            if (state == "confirmed") {
                if (existingCommit != null) dao.deleteMutation(existingCommit.id)
            } else if (existingCommit == null) {
                val operationId = newId()
                dao.put(PendingMutation(operationId = operationId, kind = "payment-confirm", eventId = eventId,
                    targetId = requestId, payload = JSONObject().put("operationId", operationId)
                        .put("reviewId", review.getString("reviewId")).put("ledgerEntryId", ledgerId).toString()))
            }
        }
        if (state == "reserved") {
            val commit = requireNotNull(dao.mutationForTarget(eventId, "payment-confirm", requestId))
            if (commit.status == "error") dao.updateMutation(commit.id, "pending")
            processMutation(remote, commit)
            dao.mutationById(commit.id)?.let { error(it.error.ifBlank { "서버 확정 결과를 확인하지 못했어요" }) }
        }
    }

    suspend fun resolveConflict(mutationId: String, useServer: Boolean) = syncMutex.withLock {
        val mutation = requireNotNull(dao.mutationById(mutationId)) { "대기 작업을 찾지 못했어요" }
        require(mutation.status == "conflict") { "충돌 작업만 해결할 수 있어요" }
        require(mutation.kind == "response" || mutation.kind == "link" || mutation.kind == "payment-reject") {
            "납부 확인 충돌은 원장과 서버 영수증을 함께 검토해 주세요"
        }
        if (useServer) {
            refreshEvent(api(), mutation.eventId)
            dao.deleteMutation(mutationId)
        } else {
            require(mutation.kind == "response" || mutation.kind == "link") { "이 작업은 서버 기록을 확인한 후 다시 처리해 주세요" }
            val conflict = mutation.conflictJson.takeIf(String::isNotBlank)?.let(::JSONObject)
                ?: error("최신 서버 기록을 먼저 가져와 주세요")
            val code = conflict.optString("code")
            require(code == "CONFLICT" || code == "EVENT_CONFLICT") { "정원이나 업무 규칙으로 거절된 작업은 그대로 재제안할 수 없어요" }
            val snapshot = JSONObject(api().responses(mutation.eventId))
            val eventVersion = snapshot.getJSONObject("event").getInt("version")
            val original = JSONObject(mutation.payload)
            val memberId = original.getString("memberId")
            val submissions = snapshot.getJSONArray("submissions")
            val current = (0 until submissions.length()).asSequence().map { submissions.getJSONObject(it) }
                .firstOrNull { it.optString("id") == mutation.targetId || it.optString("memberId") == memberId }
            if (current != null && !current.isNull("memberId")) {
                require(current.getString("memberId") == memberId) { "응답이 다른 회원에 연결됐어요. 서버 기록을 확인해 주세요" }
            }
            val version = current?.optInt("version") ?: 0
            val operationId = newId()
            val targetId = if (mutation.kind == "response") current?.optString("id")
                ?: "member:$memberId" else mutation.targetId
            val payload = original.put("operationId", operationId)
                .put("expectedVersion", version).put("id", if (targetId.startsWith("member:")) newId() else targetId)
                .apply {
                    if (mutation.kind == "response") {
                        val eventChanged = eventVersion != original.getInt("eventVersion")
                        put("eventVersion", eventVersion)
                        put("noticeAcknowledged", if (eventChanged) false else current?.optBoolean("noticeAcknowledged") ?: false)
                        put("selectedOptions", current?.optJSONArray("selectedOptions") ?: JSONArray())
                    }
                }.toString()
            dao.rebaseMutation(mutationId, operationId, targetId, version, payload)
        }
    }

    suspend fun retryMutation(mutationId: String) = syncMutex.withLock {
        val mutation = requireNotNull(dao.mutationById(mutationId)) { "대기 작업을 찾지 못했어요" }
        require(mutation.kind != "event-recover") { "서버 행사 복구는 내용을 검토한 뒤 별도로 확정해 주세요" }
        require(mutation.status == "error") { "오류가 표시된 작업만 다시 시도할 수 있어요" }
        dao.updateMutation(mutationId, "pending")
    }

    suspend fun syncAll() = syncMutex.withLock {
        val remote = api()
        try {
            for (event in dao.onlineEvents()) {
                if (event.pendingPayload.isNotBlank() && event.conflictJson.isBlank()) {
                    try {
                        val result = remote.publishDetails(event.pendingPayload)
                        acknowledgePublication(event.id, result)
                    } catch (failure: ApiFailure) {
                        if (failure.status == 409) {
                            dao.markEventConflict(event.id, failure.currentJson.ifBlank { "{}" })
                            continue
                        }
                        else if (failure.definitiveNoSave) dao.clearPublishPending(event.id)
                        throw failure
                    }
                }
            }
            repeat(2) { for (mutation in dao.readyMutations()) processMutation(remote, mutation) }
            var cursor = (dao.clubConfigSnapshot() ?: ClubConfig()).changeCursor
            var resetOnce = false
            do {
                val page = try { remote.changes(cursor) } catch (failure: ApiFailure) {
                    if (failure.code != "CURSOR_RESET" || resetOnce) throw failure
                    cursor = 0
                    resetOnce = true
                    remote.changes(cursor)
                }
                val changed = page.getJSONArray("changes")
                val eventIds = mutableSetOf<String>()
                val latestByEvent = mutableMapOf<String, Long>()
                for (index in 0 until changed.length()) {
                    val change = changed.getJSONObject(index)
                    val eventId = change.getString("eventId")
                    eventIds += eventId
                    latestByEvent[eventId] = change.getLong("sequence")
                }
                if (cursor == 0L) eventIds += dao.onlineEvents().filter { it.serverVersion > 0 }.map(Event::id)
                val snapshots = mutableMapOf<String, String>()
                val missing = mutableListOf<String>()
                for (eventId in eventIds) {
                    val snapshot = remote.responses(eventId)
                    if (dao.eventById(eventId) == null) {
                        queueEventRecovery(eventId, JSONObject(snapshot), requireNotNull(dao.clubConfigSnapshot()).serverOrigin)
                        missing += eventId
                    } else snapshots[eventId] = snapshot
                }
                require(missing.isEmpty()) { "이 기기에 없는 서버 행사 ${missing.size}건 (${missing.joinToString()})을 검토해야 해요. 변경 위치는 유지했어요" }
                val next = page.getLong("nextCursor")
                require(next >= cursor && (!page.getBoolean("hasMore") || next > cursor)) { "동기화 위치가 진행되지 않아요" }
                cursor = next
                db.withTransaction {
                    val at = System.currentTimeMillis()
                    for ((eventId, snapshot) in snapshots) {
                        dao.setResponses(eventId, snapshot, at)
                        latestByEvent[eventId]?.let { dao.markEventCursor(eventId, it) }
                    }
                    dao.markSynced(cursor, at)
                }
            } while (page.getBoolean("hasMore"))
        } catch (error: Exception) {
            dao.markSyncError(error.message ?: "동기화 오류")
            throw error
        }
    }

    private suspend fun refreshEvent(remote: RemoteApi, eventId: String) {
        dao.setResponses(eventId, remote.responses(eventId), System.currentTimeMillis())
    }

    private fun paymentRequest(snapshot: JSONObject, requestId: String): JSONObject {
        val requests = snapshot.getJSONArray("paymentRequests")
        return (0 until requests.length()).asSequence().map { requests.getJSONObject(it) }
            .firstOrNull { it.getString("id") == requestId } ?: error("서버 입금 요청을 찾지 못했어요")
    }

    private suspend fun queueEventRecovery(eventId: String, snapshot: JSONObject, origin: String) {
        val event = recoveredServerEvent(snapshot, eventId)
        val existing = dao.mutationForTarget(eventId, "event-recover", eventId)
        val operationId = existing?.operationId ?: newId()
        val payload = JSONObject().put("operationId", operationId).put("serverOrigin", origin)
            .put("snapshot", snapshot).toString()
        if (existing == null) dao.put(PendingMutation(operationId = operationId, kind = "event-recover",
            eventId = eventId, targetId = eventId, baseVersion = event.serverVersion,
            payload = payload, status = "conflict", error = "서버 행사만 확인돼요. 회원·회비·출석은 복구하지 않아요"))
        else dao.updateEventRecovery(existing.id, event.serverVersion, payload,
            "서버 행사 내용을 다시 확인해 주세요. 회원·회비·출석은 복구하지 않아요")
    }

    private suspend fun processMutation(remote: RemoteApi, mutation: PendingMutation) {
        require(mutation.kind != "event-recover") { "서버 행사 복구는 운영자 검토 후에만 진행해요" }
        try {
            when (mutation.kind) {
                "response" -> if (mutation.targetId.startsWith("member:")) remote.createSubmission(mutation.eventId, mutation.payload)
                    else remote.updateSubmission(mutation.eventId, mutation.targetId, mutation.payload)
                "link" -> remote.linkSubmission(mutation.eventId, mutation.targetId, mutation.payload)
                "revoke-link" -> {
                    val result = remote.revokeLink(mutation.eventId, mutation.payload)
                    require(result.optBoolean("revoked")) { "서버가 링크 취소를 확인하지 않았어요" }
                    db.withTransaction {
                        dao.markInviteRevoked(mutation.targetId)
                        dao.deleteMutation(mutation.id)
                    }
                    return
                }
                "revoke-member" -> {
                    val result = remote.revokeMemberLinks(mutation.eventId, mutation.payload)
                    require(result.has("revokedCount") || result.optBoolean("revoked")) { "서버가 회원 링크 취소를 확인하지 않았어요" }
                    db.withTransaction {
                        dao.markMemberInvitesRevoked(mutation.eventId, mutation.targetId)
                        dao.deleteMutation(mutation.id)
                    }
                    return
                }
                "payment-reject" -> {
                    val result = remote.reviewPayment(mutation.eventId, mutation.targetId, mutation.payload)
                    require(result.getJSONObject("request").getString("status") == "rejected") { "서버 반려 상태가 일치하지 않아요" }
                }
                "payment-prepare" -> {
                    val original = JSONObject(mutation.payload)
                    val result = remote.preparePayment(mutation.eventId, mutation.targetId, mutation.payload)
                    val review = result.getJSONObject("review")
                    for (key in listOf("reviewId", "ledgerEntryId", "chargeId", "confirmedAmount", "ownerId", "reason"))
                        require(review.get(key).toString() == original.get(key).toString()) { "서버 검토 영수증이 준비한 작업과 달라요" }
                    require(review.getString("state") == "reserved") { "서버에서 이미 확정한 요청이에요. 원장 복구는 별도 확인이 필요해요" }
                    val request = result.getJSONObject("request")
                    require(request.getString("id") == mutation.targetId) { "서버 입금 요청 ID가 대기 작업과 달라요" }
                    require(request.getString("status") == "pending") { "입금 요청이 검토 대기 상태가 아니에요" }
                    val directMemberId = if (!request.has("memberId") || request.isNull("memberId")) null
                        else request.get("memberId") as? String ?: error("서버 회원 ID 형식을 확인해 주세요")
                    val memberId = directMemberId ?: run {
                        val fresh = paymentRequest(JSONObject(remote.responses(mutation.eventId)), mutation.targetId)
                        require(fresh.getString("id") == mutation.targetId && fresh.getString("status") == "pending") {
                            "예약한 입금 요청의 최신 상태가 달라요"
                        }
                        val latestReview = requireNotNull(fresh.optJSONObject("review")) { "최신 입금 요청의 예약 영수증이 없어요" }
                        require(latestReview.getString("state") == "reserved") { "최신 입금 요청이 예약 상태가 아니에요" }
                        for (key in listOf("reviewId", "ledgerEntryId", "chargeId", "confirmedAmount", "ownerId", "reason"))
                            require(latestReview.get(key).toString() == original.get(key).toString()) {
                                "최신 예약 영수증이 저장된 검토 의도와 달라요"
                            }
                        fresh.get("memberId") as? String ?: error("최신 입금 요청에 연결된 회원이 없어요")
                    }
                    requireNotNull(dao.memberById(memberId)) { "연결된 회원을 찾지 못했어요" }
                    val amount = original.getLong("confirmedAmount")
                    require(amount > 0) { "확인 금액을 확인해 주세요" }
                    val config = requireNotNull(dao.clubConfigSnapshot())
                    val commitOpId = newId()
                    val commit = PendingMutation(operationId = commitOpId, kind = "payment-confirm",
                        eventId = mutation.eventId, targetId = mutation.targetId,
                        payload = JSONObject().put("operationId", commitOpId)
                            .put("reviewId", original.getString("reviewId"))
                            .put("ledgerEntryId", original.getString("ledgerEntryId")).toString())
                    db.withTransaction {
                        require(dao.ledgerForRequest(mutation.targetId) == null) { "이미 원장에 연결된 요청이에요" }
                        require(amount <= balance(original.getString("chargeId"), memberId, dao.ledgerSnapshot())) { "확인 금액이 현재 남은 회비보다 커요" }
                        dao.put(LedgerEntry(id = original.getString("ledgerEntryId"), chargeId = original.getString("chargeId"),
                            memberId = memberId, kind = "납부 확인", deltaWon = -amount,
                            reason = original.getString("reason"), actor = config.actor, paymentRequestId = mutation.targetId))
                        dao.put(commit)
                        dao.deleteMutation(mutation.id)
                    }
                    return
                }
                "payment-confirm" -> {
                    val result = remote.commitPayment(mutation.eventId, mutation.targetId, mutation.payload)
                    require(result.getJSONObject("request").getString("status") == "confirmed") { "서버 납부 검토 상태가 일치하지 않아요" }
                }
                else -> error("알 수 없는 동기화 작업: ${mutation.kind}")
            }
            dao.deleteMutation(mutation.id)
        } catch (failure: ApiFailure) {
            when {
                failure.status == 409 -> dao.updateMutation(mutation.id, "conflict", failure.message.orEmpty(),
                    JSONObject().put("code", failure.code)
                        .put("current", failure.currentJson.takeIf(String::isNotBlank)?.let(::JSONObject) ?: JSONObject.NULL).toString())
                failure.definitiveNoSave -> dao.updateMutation(mutation.id, "error", failure.message.orEmpty())
                else -> throw failure
            }
        } catch (failure: IOException) {
            throw failure
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            dao.updateMutation(mutation.id, "error", failure.message ?: "작업 내용 확인이 필요해요")
        }
    }

    private suspend fun acknowledgePublication(eventId: String, result: JSONObject) {
        val prepared = requireNotNull(dao.eventById(eventId)) { "행사를 찾지 못했어요" }
        val requested = JSONObject(prepared.pendingPayload).optJSONArray("initialResponses") ?: JSONArray()
        val imported = result.optJSONArray("initialResponses") ?: JSONArray()
        val requestedIds = (0 until requested.length()).map { requested.getJSONObject(it).getString("id") }.toSet()
        val importedIds = (0 until imported.length()).map { imported.getJSONObject(it).getString("id") }.toSet()
        require(requestedIds == importedIds && requested.length() == imported.length()) { "서버의 수동 응답 수락 결과가 원본 요청과 달라요" }
        db.withTransaction {
            for (index in 0 until imported.length()) {
                val item = imported.getJSONObject(index)
                require(dao.markRsvpImported(item.getString("id"), item.getString("id"), item.getInt("version")) == 1) {
                    "수동 응답의 로컬 기록을 찾지 못했어요"
                }
            }
            dao.markPublished(eventId, result.getJSONObject("event").getInt("version"), false)
        }
    }
}

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        if (applicationContext.operatorSettings.data.first()[SettingsKeys.syncEnabled] == true)
            SyncEngine(applicationContext).syncAll()
        Result.success()
    } catch (_: Exception) {
        Result.retry()
    }
}

fun scheduleSync(context: Context, enabled: Boolean) {
    val manager = WorkManager.getInstance(context)
    if (!enabled) {
        manager.cancelUniqueWork("operator-sync")
        return
    }
    val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
    manager.enqueueUniquePeriodicWork("operator-sync", ExistingPeriodicWorkPolicy.KEEP, request)
}
