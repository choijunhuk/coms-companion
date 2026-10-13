package kr.coms.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class OperationsTest {
    @Test fun expenseAmountRejectsZeroOverflowAndOversizedEntries() {
        assertEquals(-1_000_000_000L, requireExpenseAmount(-1_000_000_000L))
        assertEquals(1_000_000_000L, requireExpenseAmount(1_000_000_000L))
        listOf(0L, -1_000_000_001L, 1_000_000_001L, Long.MIN_VALUE).forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { requireExpenseAmount(value) }
        }
    }

    @Test fun settlementIncludesOnlyLinkedConfirmedLedgerAndKeepsCorrections() {
        val event = Event(id = "event", title = "행사")
        val charges = listOf(Charge(id = "linked", title = "참가비", eventId = event.id), Charge(id = "other", title = "회비"))
        val ledger = listOf(
            LedgerEntry(chargeId = "linked", memberId = "a", kind = "청구", deltaWon = 25_000, reason = "청구"),
            LedgerEntry(id = "payment", chargeId = "linked", memberId = "a", kind = "납부 확인", deltaWon = -20_000, reason = "확인"),
            LedgerEntry(chargeId = "linked", memberId = "a", kind = "환불", deltaWon = 5_000, reason = "환불"),
            LedgerEntry(chargeId = "linked", memberId = "a", kind = "면제", deltaWon = -2_000, reason = "면제"),
            LedgerEntry(chargeId = "other", memberId = "a", kind = "납부 확인", deltaWon = -30_000, reason = "다른 회비"),
        )
        val expenses = listOf(
            ExpenseEntry(eventId = "event", category = "대관", memo = "대관료", amountWon = 8_000),
            ExpenseEntry(eventId = "event", category = "환불", memo = "일부 환불", amountWon = -2_000),
            ExpenseEntry(eventId = "other", category = "대관", memo = "다른 행사", amountWon = 9_000),
        )
        assertEquals(EventSettlement(incomeWon = 15_000, expenseWon = 6_000), eventSettlement(event.id, charges, ledger, expenses))
        assertEquals(9_000, eventSettlement(event.id, charges, ledger, expenses).balanceWon)
    }

    @Test fun reversedPaymentNoLongerCountsAsIncome() {
        val payment = LedgerEntry(id = "paid", chargeId = "event-fee", memberId = "a", kind = "납부 확인", deltaWon = -12_000, reason = "확인")
        val reversal = LedgerEntry(id = "undo", chargeId = "event-fee", memberId = "a", kind = "정정 증가", deltaWon = 12_000, reason = "오입력", reversesEntryId = payment.id)
        assertEquals(0, eventSettlement("event", listOf(Charge(id = "event-fee", title = "참가비", eventId = "event")), listOf(payment, reversal), emptyList()).incomeWon)
        val restore = LedgerEntry(chargeId = "event-fee", memberId = "a", kind = "정정 감소", deltaWon = -12_000, reason = "취소 복원", reversesEntryId = reversal.id)
        assertEquals(12_000, eventSettlement("event", listOf(Charge(id = "event-fee", title = "참가비", eventId = "event")), listOf(payment, reversal, restore), emptyList()).incomeWon)
    }
}
