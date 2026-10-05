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
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.UserProfileChangeRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await

/** Why a Firebase Authentication call failed, in the app's own terms. */
enum class AuthFailure {

    /** Wrong password, or no account with that email. Firebase reports both the same way. */
    INVALID_CREDENTIALS,

    /** Firebase found no account for the address, or the account is disabled. */
    ACCOUNT_UNAVAILABLE,

    /** Another account already uses this address: at registration. */
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

/** Who a Google sign-in turned out to be. */
data class SignedInAccount(val email: String, val displayName: String?)

/** Thrown by [FirebaseAuthService] for any failed call. [failure] says what went wrong. */
class AuthException(val failure: AuthFailure, cause: Throwable? = null) : Exception(cause)

/** The only class in the app that talks to Firebase Authentication. */
class FirebaseAuthService(private val auth: FirebaseAuth) {

    /** Creates a Firebase account and signs it in. [displayName] is saved on the Firebase account. */
    suspend fun createAccount(email: String, password: String, displayName: String) {
        val user = call { auth.createUserWithEmailAndPassword(email, password).await().user }
            ?: throw AuthException(AuthFailure.UNKNOWN)

        runCatching {
            val profile = UserProfileChangeRequest.Builder().setDisplayName(displayName).build()
            user.updateProfile(profile).await()
        }.onFailure { if (it is CancellationException) throw it }
    }

    /** Signs in with an email and password. */
    suspend fun signIn(email: String, password: String): String? {
        val user = call { auth.signInWithEmailAndPassword(email, password).await().user }
            ?: throw AuthException(AuthFailure.UNKNOWN)
        return user.displayName
    }

    /** Signs in to Firebase with a Google ID token. */
    suspend fun signInWithGoogle(idToken: String): SignedInAccount {
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        val user = call { auth.signInWithCredential(credential).await().user }
            ?: throw AuthException(AuthFailure.UNKNOWN)

        // Google accounts always have an email, and the app matches profiles by it.
        val email = user.email ?: throw AuthException(AuthFailure.UNKNOWN)
        return SignedInAccount(email = email, displayName = user.displayName)
    }

    /** Checks [currentPassword] again, then replaces it with [newPassword]. */
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

    /** Signs the Firebase user out on this device. Local only, with no network call, so it cannot fail. */
    fun signOut() = auth.signOut()

    /** Returns the signed-in Firebase user after checking [password] again. */
    private suspend fun reauthenticate(email: String, password: String): FirebaseUser {
        val user = auth.currentUser
        if (user == null || !user.email.equals(email, ignoreCase = true)) {
            throw AuthException(AuthFailure.NOT_SIGNED_IN)
        }
        call { user.reauthenticate(EmailAuthProvider.getCredential(email, password)).await() }
        return user
    }

    /** Runs one Firebase call and converts any exception it throws into an [AuthException]. */
    private suspend fun <T> call(block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        // The screen was closed while the call was in flight.
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
        // Should not happen, because the password is checked again first.
        throw AuthException(AuthFailure.NOT_SIGNED_IN, e)
    } catch (e: FirebaseAuthInvalidCredentialsException) {
        // One exception type for two problems: the error code separates a malformed address from a wrong password.
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
