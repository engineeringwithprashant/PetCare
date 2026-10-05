package com.prash.petcare.data.auth

/** What happened when the app asked for a password-reset email. */
sealed interface PasswordResetResult {

    /** The request was accepted. The email is sent if the address has an account. */
    data object Sent : PasswordResetResult

    /** The device could not reach Firebase. */
    data object NoNetwork : PasswordResetResult

    /** Firebase rejected the address as malformed, even though the app's own check accepted it. */
    data object InvalidEmail : PasswordResetResult

    /** Firebase's rate limit: too many reset requests from this device or for this address. */
    data object RateLimited : PasswordResetResult

    /** Any other failure. The ViewModel shows a general "try again" message. */
    data object Failed : PasswordResetResult
}

/** Sends password-reset emails. The Repository in the reset flow's MVVM chain. */
interface PasswordResetRepository {

    /** Asks the backend to email a reset link to [email]. */
    suspend fun sendResetEmail(email: String): PasswordResetResult
}
