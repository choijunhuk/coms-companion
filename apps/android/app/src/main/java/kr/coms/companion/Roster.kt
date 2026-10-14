package kr.coms.companion

import androidx.room.withTransaction
import java.time.Instant
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

private const val ROSTER_SOURCE = "coms-eligible-members"
// A fixed namespace keeps imported IDs stable without matching manual records by name or phone.
private const val ROSTER_ID_NAMESPACE = "kr.coms.companion:coms-eligible-members:v1:"

data class RosterMember(val studentId: String, val name: String, val phone: String, val generation: String) {
    val id: String get() = UUID.nameUUIDFromBytes((ROSTER_ID_NAMESPACE + studentId).toByteArray(Charsets.UTF_8)).toString()
}

data class RosterSnapshot(val updatedAt: String, val members: List<RosterMember>)

data class RosterPreview(
    val newCount: Int,
    val updatedCount: Int,
    val localOnlyCount: Int,
    val missingOrInvalidPhoneCount: Int,
    val duplicatePhoneCount: Int,
)

fun parseRosterSnapshot(json: JSONObject): RosterSnapshot {
    val updatedAt = json.opt("updatedAt") as? String ?: error("회원 명부 갱신 시간이 없어요")
    val rows = json.opt("members") as? JSONArray ?: error("회원 명부 형식이 올바르지 않아요")
    val members = (0 until rows.length()).map { index ->
        val row = rows.opt(index) as? JSONObject ?: error("회원 명부 ${index + 1}행이 올바르지 않아요")
        fun field(key: String): String = (row.opt(key) as? String)?.trim()
            ?: error("회원 명부 ${index + 1}행의 $key 값이 올바르지 않아요")
        RosterMember(field("studentId"), field("name"), field("phone"), field("generation"))
    }
    return validatedRosterSnapshot(json.optString("source"), updatedAt, members)
}

internal fun validatedRosterSnapshot(source: String, updatedAt: String, members: List<RosterMember>): RosterSnapshot {
    require(source == ROSTER_SOURCE) { "COMS 회원 명부 출처를 확인할 수 없어요" }
    require(runCatching { Instant.parse(updatedAt) }.isSuccess) { "회원 명부 갱신 시간이 올바르지 않아요" }
    require(members.isNotEmpty()) { "회원 명부가 비어 있어요" }
    require(members.all { it.studentId.isNotBlank() && it.name.isNotBlank() }) { "회원 명부에 학번·이름이 빈 행이 있어요" }
    require(members.map { it.studentId }.distinct().size == members.size) { "회원 명부에 중복 학번이 있어요" }
    return RosterSnapshot(updatedAt, members)
}

internal fun importedMember(row: RosterMember, existing: Member?): Member =
    existing?.copy(name = row.name, phone = row.phone, cohort = row.generation)
        ?: Member(id = row.id, name = row.name, phone = row.phone, cohort = row.generation)

fun previewRoster(snapshot: RosterSnapshot, current: List<Member>): RosterPreview {
    val byId = current.associateBy(Member::id)
    val imported = snapshot.members.map { importedMember(it, byId[it.id]) }
    val rosterIds = imported.map(Member::id).toSet()
    val prospective = current.filterNot { it.id in rosterIds } + imported
    val phoneCounts = prospective.map { normalizePhone(it.phone) }.filter(String::isNotBlank).groupingBy { it }.eachCount()
    return RosterPreview(
        newCount = imported.count { it.id !in byId },
        updatedCount = imported.count { byId[it.id] != null && byId[it.id] != it },
        localOnlyCount = current.count { it.id !in rosterIds },
        missingOrInvalidPhoneCount = imported.count { !validPhone(it.phone) },
        duplicatePhoneCount = imported.count { validPhone(it.phone) && phoneCounts[normalizePhone(it.phone)]!! > 1 },
    )
}

suspend fun applyRoster(database: AppDatabase, snapshot: RosterSnapshot): RosterPreview = database.withTransaction {
    val dao = database.dao()
    val current = dao.memberSnapshot()
    val byId = current.associateBy(Member::id)
    val preview = previewRoster(snapshot, current)
    snapshot.members.forEach { row ->
        val existing = byId[row.id]
        val updated = importedMember(row, existing)
        if (existing == null) dao.put(updated)
        else if (updated != existing) require(dao.updateMemberDetails(updated.id, updated.name, updated.phone, updated.cohort,
            updated.groupName, updated.status, updated.optedOut, updated.semester, updated.role) == 1) { "회원 명부 적용 중 회원이 변경됐어요" }
    }
    preview
}
