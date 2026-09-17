package com.prash.petcare.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.prash.petcare.data.local.entity.User
import com.prash.petcare.data.local.relation.UserWithPets
import kotlinx.coroutines.flow.Flow

/**
 * Database access for the `users` table.
 *
 * A DAO (Data Access Object) is an interface of SQL operations. Room generates the
 * implementation at build time, checking every query against the schema, so a typo in a column
 * name fails the build instead of crashing the app at runtime.
 *
 * Two return styles appear throughout the DAOs in this package:
 *  - **[Flow]** for queries the UI observes. Room re-runs the query and emits a fresh list
 *    whenever the underlying table changes, which is what keeps screens up to date without any
 *    manual refresh. Flow-returning methods are not `suspend`: collecting the flow is what does
 *    the work, and Room moves that off the main thread itself.
 *  - **`suspend`** for one-shot reads and every write. The keyword forces callers to be inside a
 *    coroutine, which is how Room guarantees disk access never blocks the main thread.
 */
@Dao
interface UserDao {

    /**
     * Adds a new account and returns the generated [User.id].
     *
     * Uses Room's default conflict strategy (ABORT), so inserting an email that already exists
     * throws rather than silently overwriting the existing account — the unique index on
     * `email` is what detects it. The repository turns that exception into a friendly result.
     */
    @Insert
    suspend fun insert(user: User): Long

    @Update
    suspend fun update(user: User)

    @Delete
    suspend fun delete(user: User)

    /** Observes a single account, emitting null if it is deleted. */
    @Query("SELECT * FROM users WHERE id = :userId")
    fun observeById(userId: Long): Flow<User?>

    /** One-shot lookup used when restoring a saved session. */
    @Query("SELECT * FROM users WHERE id = :userId")
    suspend fun getById(userId: Long): User?

    /**
     * Finds the local profile for an email address. Called after Firebase has accepted a login or
     * registration, to match the Firebase account to its profile on this device.
     *
     * `COLLATE NOCASE` makes the comparison case-insensitive, so someone who registered as
     * `Sam@example.com` can still log in typing `sam@example.com`.
     */
    @Query("SELECT * FROM users WHERE email = :email COLLATE NOCASE LIMIT 1")
    suspend fun getByEmail(email: String): User?

    /**
     * Observes an account together with all of its pets.
     *
     * `@Transaction` is required for relation queries: Room runs one query for the user and
     * another for the pets, and the transaction stops a write landing between the two and
     * producing a mismatched result.
     */
    @Transaction
    @Query("SELECT * FROM users WHERE id = :userId")
    fun observeUserWithPets(userId: Long): Flow<UserWithPets?>
}
