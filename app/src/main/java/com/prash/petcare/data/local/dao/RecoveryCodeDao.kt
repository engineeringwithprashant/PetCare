package com.prash.petcare.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.prash.petcare.data.local.entity.RecoveryCode
import kotlinx.coroutines.flow.Flow

/**
 * Database access for the `recovery_codes` table — the single-use codes that stand in for an
 * authenticator code when the phone holding it is gone.
 *
 * Reached only by [com.prash.petcare.data.repository.TwoFactorRepository], as the MVVM chain
 * requires: no ViewModel or screen ever sees this interface. See the note at the top of [UserDao] for
 * why some methods return a [Flow] and the rest are `suspend`.
 */
@Dao
interface RecoveryCodeDao {

    /**
     * Stores a whole freshly generated set at once.
     *
     * A list rather than ten separate calls, because Room runs a list insert inside one transaction:
     * either all ten codes are saved or none is. A half-written set would be the worst possible
     * outcome — the user has a printed list of ten codes, only some of which work, with no way to
     * tell which.
     */
    @Insert
    suspend fun insertAll(codes: List<RecoveryCode>)

    /**
     * Every code on record for an account, spent ones included.
     *
     * The whole set has to be read rather than queried for a match, because the codes are stored
     * hashed with a salt each: there is no `WHERE codeHash = ?` to write, since the hash of a typed
     * code depends on which row's salt it is combined with. The repository therefore re-hashes what
     * was typed once per row. Ten rows makes that trivially cheap.
     */
    @Query("SELECT * FROM recovery_codes WHERE userId = :userId ORDER BY id")
    suspend fun getAllForUser(userId: Long): List<RecoveryCode>

    /**
     * How many codes are still unspent, as a live count for the profile screen's subtitle.
     *
     * A `Flow`, so using a code at sign-in updates "7 recovery codes left" with no refresh anywhere —
     * and `COUNT(*)` in SQL rather than counting a loaded list in Kotlin, so the rows themselves
     * never have to leave the database to answer a question about how many there are.
     */
    @Query("SELECT COUNT(*) FROM recovery_codes WHERE userId = :userId AND usedAt IS NULL")
    fun observeUnusedCount(userId: Long): Flow<Int>

    /**
     * Marks one code spent, and returns how many rows that changed.
     *
     * **`AND usedAt IS NULL` is what makes a code single-use**, and it does it in the database rather
     * than in Kotlin. Two sign-ins racing with the same code both read it as unused; only one of them
     * can then match this `WHERE`, so exactly one gets a return value of 1 and the other gets 0 and
     * is refused. Checking "is it used?" and then writing "it is now" as two separate statements
     * would leave a window between them in which both would win.
     */
    @Query("UPDATE recovery_codes SET usedAt = :usedAt WHERE id = :codeId AND usedAt IS NULL")
    suspend fun markUsed(codeId: Long, usedAt: Long): Int

    /**
     * Forgets every code for an account — what turning two-factor authentication off does, alongside
     * clearing the secret.
     *
     * Leaving them behind would mean the next time it was switched on, an old printed list would
     * still open the account. Codes belong to the setup that produced them.
     */
    @Query("DELETE FROM recovery_codes WHERE userId = :userId")
    suspend fun deleteAllForUser(userId: Long)
}
