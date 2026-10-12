package kr.coms.companion

data class ImportRow(val row: Int, val name: String, val phone: String, val cohort: String, val group: String, val issue: String?)

fun previewMembersCsv(content: String, existing: List<Member>): List<ImportRow> {
    // shortcut: 줄바꿈을 포함한 CSV 셀은 지원하지 않음, 실제 명단에 필요하면 완전한 CSV 파서로 교체
    val lines = content.removePrefix("\uFEFF").lines().filter(String::isNotBlank)
    if (lines.isEmpty()) return emptyList()
    val headers = csvCells(lines.first()).map(String::trim)
    fun column(vararg labels: String): Int = headers.indexOfFirst { it in labels }
    val nameIndex = column("이름", "name")
    val phoneIndex = column("전화번호", "휴대전화", "phone")
    val cohortIndex = column("기수", "학년", "cohort")
    val groupIndex = column("소모임", "그룹", "group")
    if (nameIndex < 0) return listOf(ImportRow(1, "", "", "", "", "이름 열이 필요해요"))
    val knownPhones = existing.map { normalizePhone(it.phone) }.filter(String::isNotBlank).toMutableSet()
    return lines.drop(1).mapIndexed { index, line ->
        val fields = csvCells(line)
        fun at(i: Int): String = if (i < 0) "" else fields.getOrNull(i)?.trim().orEmpty()
        val name = at(nameIndex)
        val phone = at(phoneIndex)
        val digits = normalizePhone(phone)
        val issue = when {
            name.isBlank() -> "이름 없음"
            phone.isNotBlank() && !validPhone(phone) -> "전화번호 형식 오류"
            digits.isNotBlank() && !knownPhones.add(digits) -> "기존·파일 내 번호 중복"
            else -> null
        }
        ImportRow(index + 2, name, phone, at(cohortIndex), at(groupIndex), issue)
    }
}

private fun csvCells(line: String): List<String> {
    val values = mutableListOf<String>()
    val cell = StringBuilder()
    var quoted = false
    var i = 0
    while (i < line.length) {
        when {
            line[i] == '"' && quoted && i + 1 < line.length && line[i + 1] == '"' -> { cell.append('"'); i++ }
            line[i] == '"' -> quoted = !quoted
            line[i] == ',' && !quoted -> { values += cell.toString(); cell.clear() }
            else -> cell.append(line[i])
        }
        i++
    }
    values += cell.toString()
    return values
}
