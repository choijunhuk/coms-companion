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
        assertEquals("가상 계좌 https://example.org/respond#common", renderTemplate("{계좌 안내} {응답 링크}", member,
            accountGuide = "가상 계좌", responseLink = "https://example.org/respond#common").text)
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

    @Test fun savedTargetsUseCurrentMemberAndResponseState() {
        val a = Member(name = "가상 A", phone = "01000000001", semester = "2026-2")
        val b = Member(name = "가상 B", phone = "01000000002")
        val event = Event(title = "가상 행사")
        val first = eligibleMembers(listOf(a, b), emptyList(), targetPolicy = "unanswered", eventId = event.id)
        assertEquals(setOf(a.id, b.id), first.map { it.id }.toSet())
        val updated = eligibleMembers(listOf(a, b), emptyList(), targetPolicy = "unanswered", eventId = event.id,
            rsvps = listOf(Rsvp(eventId = event.id, memberId = a.id, response = "참석")), excludedIds = setOf(b.id))
        assertTrue(updated.isEmpty())
        assertEquals(listOf(a.id), eligibleMembers(listOf(a, b), emptyList(), targetPolicy = "new", currentSemester = "2026-2").map { it.id })
        assertTrue(eligibleMembers(listOf(a, b), emptyList(), targetPolicy = "selected").isEmpty())
    }

    @Test fun publicShareAllowsOnlyExactKnownCommonLink() {
        val common = "https://example.org/respond#common-token"
        val personal = "https://example.org/respond#personal-token"
        assertTrue(publicShareSafe("안내 $common", emptyList(), setOf(common)))
        assertFalse(publicShareSafe("안내 $personal", emptyList(), setOf(common)))
        assertFalse(publicShareSafe("안내 ${common}-personal", emptyList(), setOf(common)))
        assertFalse(publicShareSafe("안내 $common 01012345678", emptyList(), setOf(common)))
    }

    @Test fun kakaoButtonUsesOnlyTheSingleCurrentCommonLinkInTheMessage() {
        val landing = "https://example.org/companion/"
        val current = "https://example.org/companion/respond#current-common"
        val another = "https://example.org/companion/respond#another-common"
        val expired = "https://example.org/companion/respond#expired-common"
        val personal = "https://example.org/companion/respond#personal-token"
        val approved = setOf(current, another)
        assertEquals(current, kakaoShareLink("행사 안내 $current", approved, landing))
        assertEquals(landing, kakaoShareLink("일반 공지", approved, landing))
        assertEquals(landing, kakaoShareLink("행사 안내 $expired", approved, landing))
        assertEquals(landing, kakaoShareLink("행사 안내 $personal", approved, landing))
        assertEquals(landing, kakaoShareLink("행사 안내 https://other.example.org/respond#arbitrary", approved, landing))
        assertEquals(landing, kakaoShareLink("행사 안내 $current $another", approved, landing))
        assertEquals(current, kakaoShareLink("행사 안내 $current $current", approved, landing))
    }

    @Test fun operatorResponsesUseOnlyServerAcceptedChoices() {
        assertEquals("attending", responseCode("참석"))
        assertEquals("declined", responseCode("불참"))
        assertEquals("undecided", responseCode("미정"))
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { responseCode("출석") }
    }

    @Test fun attendanceOnlyDoesNotBecomeAWebResponse() {
        val eventId = newId()
        val attendanceOnly = Rsvp(eventId = eventId, memberId = newId(), response = "미응답", attendance = true)
        val actualReply = Rsvp(eventId = eventId, memberId = newId(), response = "미정", attendance = false)
        assertEquals(listOf(actualReply), manualResponsesForPublish(listOf(attendanceOnly, actualReply)))
        assertTrue(attendanceOnly.attendance)
    }
}
