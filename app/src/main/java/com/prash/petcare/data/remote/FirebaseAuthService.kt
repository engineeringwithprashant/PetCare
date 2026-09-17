package com.prash.petcare.data.remote

import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.UserProfileChangeRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await

/**
 * Why a Firebase Authentication call failed, in the app's own terms.
 *
 * Repositories and ViewModels see this enum and never a Firebase exception class. That keeps
 * Firebase out of every layer above the data layer.
 */
enum class AuthFailure {

    /** Wrong password, or no account with that email. Firebase reports both the same way. */
    INVALID_CREDENTIALS,

    /** Firebase found no account for the address, or the account is disabled. */
    ACCOUNT_UNAVAILABLE,

    /** Registration: another account already uses this address. */
    EMAIL_IN_USE,

    /** Registration or password change: the password fails the project's password policy. */
    WEAK_PASSWORD,

    /** Firebase rejected the address as malformed. */
    INVALID_EMAIL,

    /** The device could not reach Firebase. */
    NO_NETWORK,

    /** Firebase's rate limit: too many attempts from this device or for this account. */
    RATE_LIMITED,

    /** No Firebase user is signed in on this device, or it is not the account expected. */
    NOT_SIGNED_IN,

    /** Anything else, such as the Email/Password provider being switched off in the console. */
    UNKNOWN
}

/** Thrown by [FirebaseAuthService] for any failed call. [failure] says what went wrong. */
class AuthException(val failure: AuthFailure, cause: Throwable? = null) : Exception(cause)

/**
 * The only class in the app that talks to Firebase Authentication. It is the auth counterpart of
 * [FirestoreSyncService].
 *
 * **What Firebase is responsible for.** Firebase stores every account's email and password and
 * checks them. The app no longer keeps a password or a password hash anywhere on the device. The
 * local Room `users` row is only the account's profile (name, phone, role, photo) and the owner that
 * pets and other records point to. [com.prash.petcare.data.repository.UserRepository] keeps the two
 * in step, matched by email address.
 *
 * **Like a DAO, but for a remote service.** Every method is a `suspend` function that either
 * finishes or throws [AuthException]. Deciding what a failure means for the screen is the
 * repository's job. `await()` (kotlinx-coroutines-play-services) turns each Firebase `Task` into a
 * suspending call.
 *
 * **Changing the password or deleting the account asks for the password first.** Firebase only
 * allows these right after a login and otherwise throws "recent login required". Checking the
 * password again first (re-authentication) also confirms it is the account owner making the change
 * rather than someone who picked up an unlocked phone.
 *
 * @property auth Injected, so PetCareApplication is the only place that calls
 *   `FirebaseAuth.getInstance()`.
 */
class FirebaseAuthService(private val auth: FirebaseAuth) {

    /**
     * Creates a Firebase account and signs it in. [displayName] is saved on the Firebase account so
     * that a login on another device can fill in the local profile's name.
     *
     * If saving the name fails, the call still succeeds. The account exists by then, and only the
     * name on a *future* device would be affected. Failing here would tell the user registration
     * failed when it had worked.
     */
    suspend fun createAccount(email: String, password: String, displayName: String) {
        val user = call { auth.createUserWithEmailAndPassword(email, password).await().user }
            ?: throw AuthException(AuthFailure.UNKNOWN)

        runCatching {
            val profile = UserProfileChangeRequest.Builder().setDisplayName(displayName).build()
            user.updateProfile(profile).await()
        }.onFailure { if (it is CancellationException) throw it }
    }

    /**
     * Signs in with an email and password.
     *
     * @return The display name saved on the account, or null if it has none. Used to create the
     *   local profile when this device has never seen the account before.
     */
    suspend fun signIn(email: String, password: String): String? {
        val user = call { auth.signInWithEmailAndPassword(email, password).await().user }
            ?: throw AuthException(AuthFailure.UNKNOWN)
        return user.displayName
    }

    /**
     * Checks [currentPassword] again, then replaces it with [newPassword]. Throws
     * [AuthFailure.INVALID_CREDENTIALS] if the current password is wrong.
     */
    suspend fun changePassword(email: String, currentPassword: String, newPassword: String) {
        val user = reauthenticate(email, currentPassword)
        call { user.updatePassword(newPassword).await() }
    }

    /** Checks [password] again, then permanently deletes the Firebase account for [email]. */
    suspend fun deleteAccount(email: String, password: String) {
        val user = reauthenticate(email, password)
        call { user.delete().await() }
    }

    /** Asks Firebase to email a password-reset link to [email]. */
    suspend fun sendPasswordResetEmail(email: String) {
        // Write the email in the phone's language rather than the Firebase project's default.
        auth.useAppLanguage()
        call { auth.sendPasswordResetEmail(email).await() }
    }

    /**
     * Signs the Firebase user out on this device. Local only, with no network call, so it cannot
     * fail. After this, Firestore requests are no longer made as this account.
     */
    fun signOut() = auth.signOut()

    /**
     * Returns the signed-in Firebase user after checking [password] again.
     *
     * It first checks that the signed-in Firebase user is the account being changed. Emails are
     * compared case-insensitively, matching how the local database looks accounts up.
     */
    private suspend fun reauthenticate(email: String, password: String): FirebaseUser {
        val user = auth.currentUser
        if (user == null || !user.email.equals(email, ignoreCase = true)) {
            throw AuthException(AuthFailure.NOT_SIGNED_IN)
        }
        call { user.reauthenticate(EmailAuthProvider.getCredential(email, password)).await() }
        return user
    }

    /**
     * Runs one Firebase call and converts any exception it throws into an [AuthException].
     *
     * The order of the `catch` blocks matters. Kotlin uses the first block whose type matches, and
     * several Firebase exceptions are subclasses of others. For example, the weak-password exception
     * is a kind of invalid-credentials exception, so it has to be caught first.
     */
    private suspend fun <T> call(block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        // The screen was closed while the call was in flight. Rethrow so the coroutine is cancelled
        // properly instead of being reported as a failure.
        throw e
    } catch (e: FirebaseNetworkException) {
        throw AuthException(AuthFailure.NO_NETWORK, e)
    } catch (e: FirebaseTooManyRequestsException) {
        throw AuthException(AuthFailure.RATE_LIMITED, e)
    } catch (e: FirebaseAuthWeakPasswordException) {
        throw AuthException(AuthFailure.WEAK_PASSWORD, e)
    } catch (e: FirebaseAuthUserCollisionException) {
        throw AuthException(AuthFailure.EMAIL_IN_USE, e)
    } catch (e: FirebaseAuthInvalidUserException) {
        throw AuthException(AuthFailure.ACCOUNT_UNAVAILABLE, e)
    } catch (e: FirebaseAuthRecentLoginRequiredException) {
        // Should not happen, because the password is checked again first. If it does, signing in
        // again is the fix, which is what NOT_SIGNED_IN tells the screen.
        throw AuthException(AuthFailure.NOT_SIGNED_IN, e)
    } catch (e: FirebaseAuthInvalidCredentialsException) {
        // One exception type for two problems: the error code separates a malformed address from
        // a wrong password.
        val failure = if (e.errorCode == "ERROR_INVALID_EMAIL") {
            AuthFailure.INVALID_EMAIL
        } else {
            AuthFailure.INVALID_CREDENTIALS
        }
        throw AuthException(failure, e)
    } catch (e: AuthException) {
        throw e
    } catch (e: Exception) {
        throw AuthException(AuthFailure.UNKNOWN, e)
    }
}
