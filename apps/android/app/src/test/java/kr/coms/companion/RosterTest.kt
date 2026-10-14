package kr.coms.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RosterTest {
    private fun row(studentId: String, name: String, phone: String = "01000000001", generation: String = "1기") =
        RosterMember(studentId, name, phone, generation)

    private fun snapshot(vararg members: RosterMember) =
        validatedRosterSnapshot("coms-eligible-members", "2026-10-14T00:00:00Z", members.toList())

    @Test fun stableIdentityMakesRepeatedImportIdempotentWithoutMatchingManualMember() {
        val roster = snapshot(row("20260001", "가상 회원"))
        val imported = importedMember(roster.members.single(), null)
        val manual = Member(name = "가상 회원", phone = "01000000001")
        assertEquals(imported.id, snapshot(row("20260001", "변경된 이름")).members.single().id)
        assertFalse(imported.id == manual.id)
        assertEquals(RosterPreview(1, 0, 1, 0, 1), previewRoster(roster, listOf(manual)))
        assertEquals(RosterPreview(0, 0, 1, 0, 1), previewRoster(roster, listOf(manual, imported)))
    }

    @Test fun updateChangesOnlySourceFieldsAndPreservesLocalDecisions() {
        val source = row("20260002", "새 이름", "01000000002", "2기")
        val existing = Member(id = source.id, name = "옛 이름", phone = "", cohort = "1기", groupName = "기획",
            status = "휴면", optedOut = true, semester = "2026-2", role = "운영")
        val updated = importedMember(source, existing)
        assertEquals("새 이름", updated.name)
        assertEquals("01000000002", updated.phone)
        assertEquals("2기", updated.cohort)
        assertEquals(existing.groupName, updated.groupName)
        assertEquals(existing.status, updated.status)
        assertTrue(updated.optedOut)
        assertEquals(existing.semester, updated.semester)
        assertEquals(existing.role, updated.role)
        assertEquals(RosterPreview(0, 1, 0, 0, 0), previewRoster(snapshot(source), listOf(existing)))
        assertEquals(RosterPreview(0, 0, 0, 0, 0), previewRoster(snapshot(source), listOf(updated)))
    }

    @Test fun malformedSnapshotIsRejectedAndPhoneIssuesDoNotDiscardMembership() {
        assertThrows(IllegalArgumentException::class.java) { snapshot(row("", "가상 회원")) }
        assertThrows(IllegalArgumentException::class.java) { snapshot(row("id", "A"), row("id", "B")) }
        assertThrows(IllegalArgumentException::class.java) { validatedRosterSnapshot("other", "2026-10-14T00:00:00Z", listOf(row("id", "A"))) }
        assertThrows(IllegalArgumentException::class.java) { validatedRosterSnapshot("coms-eligible-members", "not-a-date", listOf(row("id", "A"))) }
        assertThrows(IllegalArgumentException::class.java) { validatedRosterSnapshot("coms-eligible-members", "2026-10-14T00:00:00Z", emptyList()) }
        val roster = snapshot(row("id-a", "A", ""), row("id-b", "B", "123"))
        assertEquals(2, roster.members.size)
        assertEquals(RosterPreview(2, 0, 0, 2, 0), previewRoster(roster, emptyList()))
    }
}
