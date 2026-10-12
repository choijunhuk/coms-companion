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
): List<Member> = members.filter { member ->
    member.status == "활동" && !member.optedOut && validPhone(member.phone) &&
        members.count { normalizePhone(it.phone) == normalizePhone(member.phone) } == 1 &&
        (group == null || member.groupName == group) &&
        (chargeId == null || balance(chargeId, member.id, entries) > 0)
}

data class RenderResult(val text: String, val missing: Set<String>)

fun renderTemplate(body: String, member: Member, event: Event? = null, charge: Charge? = null, entries: List<LedgerEntry> = emptyList()): RenderResult {
    val values = mapOf(
        "이름" to member.name,
        "행사명" to (event?.title ?: ""),
        "일시" to (event?.whenText ?: ""),
        "장소" to (event?.place ?: ""),
        "납부항목" to (charge?.title ?: ""),
        "남은금액" to (if (charge == null) "" else "%,d원".format(balance(charge.id, member.id, entries).coerceAtLeast(0))),
        "마감" to (event?.deadline ?: charge?.dueDate ?: ""),
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
    require(reason.isNotBlank()) { "기록 이유를 입력해 주세요" }
    return when (kind) {
        "청구", "환불" -> amountWon
        "납부 확인", "면제" -> -amountWon
        "정정 증가" -> amountWon
        "정정 감소" -> -amountWon
        else -> error("지원하지 않는 원장 유형")
    }
}

fun publicShareSafe(text: String, members: List<Member>): Boolean =
    text.isNotBlank() && !text.contains('{') && !text.contains('}') &&
        !Regex("01[0-9][- 0-9]{8,}").containsMatchIn(text) &&
        !Regex("[0-9][0-9,]*원").containsMatchIn(text) &&
        !text.contains("token=", ignoreCase = true) && !text.contains("/respond#", ignoreCase = true) &&
        members.none { it.name.isNotBlank() && text.contains(it.name) }
