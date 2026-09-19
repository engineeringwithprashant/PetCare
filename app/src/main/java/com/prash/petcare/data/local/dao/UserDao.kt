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
     * Every profile on this device that has a phone number recorded, for the sign-in-by-number
     * lookup.
     *
     * **Why the whole (small) set rather than `WHERE phone = :number`.** Numbers are stored in one
     * canonical format now (`+9779812345678` — see [com.prash.petcare.util.PhoneNumbers]), but
     * numbers saved before the country selector existed are stored however the user typed them:
     * "9812345678", "+977 981-234 5678", "098…".
     * SQL cannot strip punctuation or expand a national number to its international form, so an
     * equality match would simply fail to find those accounts and their owners would be told their
     * number is not registered. The comparison is therefore made in Kotlin, where the library that
     * understands the formats can be used — see
     * [com.prash.petcare.data.repository.UserRepository.findEmailForPhone].
     *
     * The cost is nothing: this table holds the profiles that have signed in *on this handset*,
     * which is one row on almost every device and a handful on a shared one.
     */
    @Query("SELECT * FROM users WHERE phone IS NOT NULL AND phone != ''")
    suspend fun getAllWithPhone(): List<User>

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
