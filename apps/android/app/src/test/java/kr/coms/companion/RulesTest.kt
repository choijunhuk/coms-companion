package kr.coms.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RulesTest {
    @Test fun balanceUsesAppendOnlyEntriesAndExcludesSettledMembers() {
        val a = Member(name = "가상 회원 A", phone = "010-0000-0000")
        val b = Member(name = "가상 회원 B", phone = "010-0000-0001", optedOut = true)
        val charge = Charge(title = "가상 회비")
        val entries = listOf(
            LedgerEntry(chargeId = charge.id, memberId = a.id, kind = "청구", deltaWon = 10000, reason = "부과"),
            LedgerEntry(chargeId = charge.id, memberId = a.id, kind = "납부 확인", deltaWon = -10000, reason = "확인"),
        )
        assertEquals(0, balance(charge.id, a.id, entries))
        assertTrue(eligibleMembers(listOf(a, b), entries, charge.id).isEmpty())
        assertEquals(10000, requireLedgerEntry("청구", 10000, "부과"))
        assertEquals(-10000, requireLedgerEntry("정정 감소", 10000, "오류 수정"))
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { requireLedgerEntry("청구", 1_000_000_001L, "과다") }
    }

    @Test fun unresolvedVariablesStayVisibleAndBlockHandoff() {
        val member = Member(name = "가상 회원", phone = "01000000000")
        val result = renderTemplate("{이름}님 {행사명} 안내", member)
        assertEquals("가상 회원님 {행사명} 안내", result.text)
        assertEquals(setOf("행사명"), result.missing)
        assertFalse(validPhone("123"))
        assertTrue(renderTemplate("{이름1} {응답 링크} {name_foo} {", member).missing.isNotEmpty())
        assertFalse(publicShareSafe("가상 회원 공지", listOf(member)))
        assertFalse(publicShareSafe("잔액 10,000원", emptyList()))
        assertTrue(publicShareSafe("다음 주 행사 안내입니다", emptyList()))
    }

    @Test fun csvPreviewSkipsBadAndDuplicateRows() {
        val existing = listOf(Member(name = "기존", phone = "01000000000"))
        val rows = previewMembersCsv("이름,전화번호,기수,소모임\n새 회원,01000000001,1,기획\n중복,01000000000,2,홍보\n\"쉼표, 이름\",01000000002,3,운영", existing)
        assertEquals(3, rows.size)
        assertEquals(null, rows[0].issue)
        assertEquals("기존·파일 내 번호 중복", rows[1].issue)
        assertEquals("쉼표, 이름", rows[2].name)
    }

    @Test fun duplicatePhoneIsNotSentTwice() {
        val members = listOf(Member(name = "가상 A", phone = "01000000000"), Member(name = "가상 B", phone = "010-0000-0000"))
        assertTrue(eligibleMembers(members, emptyList()).isEmpty())
    }
}
