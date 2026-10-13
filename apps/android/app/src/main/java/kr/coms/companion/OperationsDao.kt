package kr.coms.companion

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao interface OperationsDao {
    @Query("SELECT * FROM ExpenseEntry ORDER BY createdAt DESC") fun expenses(): Flow<List<ExpenseEntry>>
    @Query("SELECT * FROM ClubAsset ORDER BY name") fun assets(): Flow<List<ClubAsset>>
    @Query("SELECT * FROM AssetLoan ORDER BY lentAt DESC") fun loans(): Flow<List<AssetLoan>>
    @Query("SELECT * FROM Applicant ORDER BY name") fun applicants(): Flow<List<Applicant>>
    @Query("SELECT * FROM MeetingNote ORDER BY heldAt DESC, title") fun meetings(): Flow<List<MeetingNote>>
    @Query("SELECT * FROM MeetingDecision ORDER BY meetingId, dueAt") fun decisions(): Flow<List<MeetingDecision>>

    @Insert suspend fun insert(entry: ExpenseEntry)
    @Insert suspend fun insert(asset: ClubAsset)
    @Insert suspend fun insert(loan: AssetLoan)
    @Insert suspend fun insert(applicant: Applicant)
    @Insert suspend fun insert(meeting: MeetingNote)
    @Insert suspend fun insert(decision: MeetingDecision)
    @Update suspend fun update(asset: ClubAsset)
    @Update suspend fun update(applicant: Applicant)
    @Update suspend fun update(meeting: MeetingNote)
    @Update suspend fun update(decision: MeetingDecision)
    @Query("SELECT * FROM ClubAsset WHERE id=:id") suspend fun assetById(id: String): ClubAsset?
    @Query("SELECT * FROM Member WHERE id=:id") suspend fun borrowerById(id: String): Member?
    @Query("SELECT * FROM AssetLoan WHERE assetId=:assetId AND returnedAt IS NULL LIMIT 1") suspend fun activeLoan(assetId: String): AssetLoan?
    @Query("SELECT * FROM AssetLoan WHERE id=:id") suspend fun loanById(id: String): AssetLoan?
    @Query("UPDATE AssetLoan SET returnedAt=:at WHERE id=:id AND returnedAt IS NULL") suspend fun markReturned(id: String, at: Long): Int

    @Transaction suspend fun lend(assetId: String, memberId: String, dueAt: Long?): AssetLoan {
        requireNotNull(assetById(assetId)) { "물품을 찾지 못했어요" }
        requireNotNull(borrowerById(memberId)) { "회원을 찾지 못했어요" }
        require(activeLoan(assetId) == null) { "이미 대여 중인 물품이에요" }
        val loan = AssetLoan(assetId = assetId, memberId = memberId, dueAt = dueAt)
        insert(loan)
        return loan
    }

    @Transaction suspend fun returnLoan(id: String, at: Long = System.currentTimeMillis()) {
        requireNotNull(loanById(id)) { "대여 기록을 찾지 못했어요" }
        require(markReturned(id, at) == 1) { "이미 반납한 물품이에요" }
    }
}
