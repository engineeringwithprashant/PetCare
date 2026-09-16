package com.prash.petcare.data.repository

import com.prash.petcare.data.local.dao.UserDao
import com.prash.petcare.data.local.entity.User
import com.prash.petcare.data.local.entity.UserRole
import com.prash.petcare.data.local.relation.UserWithPets
import com.prash.petcare.util.PasswordHasher
import com.prash.petcare.util.PetPhotoStorage
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

    /**
     * Changes the account's own details — the name, contact number and role behind the profile
     * screen's "Name & details" row.
     *
     * Reads the row first and copies it, rather than taking a whole [User] from the caller. That is
     * what keeps the password hash and the creation date out of the ViewModel entirely: a screen that
     * edits a name has no business holding a credential, and a `copy` performed here cannot
     * accidentally write a stale hash back over a password changed on another screen a moment before.
     *
     * Returns false when the account no longer exists — deleted on another device and pulled down by
     * a sync while the form was open — so the caller can say so instead of failing silently.
     *
     * The email is deliberately **not** editable here. It is the account's login identifier and the
     * key the Firestore sync files this user's data under
     * ([com.prash.petcare.data.sync.SyncManager]), so changing it would strand everything already
     * uploaded under the old address.
     */
    suspend fun updateDetails(
        userId: Long,
        fullName: String,
        phone: String?,
        role: UserRole
    ): Boolean {
        val user = userDao.getById(userId) ?: return false

        userDao.update(
            user.copy(
                fullName = fullName.trim(),
                // Blank and null mean the same thing — "no number given" — so an emptied field is
                // stored as null rather than as an empty string that would later print as a gap.
                phone = phone?.trim()?.takeIf { it.isNotEmpty() },
                role = role
            )
        )
        return true
    }

    /**
     * Replaces the account's profile photo, returning false if the account has gone.
     *
     * The *previous* file is deleted here, and that belongs in this layer rather than in the screen:
     * the repository is the one place that knows what the old path was before the new one overwrote
     * it, and a photo left on disk with nothing pointing at it would never be cleaned up. Deleting
     * after the row is updated is the safe order — if the write fails, the old file is still there
     * and still referenced.
     *
     * @param photoPath The new image's absolute path, or null to remove the photo entirely.
     */
    suspend fun updatePhoto(userId: Long, photoPath: String?): Boolean {
        val user = userDao.getById(userId) ?: return false
        val previousPath = user.photoPath

        userDao.update(user.copy(photoPath = photoPath))

        if (previousPath != null && previousPath != photoPath) {
            PetPhotoStorage.delete(previousPath)
        }
        return true
    }

    /**
     * Sets a new password, after checking the current one.
     *
     * **The check happens here rather than on the screen**, and deliberately so: verifying means
     * comparing against the stored hash, and the hash should not travel up to a ViewModel for a
     * screen to compare. Everything that touches the credential stays in this file and
     * [PasswordHasher].
     *
     * Both hashing calls are genuinely slow — PBKDF2 at 100,000 iterations, by design — which is why
     * this is a `suspend` function callers must run off the main thread.
     *
     * [User.passwordChangedAt] is stamped in the same write, so the profile screen can say when the
     * password last changed without a second source that could disagree with it.
     *
     * @return what went wrong, or [PasswordChangeResult.Success].
     */
    suspend fun changePassword(
        userId: Long,
        currentPassword: String,
        newPassword: String
    ): PasswordChangeResult {
        val user = userDao.getById(userId) ?: return PasswordChangeResult.AccountMissing

        if (!PasswordHasher.verify(currentPassword, user.passwordHash)) {
            return PasswordChangeResult.WrongCurrentPassword
        }

        // Refusing a no-op rather than accepting it quietly. Someone who types their existing
        // password twice has misunderstood the form, and reporting "changed" would leave them
        // believing something happened that did not.
        if (PasswordHasher.verify(newPassword, user.passwordHash)) {
            return PasswordChangeResult.SameAsCurrent
        }

        userDao.update(
            user.copy(
                passwordHash = PasswordHasher.hash(newPassword),
                passwordChangedAt = System.currentTimeMillis()
            )
        )
        return PasswordChangeResult.Success
    }

    /** Deletes an account. Cascading rules remove that user's pets and everything beneath them. */
    suspend fun deleteUser(user: User) = userDao.delete(user)

    /**
     * Deletes the signed-in account and everything belonging to it.
     *
     * The pets, routines, tasks, expenses, places and completion history all go with it, because
     * every one of those tables declares a foreign key to `users` with `ON DELETE CASCADE` — SQLite
     * removes them itself in the same statement. That is why this is one `delete` rather than a list
     * of them: a hand-written sweep would have to be kept in step with every table added later, and
     * the first one forgotten would leave orphaned rows behind.
     *
     * The profile photo is removed separately, since a file on disk is not something a foreign key
     * can cascade to.
     *
     * Returns false if the account was already gone, which makes calling this twice harmless.
     */
    suspend fun deleteAccount(userId: Long): Boolean {
        val user = userDao.getById(userId) ?: return false

        userDao.delete(user)
        PetPhotoStorage.delete(user.photoPath)
        return true
    }
}

/**
 * What happened when a password change was attempted.
 *
 * A sealed set of outcomes rather than a Boolean, because the screen has to tell the user *which*
 * thing went wrong — "that is not your current password" and "that is the password you already have"
 * need different messages, and a false could not distinguish them.
 */
enum class PasswordChangeResult {

    /** The password was changed. */
    Success,

    /** The current password typed does not match the stored one. */
    WrongCurrentPassword,

    /** The new password is the one already in use, so there is nothing to change. */
    SameAsCurrent,

    /** The account no longer exists — deleted while the form was open. */
    AccountMissing
}
