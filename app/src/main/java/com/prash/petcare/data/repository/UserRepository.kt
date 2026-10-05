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
import com.prash.petcare.util.PhoneNumbers
import kotlinx.coroutines.flow.Flow

/** Supplies user accounts to the ViewModel layer. */
class UserRepository(
    private val userDao: UserDao,
    private val authService: FirebaseAuthService
) {

    /** Observes one account; emits null if it is deleted. */
    fun observeUser(userId: Long): Flow<User?> = userDao.observeById(userId)

    /** Observes an account together with all of its pets. */
    fun observeUserWithPets(userId: Long): Flow<UserWithPets?> = userDao.observeUserWithPets(userId)

    suspend fun getUser(userId: Long): User? = userDao.getById(userId)

    /** Creates a Firebase account, then the local profile for it, and returns the profile. */
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
            // A profile for this address can already be on the device.
            existing.copy(fullName = fullName, phone = typedPhone)
        }
        return AccountResult.Success(user)
    }

    /** Checks [email] and [password] with Firebase, then returns this device's profile for the account. */
    suspend fun signIn(email: String, password: String): AccountResult {
        val displayName = try {
            authService.signIn(email, password)
        } catch (e: AuthException) {
            return AccountResult.Failed(e.failure)
        }

        val fallbackName = displayName?.takeIf { it.isNotBlank() } ?: email.substringBefore('@')
        return AccountResult.Success(findOrCreateProfile(email, fallbackName, phone = null))
    }

    /** Finds the email address of the account whose stored phone number is [phoneNumber]. */
    suspend fun findEmailForPhone(phoneNumber: String): String? {
        val wanted = PhoneNumbers.parseLoginNumber(phoneNumber) ?: return null

        return userDao.getAllWithPhone()
            .firstOrNull { user ->
                user.phone?.let(PhoneNumbers::parseLoginNumber) == wanted
            }
            ?.email
    }

    /** Signs in with a Google ID token, then returns this device's profile for the account. */
    suspend fun signInWithGoogle(idToken: String): AccountResult {
        val account = try {
            authService.signInWithGoogle(idToken)
        } catch (e: AuthException) {
            return AccountResult.Failed(e.failure)
        }

        val name = account.displayName?.takeIf { it.isNotBlank() }
            ?: account.email.substringBefore('@')
        return AccountResult.Success(findOrCreateProfile(account.email, name, phone = null))
    }

    /** Signs the account out of Firebase on this device. The local profile and data stay. */
    fun signOut() = authService.signOut()

    /** Returns the local profile for [email], creating it with [fullName] and [phone] if there is none. */
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

    /** Changes the account's own details — the name. */
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
                // Blank and null mean the same thing — "no number given" — so an emptied field is stored as null.
                phone = phone?.trim()?.takeIf { it.isNotEmpty() },
                role = role
            )
        )
        return true
    }

    /** Replaces the account's profile photo, returning false if the account has gone. */
    suspend fun updatePhoto(userId: Long, photoPath: String?): Boolean {
        val user = userDao.getById(userId) ?: return false
        val previousPath = user.photoPath

        userDao.update(user.copy(photoPath = photoPath))

        if (previousPath != null && previousPath != photoPath) {
            PetPhotoStorage.delete(previousPath)
        }
        return true
    }

    /** Sets a new password in Firebase, after checking the current one. */
    suspend fun changePassword(
        userId: Long,
        currentPassword: String,
        newPassword: String
    ): PasswordChangeResult {
        val user = userDao.getById(userId) ?: return PasswordChangeResult.AccountMissing

        if (newPassword == currentPassword) {
            // Nothing would change, but the current password is still checked, by signing in again.
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

    /** Deletes the signed-in account everywhere it exists on this device and in Firebase Authentication. */
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

/** The result of registering or logging in. */
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

/** What happened when a password change was attempted. */
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

    /** This device is no longer signed in to Firebase as this account, for example. */
    SignInAgain,

    /** Any other failure. Nothing was changed. */
    Failed
}
