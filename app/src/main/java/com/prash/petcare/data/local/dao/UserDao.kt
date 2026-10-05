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

/** Database access for the `users` table. */
@Dao
interface UserDao {

    /** Adds a new account and returns the generated [User.id]. */
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

    /** Finds the local profile for an email address. Called after Firebase has accepted a login or registration. */
    @Query("SELECT * FROM users WHERE email = :email COLLATE NOCASE LIMIT 1")
    suspend fun getByEmail(email: String): User?

    /** Every profile on this device that has a phone number recorded, for the sign-in-by-number lookup. */
    @Query("SELECT * FROM users WHERE phone IS NOT NULL AND phone != ''")
    suspend fun getAllWithPhone(): List<User>

    /** Observes an account together with all of its pets. */
    @Transaction
    @Query("SELECT * FROM users WHERE id = :userId")
    fun observeUserWithPets(userId: Long): Flow<UserWithPets?>
}
