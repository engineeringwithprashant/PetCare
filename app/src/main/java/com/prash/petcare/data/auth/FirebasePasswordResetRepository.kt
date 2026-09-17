package com.prash.petcare.data.auth

import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await

/**
 * The Firebase Authentication version of [PasswordResetRepository].
 *
 * **What Firebase does and what the app does.** `sendPasswordResetEmail` asks Firebase to send its
 * reset email. The link in that email opens a page that Firebase hosts, where the new password is
 * chosen and saved. The app never sees a code or the new password. Its only job is to ask for the
 * email and report whether the request went through.
 *
 * **Turning exceptions into results.** Firebase reports failures by throwing from the `Task`. This
 * class catches the ones the screen can explain and returns a [PasswordResetResult] for each, so
 * no Firebase type reaches the ViewModel.
 *
 * @param auth Passed in rather than fetched here, so the only call to `FirebaseAuth.getInstance()`
 *   is in PetCareApplication, next to the other Firebase setup.
 */
class FirebasePasswordResetRepository(private val auth: FirebaseAuth) : PasswordResetRepository {

    override suspend fun sendResetEmail(email: String): PasswordResetResult {
        // Write the email in the phone's language rather than the Firebase project's default.
        auth.useAppLanguage()

        return try {
            // `await()` (kotlinx-coroutines-play-services) suspends until the Task finishes. It
            // returns normally on success and throws the Task's exception on failure.
            auth.sendPasswordResetEmail(email.trim()).await()
            PasswordResetResult.Sent
        } catch (e: CancellationException) {
            // The screen was closed while the request was in flight. Rethrow so the coroutine is
            // cancelled properly instead of being reported as a failed send.
            throw e
        } catch (e: FirebaseNetworkException) {
            PasswordResetResult.NoNetwork
        } catch (e: FirebaseTooManyRequestsException) {
            PasswordResetResult.RateLimited
        } catch (e: FirebaseAuthInvalidUserException) {
            // "No account with this address" (or a disabled account). Older Firebase projects,
            // created without email-enumeration protection, still report this. Treat it as a
            // success so the screen looks the same whether or not the account exists.
            PasswordResetResult.Sent
        } catch (e: FirebaseAuthInvalidCredentialsException) {
            // For this call, invalid credentials can only mean the address is malformed.
            PasswordResetResult.InvalidEmail
        } catch (e: Exception) {
            // Anything else, such as the Email/Password provider being switched off in the
            // Firebase console. The screen shows a general "try again" message.
            PasswordResetResult.Failed
        }
    }
}
