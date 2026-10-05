package com.prash.petcare.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.prash.petcare.data.local.entity.RecoveryCode
import kotlinx.coroutines.flow.Flow

/** Database access for the `recovery_codes` table — the single-use codes that stand in for an authenticator code. */
@Dao
interface RecoveryCodeDao {

    /** Stores a whole freshly generated set at once. */
    @Insert
    suspend fun insertAll(codes: List<RecoveryCode>)

    /** Every code on record for an account, spent ones included. */
    @Query("SELECT * FROM recovery_codes WHERE userId = :userId ORDER BY id")
    suspend fun getAllForUser(userId: Long): List<RecoveryCode>

    /** How many codes are still unspent, as a live count for the profile screen's subtitle. */
    @Query("SELECT COUNT(*) FROM recovery_codes WHERE userId = :userId AND usedAt IS NULL")
    fun observeUnusedCount(userId: Long): Flow<Int>

    /** Marks one code spent, and returns how many rows that changed. */
    @Query("UPDATE recovery_codes SET usedAt = :usedAt WHERE id = :codeId AND usedAt IS NULL")
    suspend fun markUsed(codeId: Long, usedAt: Long): Int

    /** Forgets every code for an account — what turning two-factor authentication off does. */
    @Query("DELETE FROM recovery_codes WHERE userId = :userId")
    suspend fun deleteAllForUser(userId: Long)
}
