package com.prash.petcare.data.auth

import com.prash.petcare.data.remote.AuthException
import com.prash.petcare.data.remote.AuthFailure
import com.prash.petcare.data.remote.FirebaseAuthService

/**
 * The Firebase Authentication version of [PasswordResetRepository].
 *
 * **What Firebase does and what the app does.** Firebase sends the reset email, and the link in it
 * opens a page Firebase hosts, where the new password is chosen and saved. Because Firebase now
 * stores every account's password (see [FirebaseAuthService]), that new password is the one the log
 * in screen checks next time. The app never sees a code or the new password.
 *
 * **Turning failures into results.** [FirebaseAuthService] reports each failure as an [AuthFailure].
 * This class decides what each failure means for a *reset* request. The main decision is that "no
 * such account" counts as [PasswordResetResult.Sent], so the screen never shows whether an address
 * is registered.
 *
 * @param authService The app's single route to Firebase Authentication.
 */
class FirebasePasswordResetRepository(
    private val authService: FirebaseAuthService
) : PasswordResetRepository {

    override suspend fun sendResetEmail(email: String): PasswordResetResult = try {
        authService.sendPasswordResetEmail(email.trim())
        PasswordResetResult.Sent
    } catch (e: AuthException) {
        when (e.failure) {
            AuthFailure.NO_NETWORK -> PasswordResetResult.NoNetwork
            AuthFailure.RATE_LIMITED -> PasswordResetResult.RateLimited

            // "No account with this address" (or a disabled account). Firebase projects created
            // without email-enumeration protection still report this. It is treated as a success
            // so the screen looks the same whether or not the account exists.
            AuthFailure.ACCOUNT_UNAVAILABLE -> PasswordResetResult.Sent

            // For this call, invalid credentials can only mean the address is malformed.
            AuthFailure.INVALID_EMAIL, AuthFailure.INVALID_CREDENTIALS -> PasswordResetResult.InvalidEmail

            else -> PasswordResetResult.Failed
        }
    }
}
