package com.prash.petcare.data.repository

import android.database.sqlite.SQLiteConstraintException
import com.prash.petcare.data.local.dao.UserDao
import com.prash.petcare.data.local.entity.User
import com.prash.petcare.data.local.entity.UserRole
import com.prash.petcare.data.local.relation.UserWithPets
import com.prash.petcare.data.remote.AuthException
import com.prash.petcare.data.remote.AuthFailure
import com.prash.petcare.data.remote.FirebaseAuthService
import com.prash.petcare.util.PetPhotoStorage
import kotlinx.coroutines.flow.Flow

/**
 * Supplies user accounts to the ViewModel layer, and is the only class that keeps the two halves of
 * an account in step.
 *
 * **Where an account is stored.**
 *  - **Firebase Authentication** stores the email and password, and checks them at login. No
 *    password or password hash is kept on the device.
 *  - **The Room `users` table** stores the profile (name, phone, role, photo). Its id is what pets,
 *    places and every other record point to as their owner.
 *
 * The two are matched by email address, which is also the key the Firestore sync files the
 * account's data under. Each method below that touches both does so in an order that leaves no half-made
 * account behind. See [register], [signIn] and [deleteAccount].
 *
 * A repository is the boundary between "how data is stored" and "what the app needs". ViewModels
 * talk only to this class, so they never see a DAO or a Firebase class, and a fake repository can
 * stand in for both in a test.
 *
 * Following MVVM, the dependency chain is one-way:
 * `Activity/Fragment` → `ViewModel` → `Repository` → `DAO` / remote service.
 *
 * @property userDao The local profile table.
 * @property authService The app's single route to Firebase Authentication.
 */
class UserRepository(
    private val userDao: UserDao,
    private val authService: FirebaseAuthService
) {

    /** Observes one account; emits null if it is deleted. */
    fun observeUser(userId: Long): Flow<User?> = userDao.observeById(userId)

    /** Observes an account together with all of its pets. */
    fun observeUserWithPets(userId: Long): Flow<UserWithPets?> = userDao.observeUserWithPets(userId)

    suspend fun getUser(userId: Long): User? = userDao.getById(userId)

    /**
     * Creates a Firebase account, then the local profile for it, and returns the profile.
     *
     * **Firebase first.** Firebase decides whether the address is free, so nothing is written
     * locally until it has accepted the account. If the local write then fails, the Firebase account
     * still exists, and logging in with it creates the missing profile (see [signIn]). The user is
     * never left with an account they cannot use.
     *
     * The password goes to Firebase and is not stored or kept anywhere in this class.
     *
     * @param phone Stored as typed (trimmed by the caller). Normalising it to one international
     *   format is left to whichever service ends up sending SMS.
     * @return The new profile, or the reason registration failed.
     */
    suspend fun register(
        fullName: String,
        email: String,
        phone: String,
        password: String
    ): AccountResult {
        try {
            authService.createAccount(email, password, displayName = fullName)
        } catch (e: AuthException) {
            return AccountResult.Failed(e.failure)
        }

        val typedPhone = phone.takeIf { it.isNotBlank() }
        val user = findOrCreateProfile(email, fullName, typedPhone) { existing ->
            // A profile for this address can already be on the device, left from an earlier account
            // that was deleted in the Firebase console. Its name and phone are replaced with the ones
            // just typed.
            existing.copy(fullName = fullName, phone = typedPhone)
        }
        return AccountResult.Success(user)
    }

    /**
     * Checks [email] and [password] with Firebase, then returns this device's profile for the
     * account.
     *
     * **A first login on this device** has no local profile yet, so one is created, named with the
     * display name saved on the Firebase account at registration. That is also what lets the
     * Firestore sync restore the account's pets on a new phone.
     */
    suspend fun signIn(email: String, password: String): AccountResult {
        val displayName = try {
            authService.signIn(email, password)
        } catch (e: AuthException) {
            return AccountResult.Failed(e.failure)
        }

        val fallbackName = displayName?.takeIf { it.isNotBlank() } ?: email.substringBefore('@')
        return AccountResult.Success(findOrCreateProfile(email, fallbackName, phone = null))
    }

    /**
     * Signs the account out of Firebase on this device. The local profile and data stay, ready for
     * the next login.
     */
    fun signOut() = authService.signOut()

    /**
     * Returns the local profile for [email], creating it with [fullName] and [phone] if there is
     * none.
     *
     * @param updateExisting Applied to a profile that already exists. Returning it unchanged (the
     *   default) leaves the row untouched.
     *
     * The database's unique index on email is the real guard against duplicates. If two calls race,
     * the losing insert throws [SQLiteConstraintException], and the row the other call wrote is read
     * back instead.
     */
    private suspend fun findOrCreateProfile(
        email: String,
        fullName: String,
        phone: String?,
        updateExisting: (User) -> User = { it }
    ): User {
        userDao.getByEmail(email)?.let { existing ->
            val updated = updateExisting(existing)
            if (updated != existing) userDao.update(updated)
            return updated
        }

        val newUser = User(fullName = fullName, email = email, phone = phone)
        return try {
            newUser.copy(id = userDao.insert(newUser))
        } catch (e: SQLiteConstraintException) {
            userDao.getByEmail(email) ?: throw e
        }
    }

    suspend fun updateUser(user: User) = userDao.update(user)

    /**
     * Changes the account's own details — the name, contact number and role behind the profile
     * screen's "Name & details" row.
     *
     * Reads the row first and copies it, rather than taking a whole [User] from the caller. The
     * screen then never holds fields it has no business changing, such as the creation date. A
     * `copy` made here also cannot write back an old password-change date over one set on another
     * screen a moment before.
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
     * Sets a new password in Firebase, after checking the current one.
     *
     * Firebase checks the current password (see [FirebaseAuthService.changePassword]), so this
     * class never compares passwords itself. The one comparison made here is between the two
     * *typed* values, to turn down a "change" to the same password: someone who types their
     * existing password twice has misunderstood the form, and reporting "changed" would mislead
     * them. That reply is given only after Firebase has accepted the current password. Otherwise a
     * wrong current password typed twice would get "that's already your password" instead of
     * "that's not your password".
     *
     * [User.passwordChangedAt] is set once Firebase confirms the change, so the profile screen can
     * say when the password last changed.
     *
     * @return What went wrong, or [PasswordChangeResult.Success].
     */
    suspend fun changePassword(
        userId: Long,
        currentPassword: String,
        newPassword: String
    ): PasswordChangeResult {
        val user = userDao.getById(userId) ?: return PasswordChangeResult.AccountMissing

        if (newPassword == currentPassword) {
            // Nothing would change, but the current password is still checked, by signing in again,
            // so a wrong one gets "wrong password" rather than "same password".
            return try {
                authService.signIn(user.email, currentPassword)
                PasswordChangeResult.SameAsCurrent
            } catch (e: AuthException) {
                e.failure.toPasswordChangeResult()
            }
        }

        try {
            authService.changePassword(user.email, currentPassword, newPassword)
        } catch (e: AuthException) {
            return e.failure.toPasswordChangeResult()
        }

        userDao.update(user.copy(passwordChangedAt = System.currentTimeMillis()))
        return PasswordChangeResult.Success
    }

    /**
     * Deletes the signed-in account everywhere it exists on this device and in Firebase
     * Authentication, after checking [password].
     *
     * **Firebase first, then the device.** If only the local data were deleted, the Firebase
     * account would still accept a login, which would create a fresh profile and let the sync bring
     * the pets back. The account would effectively be restored. So the local rows are deleted only
     * once Firebase has confirmed the account is gone. If Firebase fails (no network, wrong
     * password), nothing is deleted and the user can try again.
     *
     * The pets, routines, tasks, expenses, places and completion history all go with the local row,
     * because every one of those tables declares a foreign key to `users` with `ON DELETE CASCADE`.
     * SQLite removes them itself in the same statement. That is why this is one `delete` rather
     * than a list of them: a hand-written sweep would have to be kept in step with every table added
     * later, and the first one forgotten would leave orphaned rows behind.
     *
     * The profile photo is removed separately, since a file on disk is not something a foreign key
     * can cascade to.
     */
    suspend fun deleteAccount(userId: Long, password: String): AccountDeletionResult {
        val user = userDao.getById(userId) ?: return AccountDeletionResult.AccountMissing

        try {
            authService.deleteAccount(user.email, password)
        } catch (e: AuthException) {
            return AccountDeletionResult.Failed(e.failure)
        }

        userDao.delete(user)
        PetPhotoStorage.delete(user.photoPath)
        return AccountDeletionResult.Deleted
    }
}

/** Turns a Firebase failure during a password change into what the change-password form shows. */
private fun AuthFailure.toPasswordChangeResult(): PasswordChangeResult = when (this) {
    AuthFailure.INVALID_CREDENTIALS -> PasswordChangeResult.WrongCurrentPassword
    AuthFailure.WEAK_PASSWORD -> PasswordChangeResult.WeakPassword
    AuthFailure.NO_NETWORK -> PasswordChangeResult.NoNetwork
    AuthFailure.RATE_LIMITED -> PasswordChangeResult.RateLimited
    AuthFailure.NOT_SIGNED_IN, AuthFailure.ACCOUNT_UNAVAILABLE -> PasswordChangeResult.SignInAgain
    else -> PasswordChangeResult.Failed
}

/**
 * The result of registering or logging in.
 *
 * A sealed type rather than a nullable [User], because the screen has to say *why* it failed: an
 * address already in use goes under the email field, while a lost connection is a message.
 */
sealed interface AccountResult {

    /** The account is ready. [user] is this device's profile for it. */
    data class Success(val user: User) : AccountResult

    /** Nothing was signed in. [reason] says why. */
    data class Failed(val reason: AuthFailure) : AccountResult
}

/** The result of [UserRepository.deleteAccount]. */
sealed interface AccountDeletionResult {

    /** The Firebase account and every local record for it have been deleted. */
    data object Deleted : AccountDeletionResult

    /** The local profile was already gone, so there was nothing to delete. */
    data object AccountMissing : AccountDeletionResult

    /** Firebase refused or could not be reached, so nothing was deleted. [reason] says why. */
    data class Failed(val reason: AuthFailure) : AccountDeletionResult
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

    /** Firebase says the current password typed is wrong. */
    WrongCurrentPassword,

    /** The new password is the one already in use, so there is nothing to change. */
    SameAsCurrent,

    /** The account no longer exists — deleted while the form was open. */
    AccountMissing,

    /** Firebase rejected the new password under the project's password policy. */
    WeakPassword,

    /** Firebase could not be reached. Nothing was changed. */
    NoNetwork,

    /** Too many attempts. Firebase is refusing for now. */
    RateLimited,

    /**
     * This device is no longer signed in to Firebase as this account, for example because the
     * session was ended elsewhere. Logging out and back in fixes it.
     */
    SignInAgain,

    /** Any other failure. Nothing was changed. */
    Failed
}
