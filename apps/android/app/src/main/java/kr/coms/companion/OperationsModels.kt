package kr.coms.companion

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Positive amounts are expenses; negative amounts are refunds or corrections. */
@Entity(indices = [Index("eventId")]) data class ExpenseEntry(
    @PrimaryKey val id: String = newId(),
    val eventId: String,
    val category: String,
    val memo: String,
    val amountWon: Long,
    val actor: String = "운영자",
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity data class ClubAsset(
    @PrimaryKey val id: String = newId(),
    val name: String,
    val details: String = "",
)

/** Rows are retained after return so a later loan cannot erase history. */
@Entity(indices = [Index("assetId"), Index("memberId")]) data class AssetLoan(
    @PrimaryKey val id: String = newId(),
    val assetId: String,
    val memberId: String,
    val lentAt: Long = System.currentTimeMillis(),
    val dueAt: Long? = null,
    val returnedAt: Long? = null,
)

@Entity(indices = [Index("memberId")]) data class Applicant(
    @PrimaryKey val id: String = newId(),
    val name: String,
    val phone: String = "",
    val status: String = "접수",
    val interviewAt: String = "",
    val notes: String = "",
    val memberId: String? = null,
)

@Entity data class MeetingNote(
    @PrimaryKey val id: String = newId(),
    val title: String,
    val heldAt: String = "",
    val minutes: String = "",
)

@Entity(indices = [Index("meetingId")]) data class MeetingDecision(
    @PrimaryKey val id: String = newId(),
    val meetingId: String,
    val text: String,
    val responsible: String = "",
    val dueAt: String = "",
    val done: Boolean = false,
)

data class EventSettlement(val incomeWon: Long, val expenseWon: Long) {
    val balanceWon: Long get() = incomeWon - expenseWon
}

fun requireExpenseAmount(amountWon: Long): Long {
    require(amountWon != 0L && amountWon in -1_000_000_000L..1_000_000_000L) { "한 건의 금액은 0원이 아니고 10억 원 이하여야 해요" }
    return amountWon
}

fun eventSettlement(eventId: String, charges: List<Charge>, ledger: List<LedgerEntry>, expenses: List<ExpenseEntry>): EventSettlement {
    val chargeIds = charges.filter { it.eventId == eventId }.mapTo(mutableSetOf()) { it.id }
    val cashEntries = ledger.filter { it.chargeId in chargeIds && it.kind in setOf("납부 확인", "환불") }
    val cashIds = cashEntries.mapTo(mutableSetOf()) { it.id }
    do {
        val before = cashIds.size
        ledger.filter { it.chargeId in chargeIds && it.reversesEntryId in cashIds }.forEach { cashIds.add(it.id) }
    } while (cashIds.size != before)
    return EventSettlement(
        -ledger.filter { it.id in cashIds }.sumOf { it.deltaWon },
        expenses.filter { it.eventId == eventId }.sumOf { it.amountWon },
    )
}
