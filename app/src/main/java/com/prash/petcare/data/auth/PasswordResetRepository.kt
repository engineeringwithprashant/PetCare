package com.prash.petcare.data.auth

/**
 * What happened when the app asked for a password-reset email.
 *
 * A sealed type of the app's own, rather than Firebase's exceptions, so the ViewModel above never
 * imports a Firebase class. It only has to handle these four cases, and the backend could change
 * without the ViewModel changing.
 *
 * There is deliberately **no "account not found" case.** Firebase does not say whether an address
 * is registered, and the app must not say so either: if it did, anyone could type addresses into
 * this screen to find out which ones have PetCare accounts. An unknown address counts as [Sent].
 */
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

/**
 * Sends password-reset emails. The **Repository** in the reset flow's MVVM chain:
 * `ResetRequestFragment` → `ResetRequestViewModel` → `PasswordResetRepository` → Firebase
 * Authentication.
 *
 * It is an interface so the one line in PetCareApplication that builds the Firebase version is the
 * only place that names Firebase. A test can pass a fake instead. The assistant's repository uses
 * the same pattern.
 *
 * This is a separate repository from UserRepository on purpose. Reset emails go to Firebase, while
 * UserRepository reads and writes the local Room accounts, and this change leaves those alone.
 */
interface PasswordResetRepository {

    /**
     * Asks the backend to email a reset link to [email].
     *
     * A `suspend` function, so the ViewModel can call it from `viewModelScope` without blocking the
     * main thread. It never throws for an expected failure. Every outcome comes back as a
     * [PasswordResetResult].
     */
    suspend fun sendResetEmail(email: String): PasswordResetResult
}
