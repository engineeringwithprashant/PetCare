package com.prash.petcare.ui.auth.twofactor

import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import com.prash.petcare.R
import com.prash.petcare.ui.auth.CodeEntryViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

/** One-off outcomes of the two-factor screen, delivered once each (see AuthEvent for why). */
sealed interface TwoFactorEvent {

    /** The code checked out; [userId] can now be signed in. */
    data class Verified(val userId: Long) : TwoFactorEvent

    /** Something to tell the user that is not tied to the code boxes. */
    data class Message(@param:StringRes val messageRes: Int) : TwoFactorEvent
}

/**
 * The ViewModel behind the two-factor check (TwoFactorFragment).
 *
 * Everything about *typing* the code — the digits, the "wrong code" state, when the verify button is
 * enabled — is inherited from [CodeEntryViewModel], shared with the reset-code screen. This class adds
 * only what is particular to two-factor: which account is being checked, and what verify and "lost
 * access" do.
 *
 * **UI only for now.** The screen, its input and its states are complete; checking the code against an
 * authenticator is not built yet. The stubs below **fail closed**: pressing verify never signs anyone
 * in. A stub that let any six digits through would make this screen look like protection it is not.
 *
 * @param savedStateHandle Holds the typed code (see the base class) and the `userId` navigation
 *   argument, which the default ViewModel factory copies in from the fragment's arguments.
 */
class TwoFactorViewModel(savedStateHandle: SavedStateHandle) : CodeEntryViewModel(savedStateHandle) {

    /** The account that passed the password check and is waiting on its second factor. */
    private val userId: Long = savedStateHandle[TwoFactorFragment.ARG_USER_ID] ?: NO_USER

    private val _events = Channel<TwoFactorEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    /**
     * "VERIFY & LOG IN".
     *
     * TODO(2fa): verify `code.value` for `userId` against the account's authenticator (TOTP) secret via
     *  the auth backend, off the main thread (viewModelScope.launch + withContext(Dispatchers.IO)).
     *  - Correct: `_events.send(TwoFactorEvent.Verified(userId))` — TwoFactorFragment already records
     *    the session and navigates home for that event.
     *  - Wrong or expired: call `rejectCode()` to show the error state and clear the boxes.
     *  Until then this signs nobody in and only says the check is unavailable.
     */
    override fun onVerify() {
        if (!canVerify.value || userId == NO_USER) return
        _events.trySend(TwoFactorEvent.Message(R.string.two_factor_unavailable))
    }

    /**
     * "LOST ACCESS?".
     *
     * TODO(2fa-recovery): route to account recovery (backup codes, or a reset of the second factor by
     *  email/SMS) once that flow exists. There is no recovery screen yet, so this only says so.
     */
    fun onLostAccess() {
        _events.trySend(TwoFactorEvent.Message(R.string.two_factor_lost_access_unavailable))
    }

    private companion object {
        /** Matches SessionViewModel.NO_USER_ID: the app's value for "no account". */
        const val NO_USER = -1L
    }
}
