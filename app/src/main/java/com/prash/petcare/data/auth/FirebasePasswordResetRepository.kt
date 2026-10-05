package com.prash.petcare.data.auth

import com.prash.petcare.data.remote.AuthException
import com.prash.petcare.data.remote.AuthFailure
import com.prash.petcare.data.remote.FirebaseAuthService

/** The Firebase Authentication version of [PasswordResetRepository]. */
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

            // "No account with this address" (or a disabled account).
            AuthFailure.ACCOUNT_UNAVAILABLE -> PasswordResetResult.Sent

            // For this call, invalid credentials can only mean the address is malformed.
            AuthFailure.INVALID_EMAIL, AuthFailure.INVALID_CREDENTIALS -> PasswordResetResult.InvalidEmail

            else -> PasswordResetResult.Failed
        }
    }
}
