package kr.coms.companion

import android.database.Cursor
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class BackupPreview(val createdAt: Long, val counts: Map<String, Int>, val serverOrigin: String)

/** Only table and column identifiers discovered from this application's Room schema enter SQL. */
object Backup {
    private const val maxRowsPerTable = 5_000
    private const val maxTotalRows = 20_000
    private const val maxCellBytes = 1_048_576
    private val identifier = Regex("[A-Za-z_][A-Za-z_0-9]*")

    private data class Column(val name: String, val type: String, val required: Boolean, val primary: Boolean)

    suspend fun capture(database: AppDatabase): ByteArray = withContext(Dispatchers.IO) {
        database.withTransaction {
            val db = database.openHelper.writableDatabase
            val tables = JSONObject()
            var total = 0
            for (table in tableNames(db)) {
                val columns = columns(db, table)
                val rows = JSONArray()
                db.query("SELECT * FROM ${quote(table)}").use { cursor ->
                    while (cursor.moveToNext()) {
                        require(rows.length() < maxRowsPerTable && ++total <= maxTotalRows) { "백업 행이 너무 많아요" }
                        val row = JSONArray()
                        columns.indices.forEach { index -> row.put(cursorValue(cursor, index)) }
                        rows.put(row)
                    }
                }
                tables.put(table, JSONObject().put("columns", columnJson(columns)).put("rows", rows))
            }
            JSONObject().put("format", 1).put("createdAt", System.currentTimeMillis())
                .put("tables", tables).toString().toByteArray(Charsets.UTF_8).also {
                    require(it.size in 1..32 * 1024 * 1024) { "백업 크기가 너무 커요" }
                }
        }
    }

    suspend fun preview(database: AppDatabase, plaintext: ByteArray): BackupPreview = withContext(Dispatchers.IO) {
        validate(database, plaintext).second
    }

    suspend fun restore(database: AppDatabase, plaintext: ByteArray) = withContext(Dispatchers.IO) {
        val (tables, _) = validate(database, plaintext)
        database.withTransaction {
            val db = database.openHelper.writableDatabase
            // Recheck the live schema in the write transaction; any insert failure rolls all tables back.
            require(tables.keys().asSequence().toSet() == tableNames(db).toSet()) { "복원 중 데이터 구조가 바뀌었어요" }
            for (table in tableNames(db).asReversed()) db.execSQL("DELETE FROM ${quote(table)}")
            for (table in tableNames(db)) {
                val columns = columns(db, table)
                val saved = tables.getJSONObject(table)
                require(saved.getJSONArray("columns").toString() == columnJson(columns).toString()) { "복원 중 데이터 구조가 바뀌었어요" }
                val sql = "INSERT INTO ${quote(table)} (${columns.joinToString(",") { quote(it.name) }}) VALUES (${columns.joinToString(",") { "?" }})"
                val rows = saved.getJSONArray("rows")
                for (index in 0 until rows.length()) {
                    val row = rows.getJSONArray(index)
                    db.execSQL(sql, Array<Any?>(columns.size) { cell -> sqlValue(row.get(cell)) })
                }
            }
        }
    }

    private fun validate(database: AppDatabase, plaintext: ByteArray): Pair<JSONObject, BackupPreview> {
        require(plaintext.size in 1..32 * 1024 * 1024) { "백업 내용 크기가 잘못됐어요" }
        val root = JSONObject(String(plaintext, Charsets.UTF_8))
        require(root.keys().asSequence().toSet() == setOf("format", "createdAt", "tables") && root.get("format") == 1) { "지원하지 않는 백업 내용이에요" }
        val createdAt = root.get("createdAt") as? Long ?: error("백업 날짜가 잘못됐어요")
        require(createdAt > 0 && createdAt <= System.currentTimeMillis() + 86_400_000L) { "백업 날짜가 잘못됐어요" }
        val tables = root.getJSONObject("tables")
        val db = database.openHelper.writableDatabase
        val names = tableNames(db)
        require(tables.keys().asSequence().toSet() == names.toSet()) { "이 앱 버전과 백업 데이터 구조가 달라요" }
        val counts = linkedMapOf<String, Int>()
        var total = 0
        for (table in names) {
            val saved = tables.getJSONObject(table)
            require(saved.keys().asSequence().toSet() == setOf("columns", "rows")) { "백업 테이블 정보가 잘못됐어요" }
            val columns = columns(db, table)
            require(saved.getJSONArray("columns").toString() == columnJson(columns).toString()) { "백업 컬럼이 앱과 달라요" }
            val rows = saved.getJSONArray("rows")
            require(rows.length() <= maxRowsPerTable && (total + rows.length()).also { total = it } <= maxTotalRows) { "백업 행이 너무 많아요" }
            val primaryIndexes = columns.indices.filter { columns[it].primary }
            val seen = HashSet<String>()
            for (index in 0 until rows.length()) {
                val row = rows.getJSONArray(index)
                require(row.length() == columns.size) { "백업 행의 컬럼 수가 잘못됐어요" }
                columns.forEachIndexed { cell, column -> validateCell(row.get(cell), column) }
                require(primaryIndexes.isNotEmpty()) { "기본 키 없는 테이블은 복원할 수 없어요" }
                val key = primaryIndexes.joinToString("\u0000") { row.get(it).toString() }
                require(seen.add(key)) { "백업에 중복된 행이 있어요" }
            }
            validateUniqueIndexes(db, table, columns, rows)
            counts[table] = rows.length()
        }
        val configRows = tables.optJSONObject("ClubConfig")?.getJSONArray("rows")
        val configColumns = names.takeIf { "ClubConfig" in it }?.let { columns(db, "ClubConfig") }
        if (configRows != null) {
            require(configRows.length() <= 1) { "동아리 설정이 중복됐어요" }
            val idIndex = configColumns!!.indexOfFirst { it.name == "id" }
            if (configRows.length() == 1 && idIndex >= 0) require((configRows.getJSONArray(0).get(idIndex) as Number).toLong() == 1L) { "동아리 설정 ID가 잘못됐어요" }
        }
        validateDomain(db, tables)
        val originIndex = configColumns?.indexOfFirst { it.name == "serverOrigin" } ?: -1
        val origin = if (originIndex >= 0 && configRows != null && configRows.length() > 0) configRows.getJSONArray(0).getString(originIndex) else ""
        return tables to BackupPreview(createdAt, counts, origin)
    }

    private fun validateDomain(db: SupportSQLiteDatabase, tables: JSONObject) {
        fun rows(table: String): List<Map<String, Any>> {
            if (!tables.has(table)) return emptyList()
            val names = columns(db, table).map { it.name }
            val source = tables.getJSONObject(table).getJSONArray("rows")
            return (0 until source.length()).map { index ->
                val row = source.getJSONArray(index)
                names.indices.associate { names[it] to row.get(it) }
            }
        }
        fun Map<String, Any>.string(name: String): String = getValue(name) as String
        fun Map<String, Any>.number(name: String): Long = (getValue(name) as Number).toLong()
        fun Map<String, Any>.optional(name: String): String? = getValue(name).takeUnless { it == JSONObject.NULL } as String?
        val members = rows("Member")
        val events = rows("Event")
        val charges = rows("Charge")
        val drafts = rows("ContactDraft")
        val memberIds = members.map { it.string("id") }.toSet()
        val eventIds = events.map { it.string("id") }.toSet()
        val chargeIds = charges.map { it.string("id") }.toSet()
        val draftIds = drafts.map { it.string("id") }.toSet()
        fun exists(id: String?, ids: Set<String>) = id.isNullOrBlank() || id in ids
        fun jsonObject(value: String) { if (value.isNotBlank()) JSONObject(value) }
        fun jsonArray(value: String) { JSONArray(value) }
        members.forEach { require(it.string("name").isNotBlank() && it.string("status") in setOf("활동", "휴면", "탈퇴")) { "회원 정보가 잘못됐어요" } }
        events.forEach {
            require(it.number("feeWon") in 0..1_000_000_000L && it.number("serverVersion") in 0L..Int.MAX_VALUE.toLong() && it.number("changeCursor") >= 0) { "행사 금액·버전이 잘못됐어요" }
            require(it.getValue("capacity") == JSONObject.NULL || it.number("capacity") in 1L..Int.MAX_VALUE.toLong()) { "행사 정원이 잘못됐어요" }
            val operation = it.string("pendingOperationId")
            val payload = it.string("pendingPayload")
            require(operation.isBlank() == payload.isBlank()) { "행사 게시 대기 내용이 잘못됐어요" }
            if (operation.isNotBlank()) require(JSONObject(payload).getString("operationId") == operation) { "행사 게시 작업 ID가 달라요" }
            jsonArray(it.string("pollOptionsJson"))
            jsonObject(it.string("responsesJson"))
            jsonObject(it.string("conflictJson"))
        }
        val invites = rows("Invite")
        val inviteById = invites.associateBy { it.string("id") }
        require(invites.map { it.string("operationId") }.toSet().size == invites.size) { "초대 발급 작업 ID가 중복됐어요" }
        invites.forEach {
            require(it.string("eventId") in eventIds && exists(it.string("memberId"), memberIds)) { "초대 대상이 존재하지 않아요" }
            require(it.string("operationId").isNotBlank()) { "초대 작업 ID가 비었어요" }
        }
        rows("Rsvp").forEach {
            require(it.string("eventId") in eventIds && it.string("memberId") in memberIds) { "응답 대상이 존재하지 않아요" }
            require(it.string("response") in setOf("참석", "불참", "미정", "미응답")) { "응답 상태가 잘못됐어요" }
            require(it.number("serverVersion") in 0L..Int.MAX_VALUE.toLong()) { "응답 버전이 잘못됐어요" }
        }
        charges.forEach { require(exists(it.optional("eventId"), eventIds)) { "회비 행사가 존재하지 않아요" } }
        val ledger = rows("LedgerEntry")
        val ledgerIds = ledger.map { it.string("id") }.toSet()
        ledger.forEach {
            require(it.string("chargeId") in chargeIds && it.string("memberId") in memberIds) { "회비 원장 대상이 존재하지 않아요" }
            val delta = it.number("deltaWon")
            require(delta != Long.MIN_VALUE && kotlin.math.abs(delta) in 1..1_000_000_000L) { "회비 원장 금액이 잘못됐어요" }
            require(requireLedgerEntry(it.string("kind"), kotlin.math.abs(delta), it.string("reason")) == delta) { "회비 원장 금액 부호가 잘못됐어요" }
            require(exists(it.optional("reversesEntryId"), ledgerIds)) { "정정할 원장 기록이 존재하지 않아요" }
        }
        rows("Duty").forEach { require(exists(it.optional("eventId"), eventIds)) { "할 일 행사가 존재하지 않아요" } }
        drafts.forEach {
            require(it.number("currentIndex") in 0L..Int.MAX_VALUE.toLong()) { "연락 진행 위치가 잘못됐어요" }
            require(exists(it.optional("eventId"), eventIds) && exists(it.optional("chargeId"), chargeIds)) { "연락 초안 대상이 존재하지 않아요" }
            require(it.string("targetPolicy") in setOf("all", "selected", "unanswered", "unpaid", "new")) { "연락 대상 방식이 잘못됐어요" }
            for (field in listOf("selectedMemberIdsJson", "excludedMemberIdsJson")) {
                val ids = JSONArray(it.string(field))
                for (index in 0 until ids.length()) require(ids.get(index) is String && ids.getString(index) in memberIds) { "연락 초안 회원이 존재하지 않아요" }
            }
        }
        rows("Reminder").forEach { require(exists(it.optional("draftId"), draftIds)) { "알림 초안이 존재하지 않아요" } }
        rows("ContactRecord").forEach { require(exists(it.string("memberId"), memberIds)) { "연락 기록 회원이 존재하지 않아요" } }
        rows("ExpenseEntry").forEach {
            require(it.string("eventId") in eventIds && it.string("category").isNotBlank() && it.string("memo").isNotBlank()) { "지출 기록 대상·내용이 잘못됐어요" }
            requireExpenseAmount(it.number("amountWon"))
        }
        val assets = rows("ClubAsset")
        val assetIds = assets.map { it.string("id") }.toSet()
        assets.forEach { require(it.string("name").isNotBlank()) { "물품명이 비었어요" } }
        rows("AssetLoan").forEach {
            require(it.string("assetId") in assetIds && it.string("memberId") in memberIds) { "물품 대여 대상이 존재하지 않아요" }
        }
        rows("Applicant").forEach {
            require(it.string("name").isNotBlank() && exists(it.optional("memberId"), memberIds)) { "지원자 정보가 잘못됐어요" }
        }
        val meetings = rows("MeetingNote")
        val meetingIds = meetings.map { it.string("id") }.toSet()
        meetings.forEach { require(it.string("title").isNotBlank()) { "회의 제목이 비었어요" } }
        rows("MeetingDecision").forEach {
            require(it.string("meetingId") in meetingIds && it.string("text").isNotBlank()) { "회의 결정 사항이 잘못됐어요" }
        }
        val config = rows("ClubConfig").singleOrNull()
        if (config != null) require(config.string("ownerId").isNotBlank() && config.number("changeCursor") >= 0) { "동아리 연결 정보가 잘못됐어요" }
        val origin = config?.string("serverOrigin").orEmpty()
        val recoveryTargets = HashSet<String>()
        rows("PendingMutation").forEach {
            val kind = it.string("kind")
            require(origin.isNotBlank() && (kind == "event-recover" || it.string("eventId") in eventIds)) { "온라인 작업의 행사·서버 출처가 없어요" }
            require(kind in setOf("response", "link", "revoke-link", "revoke-member", "payment-reject", "payment-prepare", "payment-confirm", "event-recover")) { "온라인 작업 유형이 잘못됐어요" }
            require(it.string("status") in setOf("pending", "retry", "error", "conflict") && it.number("baseVersion") in 0L..Int.MAX_VALUE.toLong()) { "온라인 작업 상태가 잘못됐어요" }
            val payload = JSONObject(it.string("payload"))
            require(payload.getString("operationId") == it.string("operationId")) { "온라인 작업 ID가 달라요" }
            when (kind) {
                "event-recover" -> {
                    val eventId = it.string("eventId")
                    require(eventId !in eventIds && recoveryTargets.add(eventId)) { "복구할 서버 행사 대상이 중복되거나 이미 존재해요" }
                    require(it.number("baseVersion") in 1L..Int.MAX_VALUE.toLong()) { "서버 행사 버전이 잘못됐어요" }
                    validateEventRecovery(PendingMutation(id = it.string("id"), operationId = it.string("operationId"),
                        kind = kind, eventId = eventId, targetId = it.string("targetId"),
                        baseVersion = it.number("baseVersion").toInt(), payload = it.string("payload"),
                        status = it.string("status"), error = it.string("error"),
                        conflictJson = it.string("conflictJson"), createdAt = it.number("createdAt")), origin)
                }
                "revoke-link" -> {
                    val invite = inviteById[it.string("targetId")]
                    require(invite != null && invite.string("eventId") == it.string("eventId") &&
                        payload.getString("issuedOperationId") == invite.string("operationId")) { "취소할 초대 링크가 일치하지 않아요" }
                }
                "revoke-member" -> require(it.string("targetId") in memberIds &&
                    payload.getString("memberId") == it.string("targetId")) { "링크를 취소할 회원이 일치하지 않아요" }
            }
            jsonObject(it.string("conflictJson"))
        }
    }

    private fun validateCell(value: Any, column: Column) {
        if (value == JSONObject.NULL) {
            require(!column.required && !column.primary) { "필수 값이 비어 있어요" }
            return
        }
        when (column.type.uppercase()) {
            "TEXT" -> require(value is String && value.toByteArray(Charsets.UTF_8).size <= maxCellBytes && (!column.primary || value.isNotBlank())) { "문자 값이 잘못됐어요" }
            "INTEGER" -> {
                require(value is Int || value is Long) { "정수 값이 잘못됐어요" }
                if (column.name in setOf("optedOut", "cancelled", "serverDirty", "attendance", "done"))
                    require((value as Number).toLong() in 0..1) { "참·거짓 값이 잘못됐어요" }
            }
            "REAL" -> require(value is Number && value.toDouble().isFinite()) { "실수 값이 잘못됐어요" }
            else -> error("지원하지 않는 백업 컬럼 형식이에요")
        }
    }

    private fun validateUniqueIndexes(db: SupportSQLiteDatabase, table: String, columns: List<Column>, rows: JSONArray) {
        val byName = columns.withIndex().associate { it.value.name to it.index }
        db.query("PRAGMA index_list(${quote(table)})").use { indexes ->
            while (indexes.moveToNext()) {
                if (indexes.getInt(2) == 0) continue
                val indexName = indexes.getString(1).also { require(identifier.matches(it)) }
                val fields = db.query("PRAGMA index_info(${quote(indexName)})").use { info ->
                    buildList { while (info.moveToNext()) add(requireNotNull(byName[info.getString(2)]) { "백업 인덱스가 달라요" }) }
                }
                require(fields.isNotEmpty()) { "백업 인덱스가 잘못됐어요" }
                val seen = HashSet<List<Any>>()
                for (rowIndex in 0 until rows.length()) {
                    val row = rows.getJSONArray(rowIndex)
                    val key = fields.map { row.get(it) }
                    if (key.none { it == JSONObject.NULL }) require(seen.add(key)) { "백업에 중복된 고유 값이 있어요" }
                }
            }
        }
    }

    private fun tableNames(db: SupportSQLiteDatabase): List<String> = db.query(
        "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT IN ('room_master_table','android_metadata') ORDER BY name"
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0).also { require(identifier.matches(it)) }) } }

    private fun columns(db: SupportSQLiteDatabase, table: String): List<Column> = db.query("PRAGMA table_info(${quote(table)})").use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(Column(cursor.getString(1).also { require(identifier.matches(it)) }, cursor.getString(2), cursor.getInt(3) != 0, cursor.getInt(5) != 0))
        }.also { require(it.isNotEmpty()) }
    }

    private fun columnJson(columns: List<Column>): JSONArray = JSONArray().also { array ->
        columns.forEach { array.put(JSONArray().put(it.name).put(it.type).put(it.required).put(it.primary)) }
    }

    private fun cursorValue(cursor: Cursor, index: Int): Any = when (cursor.getType(index)) {
        Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
        Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
        Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index)
        Cursor.FIELD_TYPE_STRING -> cursor.getString(index).also { require(it.toByteArray(Charsets.UTF_8).size <= maxCellBytes) }
        else -> error("지원하지 않는 백업 값 형식이에요")
    }

    private fun sqlValue(value: Any): Any? = if (value == JSONObject.NULL) null else value
    private fun quote(name: String): String { require(identifier.matches(name)); return "\"$name\"" }
}
