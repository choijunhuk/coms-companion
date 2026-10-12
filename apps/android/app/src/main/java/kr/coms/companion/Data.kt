package kr.coms.companion

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.Upsert
import androidx.room.Transaction
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
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
)

@Entity data class Charge(
    @PrimaryKey val id: String = newId(),
    val title: String,
    val dueDate: String = "",
)

/** Positive delta increases the balance; negative delta decreases it. Never edit a past entry. */
@Entity data class LedgerEntry(
    @PrimaryKey val id: String = newId(),
    val chargeId: String,
    val memberId: String,
    val kind: String,
    val deltaWon: Long,
    val reason: String,
    val actor: String = "운영자",
    val createdAt: Long = System.currentTimeMillis(),
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
)

@Dao interface AppDao {
    @Query("SELECT * FROM Member") suspend fun memberSnapshot(): List<Member>
    @Query("SELECT * FROM LedgerEntry") suspend fun ledgerSnapshot(): List<LedgerEntry>
    @Query("SELECT * FROM ContactRecord") suspend fun contactSnapshot(): List<ContactRecord>
    @Query("SELECT * FROM Member ORDER BY name") fun members(): Flow<List<Member>>
    @Query("SELECT * FROM Event ORDER BY whenText, title") fun events(): Flow<List<Event>>
    @Query("SELECT * FROM Event WHERE id=:id") suspend fun eventById(id: String): Event?
    @Query("SELECT (SELECT COUNT(*) FROM Event WHERE serverVersion>0 OR pendingOperationId!='') + (SELECT COUNT(*) FROM Invite)")
    suspend fun onlineStateCount(): Int
    @Query("SELECT * FROM Invite ORDER BY createdAt DESC") fun invites(): Flow<List<Invite>>
    @Query("SELECT * FROM Invite WHERE eventId=:eventId AND memberId=:memberId ORDER BY createdAt DESC LIMIT 1") suspend fun inviteFor(eventId: String, memberId: String): Invite?
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
    @Insert suspend fun put(duty: Duty)
    @Insert suspend fun put(template: MessageTemplate)
    @Insert suspend fun put(record: ContactRecord)
    @Upsert suspend fun put(reminder: Reminder)
    @Insert suspend fun put(draft: ContactDraft)
    @Query("UPDATE Member SET name=:name, phone=:phone, cohort=:cohort, groupName=:groupName, status=:status, optedOut=:optedOut WHERE id=:id")
    suspend fun updateMember(id: String, name: String, phone: String, cohort: String, groupName: String, status: String, optedOut: Boolean)
    @Query("UPDATE Event SET title=:title, whenText=:whenText, place=:place, details=:details, deadline=:deadline, capacity=:capacity, feeWon=:feeWon, cancelled=:cancelled, serverDirty=1 WHERE id=:id")
    suspend fun updateEvent(id: String, title: String, whenText: String, place: String, details: String, deadline: String, capacity: Int?, feeWon: Long, cancelled: Boolean)
    @Query("UPDATE Event SET pendingOperationId=:operationId, pendingPayload=:payload WHERE id=:id")
    suspend fun markPublishPending(id: String, operationId: String, payload: String)
    @Query("UPDATE Event SET pendingOperationId='', pendingPayload='' WHERE id=:id")
    suspend fun clearPublishPending(id: String)
    @Query("UPDATE Event SET serverVersion=:version, serverDirty=:dirty, pendingOperationId='', pendingPayload='' WHERE id=:id")
    suspend fun markPublished(id: String, version: Int, dirty: Boolean)
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
}

@Database(entities = [Member::class, Event::class, Invite::class, Rsvp::class, Charge::class, LedgerEntry::class, Duty::class, MessageTemplate::class, ContactRecord::class, Reminder::class, ContactDraft::class], version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): AppDao
    companion object {
        @Volatile private var instance: AppDatabase? = null
        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "companion-private.db").build().also { instance = it }
        }
    }
}
