package com.prash.petcare.ui.auth.twofactor

import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.prash.petcare.R
import com.prash.petcare.data.local.LoginAttemptStore
import com.prash.petcare.data.repository.TwoFactorCheck
import com.prash.petcare.data.repository.TwoFactorRepository
import com.prash.petcare.data.repository.UserRepository
import com.prash.petcare.ui.auth.CodeEntryViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** One-off outcomes of a two-factor code screen, delivered once each (see AuthEvent for why). */
sealed interface TwoFactorEvent {

    /** The code checked out; [userId] can now be signed in. */
    data class Verified(val userId: Long, val recoveryCodesRemaining: Int? = null) : TwoFactorEvent

    /** "LOST ACCESS?" — open the recovery-code screen for this account. */
    data class OpenRecovery(val userId: Long) : TwoFactorEvent
}

/** The shared half of the two screens that check a six-digit code against an account's second factor. */
abstract class TwoFactorCheckViewModel(
    savedStateHandle: SavedStateHandle,
    private val twoFactorRepository: TwoFactorRepository,
    private val userRepository: UserRepository,
    private val loginAttemptStore: LoginAttemptStore
) : CodeEntryViewModel(savedStateHandle) {

    /** The account that passed the password check and is waiting on its second factor. */
    protected val userId: Long = savedStateHandle[ARG_USER_ID] ?: NO_USER

    private val _events = Channel<TwoFactorEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    /** True while a check is in flight. */
    private var isChecking = false

    /** The verify button on either screen. */
    override fun onVerify() {
        if (!canVerify.value || userId == NO_USER || isChecking) return

        viewModelScope.launch {
            isChecking = true
            try {
                val email = userRepository.getUser(userId)?.email

                if (email != null && loginAttemptStore.recordFor(email).isLocked) {
                    rejectCode(R.string.two_factor_locked)
                    return@launch
                }

                when (val result = twoFactorRepository.verify(userId, code.value)) {
                    TwoFactorCheck.Correct -> succeed(email, recoveryCodesRemaining = null)

                    is TwoFactorCheck.RecoveryCodeAccepted ->
                        succeed(email, recoveryCodesRemaining = result.remaining)

                    TwoFactorCheck.RecoveryCodeAlreadyUsed ->
                        fail(email, R.string.two_factor_recovery_already_used)

                    TwoFactorCheck.Incorrect -> fail(email, wrongCodeMessage)

                    // The account's second factor was turned off while this screen was open — on another device.
                    TwoFactorCheck.NotEnabled -> succeed(email, recoveryCodesRemaining = null)
                }
            } finally {
                isChecking = false
            }
        }
    }

    /** What to say under the boxes when the code is simply not right. */
    @get:StringRes
    protected abstract val wrongCodeMessage: Int

    /** The code was accepted: end the run of failures and let the fragment sign the account in. */
    private suspend fun succeed(email: String?, recoveryCodesRemaining: Int?) {
        email?.let(loginAttemptStore::clear)
        _events.send(TwoFactorEvent.Verified(userId, recoveryCodesRemaining))
    }

    /** The code was refused: count it against the account and show [messageRes] under the boxes. */
    private fun fail(email: String?, @StringRes messageRes: Int) {
        val isNowLocked = email != null && loginAttemptStore.recordFailure(email).isLocked
        rejectCode(if (isNowLocked) R.string.two_factor_locked else messageRes)
    }

    /** Sends an event from a subclass — the "LOST ACCESS?" link is the only user of this. */
    protected fun send(event: TwoFactorEvent) {
        _events.trySend(event)
    }

    companion object {

        /** The navigation argument both screens are reached with: whose second factor is being checked. */
        const val ARG_USER_ID = "userId"

        /** Matches SessionViewModel.NO_USER_ID: the app's value for "no account". */
        const val NO_USER = -1L
    }
}
