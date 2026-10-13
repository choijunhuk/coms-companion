package kr.coms.companion

import android.content.Context
import androidx.room.Dao
import androidx.room.ColumnInfo
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.Upsert
import androidx.room.Transaction
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import java.util.UUID

fun newId(): String = UUID.randomUUID().toString()

@Entity data class Member(
    @PrimaryKey val id: String = newId(),
    val name: String,
    val phone: String = "",
    val cohort: String = "",
    val groupName: String = "",
    val status: String = "활동",
    val optedOut: Boolean = false,
    @ColumnInfo(defaultValue = "''") val semester: String = "",
    @ColumnInfo(defaultValue = "''") val role: String = "",
)

@Entity data class Event(
    @PrimaryKey val id: String = newId(),
    val title: String,
    val whenText: String = "",
    val place: String = "",
    val details: String = "",
    val deadline: String = "",
    val capacity: Int? = null,
    val feeWon: Long = 0,
    val cancelled: Boolean = false,
    val serverVersion: Int = 0,
    val serverDirty: Boolean = true,
    val pendingOperationId: String = "",
    val pendingPayload: String = "",
    val responsesJson: String = "",
    val lastSyncAt: Long? = null,
    @ColumnInfo(defaultValue = "'[]'") val pollOptionsJson: String = "[]",
    @ColumnInfo(defaultValue = "''") val pollDeadline: String = "",
    @ColumnInfo(defaultValue = "0") val changeCursor: Long = 0,
    @ColumnInfo(defaultValue = "''") val conflictJson: String = "",
)

@Entity data class Invite(
    @PrimaryKey val id: String = newId(),
    val eventId: String,
    val memberId: String = "",
    val operationId: String = newId(),
    val expiresAt: String,
    val url: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity data class Rsvp(
    @PrimaryKey val id: String = newId(),
    val eventId: String,
    val memberId: String,
    val response: String,
    val attendance: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "''") val submissionId: String = "",
    @ColumnInfo(defaultValue = "0") val serverVersion: Int = 0,
)

@Entity data class Charge(
    @PrimaryKey val id: String = newId(),
    val title: String,
    val dueDate: String = "",
    val eventId: String? = null,
)

/** Positive delta increases the balance; negative delta decreases it. Never edit a past entry. */
@Entity(indices = [Index(value = ["paymentRequestId"], unique = true), Index(value = ["reversesEntryId"], unique = true)]) data class LedgerEntry(
    @PrimaryKey val id: String = newId(),
    val chargeId: String,
    val memberId: String,
    val kind: String,
    val deltaWon: Long,
    val reason: String,
    val actor: String = "운영자",
    val createdAt: Long = System.currentTimeMillis(),
    val paymentRequestId: String? = null,
    val reversesEntryId: String? = null,
)

@Entity data class Duty(
    @PrimaryKey val id: String = newId(),
    val title: String,
    val dueAt: Long? = null,
    val eventId: String? = null,
    val done: Boolean = false,
)

@Entity data class MessageTemplate(
    @PrimaryKey val id: String = newId(),
    val title: String,
    val body: String,
)

@Entity data class ContactRecord(
    @PrimaryKey val id: String = newId(),
    val memberId: String,
    val memberName: String,
    val phoneSnapshot: String,
    val bodySnapshot: String,
    val channel: String,
    val state: String,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity data class Reminder(
    @PrimaryKey val id: String = newId(),
    val title: String,
    val atMillis: Long,
    val state: String = "대기",
    val draftId: String? = null,
)

@Entity data class ContactDraft(
    @PrimaryKey val id: String = newId(),
    val title: String,
    val body: String,
    val eventId: String? = null,
    val chargeId: String? = null,
    val groupName: String? = null,
    val currentIndex: Int = 0,
    val updatedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "'all'") val targetPolicy: String = "all",
    @ColumnInfo(defaultValue = "'[]'") val selectedMemberIdsJson: String = "[]",
    @ColumnInfo(defaultValue = "'[]'") val excludedMemberIdsJson: String = "[]",
    @ColumnInfo(defaultValue = "''") val purpose: String = "",
    val cohortFilter: String? = null,
)

@Entity data class ClubConfig(
    @PrimaryKey val id: Int = 1,
    val clubName: String = "COM’s",
    val semester: String = "",
    val accountGuide: String = "",
    val actor: String = "운영자",
    val ownerId: String = newId(),
    val serverOrigin: String = "",
    val changeCursor: Long = 0,
    val lastSyncAt: Long? = null,
    val lastError: String = "",
)

@Entity(indices = [Index(value = ["operationId"], unique = true)]) data class PendingMutation(
    @PrimaryKey val id: String = newId(),
    val operationId: String = newId(),
    val kind: String,
    val eventId: String,
    val targetId: String = "",
    val baseVersion: Int = 0,
    val payload: String,
    val status: String = "pending",
    val error: String = "",
    val conflictJson: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)

@Dao interface AppDao {
    @Query("SELECT * FROM Member") suspend fun memberSnapshot(): List<Member>
    @Query("SELECT * FROM LedgerEntry") suspend fun ledgerSnapshot(): List<LedgerEntry>
    @Query("SELECT * FROM ContactRecord") suspend fun contactSnapshot(): List<ContactRecord>
    @Query("SELECT * FROM Member ORDER BY name") fun members(): Flow<List<Member>>
    @Query("SELECT * FROM Event ORDER BY whenText, title") fun events(): Flow<List<Event>>
    @Query("SELECT * FROM Event WHERE id=:id") suspend fun eventById(id: String): Event?
    @Query("SELECT * FROM Event WHERE serverVersion>0 OR pendingOperationId!=''") suspend fun onlineEvents(): List<Event>
    @Query("SELECT * FROM Rsvp WHERE eventId=:eventId") suspend fun rsvpsFor(eventId: String): List<Rsvp>
    @Query("SELECT * FROM ClubConfig WHERE id=1") fun clubConfig(): Flow<ClubConfig?>
    @Query("SELECT * FROM ClubConfig WHERE id=1") suspend fun clubConfigSnapshot(): ClubConfig?
    @Query("SELECT * FROM PendingMutation ORDER BY createdAt") fun pendingMutations(): Flow<List<PendingMutation>>
    @Query("SELECT * FROM PendingMutation WHERE status IN ('pending','retry') AND kind!='event-recover' ORDER BY createdAt") suspend fun readyMutations(): List<PendingMutation>
    @Query("SELECT * FROM PendingMutation WHERE id=:id") suspend fun mutationById(id: String): PendingMutation?
    @Query("SELECT COUNT(*) FROM PendingMutation WHERE eventId=:eventId AND kind=:kind AND targetId=:targetId")
    suspend fun mutationCount(eventId: String, kind: String, targetId: String): Int
    @Query("SELECT * FROM PendingMutation WHERE eventId=:eventId AND kind=:kind AND targetId=:targetId LIMIT 1")
    suspend fun mutationForTarget(eventId: String, kind: String, targetId: String): PendingMutation?
    @Query("SELECT * FROM LedgerEntry WHERE paymentRequestId=:requestId LIMIT 1") suspend fun ledgerForRequest(requestId: String): LedgerEntry?
    @Query("SELECT * FROM LedgerEntry WHERE id=:id LIMIT 1") suspend fun entryById(id: String): LedgerEntry?
    @Query("SELECT * FROM LedgerEntry WHERE reversesEntryId=:id LIMIT 1") suspend fun reversalFor(id: String): LedgerEntry?
    @Query("SELECT * FROM Charge WHERE id=:id") suspend fun chargeById(id: String): Charge?
    @Query("SELECT * FROM Member WHERE id=:id") suspend fun memberById(id: String): Member?
    @Query("SELECT (SELECT COUNT(*) FROM Event WHERE serverVersion>0 OR pendingOperationId!='') + (SELECT COUNT(*) FROM Invite) + (SELECT COUNT(*) FROM PendingMutation)")
    suspend fun onlineStateCount(): Int
    @Query("SELECT * FROM Invite ORDER BY createdAt DESC") fun invites(): Flow<List<Invite>>
    @Query("SELECT * FROM Invite WHERE eventId=:eventId AND memberId=:memberId ORDER BY createdAt DESC LIMIT 1") suspend fun inviteFor(eventId: String, memberId: String): Invite?
    @Query("SELECT * FROM Invite WHERE id=:id") suspend fun inviteById(id: String): Invite?
    @Query("SELECT * FROM Invite WHERE memberId=:memberId") suspend fun personalInvitesForMember(memberId: String): List<Invite>
    @Query("UPDATE Invite SET url='', expiresAt='1970-01-01T00:00:00Z' WHERE id=:id") suspend fun markInviteRevoked(id: String)
    @Query("UPDATE Invite SET url='', expiresAt='1970-01-01T00:00:00Z' WHERE eventId=:eventId AND memberId=:memberId")
    suspend fun markMemberInvitesRevoked(eventId: String, memberId: String)
    @Query("SELECT * FROM Rsvp ORDER BY updatedAt DESC") fun rsvps(): Flow<List<Rsvp>>
    @Query("SELECT COUNT(*) FROM Rsvp WHERE eventId=:eventId") suspend fun rsvpCount(eventId: String): Int
    @Query("SELECT * FROM Charge ORDER BY title") fun charges(): Flow<List<Charge>>
    @Query("SELECT * FROM LedgerEntry ORDER BY createdAt DESC") fun ledger(): Flow<List<LedgerEntry>>
    @Query("SELECT * FROM Duty ORDER BY done, dueAt") fun duties(): Flow<List<Duty>>
    @Query("SELECT * FROM MessageTemplate ORDER BY title") fun templates(): Flow<List<MessageTemplate>>
    @Query("SELECT * FROM ContactRecord ORDER BY createdAt DESC") fun contacts(): Flow<List<ContactRecord>>
    @Query("SELECT * FROM Reminder ORDER BY atMillis") fun reminders(): Flow<List<Reminder>>
    @Query("SELECT * FROM ContactDraft ORDER BY updatedAt DESC") fun drafts(): Flow<List<ContactDraft>>
    @Insert suspend fun put(member: Member)
    @Insert suspend fun putMembers(members: List<Member>)
    @Transaction suspend fun importMembers(members: List<Member>) { putMembers(members) }
    @Insert suspend fun put(event: Event)
    @Insert suspend fun put(invite: Invite)
    @Insert suspend fun put(rsvp: Rsvp)
    @Insert suspend fun put(charge: Charge)
    @Insert suspend fun put(entry: LedgerEntry)
    @Transaction suspend fun appendReversal(entryId: String, reason: String): LedgerEntry {
        require(reason.isNotBlank()) { "정정 이유를 입력해 주세요" }
        val source = requireNotNull(entryById(entryId)) { "원장 기록을 찾지 못했어요" }
        require(reversalFor(entryId) == null) { "이미 정정한 기록이에요" }
        val reversal = LedgerEntry(chargeId = source.chargeId, memberId = source.memberId,
            kind = if (source.deltaWon > 0) "정정 감소" else "정정 증가", deltaWon = -source.deltaWon,
            reason = reason.trim(), reversesEntryId = entryId)
        put(reversal)
        return reversal
    }
    @Insert suspend fun put(duty: Duty)
    @Insert suspend fun put(template: MessageTemplate)
    @Insert suspend fun put(record: ContactRecord)
    @Upsert suspend fun put(reminder: Reminder)
    @Insert suspend fun put(draft: ContactDraft)
    @Upsert suspend fun put(config: ClubConfig)
    @Insert suspend fun put(mutation: PendingMutation)
    @Query("UPDATE PendingMutation SET status=:status, error=:error, conflictJson=:conflictJson WHERE id=:id")
    suspend fun updateMutation(id: String, status: String, error: String = "", conflictJson: String = "")
    @Query("UPDATE PendingMutation SET operationId=:operationId, targetId=:targetId, baseVersion=:baseVersion, payload=:payload, status='pending', error='', conflictJson='' WHERE id=:id")
    suspend fun rebaseMutation(id: String, operationId: String, targetId: String, baseVersion: Int, payload: String)
    @Query("UPDATE PendingMutation SET baseVersion=:baseVersion, payload=:payload, status='conflict', error=:error WHERE id=:id AND kind='event-recover'")
    suspend fun updateEventRecovery(id: String, baseVersion: Int, payload: String, error: String)
    @Query("DELETE FROM PendingMutation WHERE id=:id") suspend fun deleteMutation(id: String)
    @Query("UPDATE ClubConfig SET changeCursor=:cursor, lastSyncAt=:at, lastError='' WHERE id=1") suspend fun markSynced(cursor: Long, at: Long)
    @Query("UPDATE ClubConfig SET lastError=:error WHERE id=1") suspend fun markSyncError(error: String)
    @Query("UPDATE Event SET changeCursor=:cursor WHERE id=:id") suspend fun markEventCursor(id: String, cursor: Long)
    @Query("UPDATE Rsvp SET submissionId=:submissionId, serverVersion=:version WHERE id=:id") suspend fun markRsvpImported(id: String, submissionId: String, version: Int): Int
    @Query("UPDATE Member SET name=:name, phone=:phone, cohort=:cohort, groupName=:groupName, status=:status, optedOut=:optedOut WHERE id=:id")
    suspend fun updateMember(id: String, name: String, phone: String, cohort: String, groupName: String, status: String, optedOut: Boolean)
    @Query("UPDATE Member SET name=:name, phone=:phone, cohort=:cohort, groupName=:groupName, status=:status, optedOut=:optedOut, semester=:semester, role=:role WHERE id=:id")
    suspend fun updateMemberDetails(id: String, name: String, phone: String, cohort: String, groupName: String, status: String, optedOut: Boolean, semester: String, role: String): Int
    @Query("UPDATE Member SET status=COALESCE(:status,status), groupName=COALESCE(:groupName,groupName) WHERE id IN (:ids)")
    suspend fun updateMemberBatch(ids: List<String>, status: String?, groupName: String?): Int
    @Transaction suspend fun bulkUpdateMembers(ids: List<String>, status: String?, groupName: String?) {
        require(ids.isNotEmpty() && ids.size == ids.toSet().size) { "수정할 회원을 선택해 주세요" }
        require(status != null || groupName != null) { "변경할 항목을 선택해 주세요" }
        require(status == null || status in setOf("활동", "휴면", "탈퇴")) { "회원 상태를 확인해 주세요" }
        require(updateMemberBatch(ids, status, groupName?.trim()) == ids.size) { "목록이 변경됐어요. 회원을 다시 확인해 주세요" }
    }
    @Query("UPDATE Event SET title=:title, whenText=:whenText, place=:place, details=:details, deadline=:deadline, capacity=:capacity, feeWon=:feeWon, cancelled=:cancelled, serverDirty=1 WHERE id=:id AND pendingOperationId=''")
    suspend fun updateEvent(id: String, title: String, whenText: String, place: String, details: String, deadline: String, capacity: Int?, feeWon: Long, cancelled: Boolean): Int
    @Query("UPDATE Event SET title=:title, whenText=:whenText, place=:place, details=:details, deadline=:deadline, capacity=:capacity, feeWon=:feeWon, cancelled=:cancelled, pollOptionsJson=:pollOptionsJson, pollDeadline=:pollDeadline, serverDirty=1 WHERE id=:id AND pendingOperationId=''")
    suspend fun updateEventWithPoll(id: String, title: String, whenText: String, place: String, details: String, deadline: String, capacity: Int?, feeWon: Long, cancelled: Boolean, pollOptionsJson: String, pollDeadline: String): Int
    @Query("UPDATE Event SET pendingOperationId=:operationId, pendingPayload=:payload WHERE id=:id AND pendingOperationId=''")
    suspend fun markPublishPending(id: String, operationId: String, payload: String): Int
    @Query("UPDATE Event SET pendingOperationId='', pendingPayload='' WHERE id=:id")
    suspend fun clearPublishPending(id: String)
    @Query("UPDATE Event SET serverVersion=:version, serverDirty=:dirty, pendingOperationId='', pendingPayload='', conflictJson='' WHERE id=:id")
    suspend fun markPublished(id: String, version: Int, dirty: Boolean)
    @Query("UPDATE Event SET conflictJson=:json WHERE id=:id") suspend fun markEventConflict(id: String, json: String)
    @Query("UPDATE Event SET serverVersion=:version, pendingOperationId=:operationId, pendingPayload=:payload, conflictJson='' WHERE id=:id")
    suspend fun rebaseEvent(id: String, version: Int, operationId: String, payload: String)
    @Query("UPDATE Event SET title=:title, whenText=:whenText, place=:place, details=:details, deadline=:deadline, capacity=:capacity, feeWon=:feeWon, cancelled=:cancelled, pollOptionsJson=:pollOptionsJson, pollDeadline=:pollDeadline, serverVersion=:version, serverDirty=0, pendingOperationId='', pendingPayload='', conflictJson='' WHERE id=:id")
    suspend fun acceptServerEvent(id: String, title: String, whenText: String, place: String, details: String, deadline: String, capacity: Int?, feeWon: Long, cancelled: Boolean, pollOptionsJson: String, pollDeadline: String, version: Int)
    @Query("UPDATE Event SET responsesJson=:json, lastSyncAt=:at WHERE id=:id") suspend fun setResponses(id: String, json: String, at: Long)
    @Query("UPDATE Invite SET url=:url WHERE id=:id") suspend fun setInviteUrl(id: String, url: String)
    @Query("UPDATE Rsvp SET response=:response, attendance=:attendance, updatedAt=:updatedAt WHERE id=:id")
    suspend fun updateRsvp(id: String, response: String, attendance: Boolean, updatedAt: Long = System.currentTimeMillis())
    @Query("UPDATE Duty SET done=:done WHERE id=:id") suspend fun updateDuty(id: String, done: Boolean)
    @Query("UPDATE MessageTemplate SET title=:title, body=:body WHERE id=:id") suspend fun updateTemplate(id: String, title: String, body: String)
    @Query("UPDATE ContactRecord SET state=:state WHERE id=:id") suspend fun updateContact(id: String, state: String)
    @Query("UPDATE Reminder SET state=:state WHERE id=:id") suspend fun updateReminder(id: String, state: String)
    @Query("UPDATE ContactDraft SET title=:title, body=:body, eventId=:eventId, chargeId=:chargeId, groupName=:groupName, currentIndex=:currentIndex, updatedAt=:updatedAt WHERE id=:id")
    suspend fun updateDraft(id: String, title: String, body: String, eventId: String?, chargeId: String?, groupName: String?, currentIndex: Int, updatedAt: Long = System.currentTimeMillis())
    @Query("UPDATE ContactDraft SET title=:title, body=:body, eventId=:eventId, chargeId=:chargeId, groupName=:groupName, currentIndex=:currentIndex, targetPolicy=:targetPolicy, selectedMemberIdsJson=:selectedMemberIdsJson, excludedMemberIdsJson=:excludedMemberIdsJson, purpose=:purpose, cohortFilter=:cohortFilter, updatedAt=:updatedAt WHERE id=:id")
    suspend fun updateDraftTargeted(id: String, title: String, body: String, eventId: String?, chargeId: String?, groupName: String?, currentIndex: Int, targetPolicy: String, selectedMemberIdsJson: String, excludedMemberIdsJson: String, purpose: String, cohortFilter: String? = null, updatedAt: Long = System.currentTimeMillis())
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE Member ADD COLUMN semester TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE Member ADD COLUMN role TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE Event ADD COLUMN pollOptionsJson TEXT NOT NULL DEFAULT '[]'")
        db.execSQL("ALTER TABLE Event ADD COLUMN pollDeadline TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE Event ADD COLUMN changeCursor INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE Event ADD COLUMN conflictJson TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE Rsvp ADD COLUMN submissionId TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE Rsvp ADD COLUMN serverVersion INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE Charge ADD COLUMN eventId TEXT")
        db.execSQL("ALTER TABLE LedgerEntry ADD COLUMN paymentRequestId TEXT")
        db.execSQL("CREATE UNIQUE INDEX index_LedgerEntry_paymentRequestId ON LedgerEntry(paymentRequestId)")
        db.execSQL("ALTER TABLE LedgerEntry ADD COLUMN reversesEntryId TEXT")
        db.execSQL("CREATE UNIQUE INDEX index_LedgerEntry_reversesEntryId ON LedgerEntry(reversesEntryId)")
        db.execSQL("ALTER TABLE ContactDraft ADD COLUMN targetPolicy TEXT NOT NULL DEFAULT 'all'")
        db.execSQL("ALTER TABLE ContactDraft ADD COLUMN selectedMemberIdsJson TEXT NOT NULL DEFAULT '[]'")
        db.execSQL("ALTER TABLE ContactDraft ADD COLUMN excludedMemberIdsJson TEXT NOT NULL DEFAULT '[]'")
        db.execSQL("ALTER TABLE ContactDraft ADD COLUMN purpose TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE Reminder ADD COLUMN draftId TEXT")
        db.execSQL("CREATE TABLE IF NOT EXISTS ClubConfig (id INTEGER NOT NULL, clubName TEXT NOT NULL, semester TEXT NOT NULL, accountGuide TEXT NOT NULL, actor TEXT NOT NULL, ownerId TEXT NOT NULL, serverOrigin TEXT NOT NULL, changeCursor INTEGER NOT NULL, lastSyncAt INTEGER, lastError TEXT NOT NULL, PRIMARY KEY(id))")
        db.execSQL("CREATE TABLE IF NOT EXISTS PendingMutation (id TEXT NOT NULL, operationId TEXT NOT NULL, kind TEXT NOT NULL, eventId TEXT NOT NULL, targetId TEXT NOT NULL, baseVersion INTEGER NOT NULL, payload TEXT NOT NULL, status TEXT NOT NULL, error TEXT NOT NULL, conflictJson TEXT NOT NULL, createdAt INTEGER NOT NULL, PRIMARY KEY(id))")
        db.execSQL("CREATE UNIQUE INDEX index_PendingMutation_operationId ON PendingMutation(operationId)")
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS ExpenseEntry (id TEXT NOT NULL, eventId TEXT NOT NULL, category TEXT NOT NULL, memo TEXT NOT NULL, amountWon INTEGER NOT NULL, actor TEXT NOT NULL, createdAt INTEGER NOT NULL, PRIMARY KEY(id))")
        db.execSQL("CREATE INDEX index_ExpenseEntry_eventId ON ExpenseEntry(eventId)")
        db.execSQL("CREATE TABLE IF NOT EXISTS ClubAsset (id TEXT NOT NULL, name TEXT NOT NULL, details TEXT NOT NULL, PRIMARY KEY(id))")
        db.execSQL("CREATE TABLE IF NOT EXISTS AssetLoan (id TEXT NOT NULL, assetId TEXT NOT NULL, memberId TEXT NOT NULL, lentAt INTEGER NOT NULL, dueAt INTEGER, returnedAt INTEGER, PRIMARY KEY(id))")
        db.execSQL("CREATE INDEX index_AssetLoan_assetId ON AssetLoan(assetId)")
        db.execSQL("CREATE INDEX index_AssetLoan_memberId ON AssetLoan(memberId)")
        db.execSQL("CREATE TABLE IF NOT EXISTS Applicant (id TEXT NOT NULL, name TEXT NOT NULL, phone TEXT NOT NULL, status TEXT NOT NULL, interviewAt TEXT NOT NULL, notes TEXT NOT NULL, memberId TEXT, PRIMARY KEY(id))")
        db.execSQL("CREATE INDEX index_Applicant_memberId ON Applicant(memberId)")
        db.execSQL("CREATE TABLE IF NOT EXISTS MeetingNote (id TEXT NOT NULL, title TEXT NOT NULL, heldAt TEXT NOT NULL, minutes TEXT NOT NULL, PRIMARY KEY(id))")
        db.execSQL("CREATE TABLE IF NOT EXISTS MeetingDecision (id TEXT NOT NULL, meetingId TEXT NOT NULL, text TEXT NOT NULL, responsible TEXT NOT NULL, dueAt TEXT NOT NULL, done INTEGER NOT NULL, PRIMARY KEY(id))")
        db.execSQL("CREATE INDEX index_MeetingDecision_meetingId ON MeetingDecision(meetingId)")
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE ContactDraft ADD COLUMN cohortFilter TEXT")
    }
}

@Database(entities = [Member::class, Event::class, Invite::class, Rsvp::class, Charge::class, LedgerEntry::class, Duty::class, MessageTemplate::class, ContactRecord::class, Reminder::class, ContactDraft::class, ClubConfig::class, PendingMutation::class, ExpenseEntry::class, ClubAsset::class, AssetLoan::class, Applicant::class, MeetingNote::class, MeetingDecision::class], version = 4, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): AppDao
    abstract fun operationsDao(): OperationsDao
    companion object {
        @Volatile private var instance: AppDatabase? = null
        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "companion-private.db").addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build().also { instance = it }
        }
    }
}
