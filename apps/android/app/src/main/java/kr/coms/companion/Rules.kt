package kr.coms.companion

private val variable = Regex("\\{([^{}]*)\\}")

fun normalizePhone(value: String): String = value.filter(Char::isDigit)

fun validPhone(value: String): Boolean {
    val digits = normalizePhone(value)
    return digits.length in 10..11 && digits.startsWith("01")
}

fun balance(chargeId: String, memberId: String, entries: List<LedgerEntry>): Long =
    entries.asSequence().filter { it.chargeId == chargeId && it.memberId == memberId }.sumOf { it.deltaWon }

fun eligibleMembers(
    members: List<Member>,
    entries: List<LedgerEntry>,
    chargeId: String? = null,
    group: String? = null,
    cohort: String? = null,
    targetPolicy: String = "all",
    eventId: String? = null,
    rsvps: List<Rsvp> = emptyList(),
    currentSemester: String = "",
    selectedIds: Set<String> = emptySet(),
    excludedIds: Set<String> = emptySet(),
): List<Member> = members.filter { member ->
    member.status == "활동" && !member.optedOut && validPhone(member.phone) &&
        members.count { normalizePhone(it.phone) == normalizePhone(member.phone) } == 1 &&
        (group == null || member.groupName == group) &&
        (cohort == null || member.cohort == cohort) && member.id !in excludedIds &&
        (selectedIds.isEmpty() || member.id in selectedIds) &&
        (chargeId == null || balance(chargeId, member.id, entries) > 0) &&
        when (targetPolicy) {
            "all" -> true
            "unpaid" -> chargeId != null && balance(chargeId, member.id, entries) > 0
            "unanswered" -> eventId != null && rsvps.none { it.eventId == eventId && it.memberId == member.id && it.response != "미응답" }
            "new" -> currentSemester.isNotBlank() && member.semester == currentSemester
            "selected" -> selectedIds.isNotEmpty() && member.id in selectedIds
            else -> false
        }
}

data class RenderResult(val text: String, val missing: Set<String>)

fun renderTemplate(body: String, member: Member, event: Event? = null, charge: Charge? = null, entries: List<LedgerEntry> = emptyList(), accountGuide: String = "", responseLink: String = ""): RenderResult {
    val values = mapOf(
        "이름" to member.name,
        "행사명" to (event?.title ?: ""),
        "일시" to (event?.whenText ?: ""),
        "장소" to (event?.place ?: ""),
        "납부항목" to (charge?.title ?: ""),
        "남은금액" to (if (charge == null) "" else "%,d원".format(balance(charge.id, member.id, entries).coerceAtLeast(0))),
        "마감" to (event?.deadline ?: charge?.dueDate ?: ""),
        "계좌안내" to accountGuide,
        "계좌 안내" to accountGuide,
        "응답링크" to responseLink,
        "응답 링크" to responseLink,
    )
    val missing = mutableSetOf<String>()
    val text = variable.replace(body) { match ->
        values[match.groupValues[1]]?.takeIf(String::isNotBlank) ?: match.value.also { missing += match.groupValues[1].ifBlank { "빈 변수" } }
    }
    if (missing.isEmpty() && ('{' in text || '}' in text)) missing += "치환되지 않은 변수"
    return RenderResult(text, missing)
}

fun requireLedgerEntry(kind: String, amountWon: Long, reason: String): Long {
    require(amountWon > 0) { "금액은 0원보다 커야 해요" }
    require(amountWon <= 1_000_000_000L) { "한 건의 금액은 10억 원 이하로 입력해 주세요" }
    require(reason.isNotBlank()) { "기록 이유를 입력해 주세요" }
    return when (kind) {
        "청구", "환불" -> amountWon
        "납부 확인", "면제" -> -amountWon
        "정정 증가" -> amountWon
        "정정 감소" -> -amountWon
        else -> error("지원하지 않는 원장 유형")
    }
}

fun publicShareSafe(text: String, members: List<Member>, allowedCommonUrls: Set<String> = emptySet()): Boolean {
    val urls = Regex("https?://\\S+")
    val redacted = urls.replace(text) { match ->
        val url = match.value
        if (url.startsWith("https://") && "/respond#" in url && url in allowedCommonUrls)
            "[공통 응답 링크]" else url
    }
    return text.isNotBlank() && !redacted.contains('{') && !redacted.contains('}') &&
        !Regex("01[0-9][- 0-9]{8,}").containsMatchIn(redacted) &&
        !Regex("[0-9][0-9,]*원").containsMatchIn(redacted) &&
        !redacted.contains("token=", ignoreCase = true) && !redacted.contains("/respond#", ignoreCase = true) &&
        members.none { it.name.isNotBlank() && redacted.contains(it.name) }
}
