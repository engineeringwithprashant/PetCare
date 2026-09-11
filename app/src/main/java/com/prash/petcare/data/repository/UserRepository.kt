package com.prash.petcare.data.repository

import com.prash.petcare.data.local.dao.UserDao
import com.prash.petcare.data.local.entity.User
import com.prash.petcare.data.local.relation.UserWithPets
import kotlinx.coroutines.flow.Flow

/**
 * Supplies user account data to the ViewModel layer.
 *
 * A repository is the boundary between "how data is stored" and "what the app needs". The
 * ViewModels talk only to this class, so they never import Room types and never see a DAO. That
 * separation is what makes the ViewModels testable — a fake repository can stand in for the
 * database — and means swapping local storage for a web API later would change only this file.
 *
 * Following MVVM, the dependency chain is one-way:
 * `Activity/Fragment` → `ViewModel` → `Repository` → `DAO`.
 *
 * @property userDao Injected through the constructor rather than created here, so tests can
 *   pass an in-memory implementation.
 */
class UserRepository(private val userDao: UserDao) {

    /** Observes one account; emits null if it is deleted. */
    fun observeUser(userId: Long): Flow<User?> = userDao.observeById(userId)

    /** Observes an account together with all of its pets. */
    fun observeUserWithPets(userId: Long): Flow<UserWithPets?> = userDao.observeUserWithPets(userId)

    suspend fun getUser(userId: Long): User? = userDao.getById(userId)

    /** Looks up an account by email address for sign-in; null when no such account exists. */
    suspend fun getUserByEmail(email: String): User? = userDao.getByEmail(email)

    /** True if the address is already registered — lets sign-up warn before attempting a write. */
    suspend fun isEmailTaken(email: String): Boolean = userDao.isEmailTaken(email)

    /**
     * Creates an account and returns its new id.
     *
     * The [User.passwordHash] must already be hashed by the caller; this layer stores what it is
     * given and never handles a plain-text password.
     */
    suspend fun addUser(user: User): Long = userDao.insert(user)

    suspend fun updateUser(user: User) = userDao.update(user)

    /** Deletes an account. Cascading rules remove that user's pets and everything beneath them. */
    suspend fun deleteUser(user: User) = userDao.delete(user)
}
