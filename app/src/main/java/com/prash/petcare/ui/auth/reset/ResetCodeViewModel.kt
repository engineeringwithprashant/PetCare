package com.prash.petcare.ui.auth.reset

import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import com.prash.petcare.R
import com.prash.petcare.ui.auth.CodeEntryViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

/** One-off outcomes of reset step 2. */
sealed interface ResetCodeEvent {

    /** The code was accepted; move on to choosing a new password. */
    data object CodeAccepted : ResetCodeEvent

    /** Something to tell the user that is not tied to the code boxes. */
    data class Message(@param:StringRes val messageRes: Int) : ResetCodeEvent
}

/**
 * The ViewModel behind reset step 2 (ResetCodeFragment).
 *
 * Typing the code — digits, backspace, the error state, when "VERIFY CODE" is enabled — is inherited
 * from [CodeEntryViewModel], exactly as on the two-factor screen. This class adds where the code was
 * sent, how long it lasts, and what verify and resend do.
 *
 * **Why verify moves on here, when the two-factor stub does not.** Passing this screen grants nothing:
 * step 3 only collects a new password, and saving it is itself a stub that changes nothing. So letting
 * the flow continue is harmless and lets the three steps be walked through. On the two-factor screen,
 * passing *would* sign someone in, so that stub refuses instead.
 *
 * @param savedStateHandle The typed code (see the base class) and the `channel` / `destination`
 *   navigation arguments.
 */
class ResetCodeViewModel(savedStateHandle: SavedStateHandle) : CodeEntryViewModel(savedStateHandle) {

    /** Where the code was sent. */
    val channel: ResetChannel =
        ResetChannel.fromName(savedStateHandle[ResetCodeFragment.ARG_CHANNEL])

    /** The address or masked number it was sent to, as typed on step 1; null only if missing. */
    val destination: String? = savedStateHandle[ResetCodeFragment.ARG_DESTINATION]

    /**
     * How long the code stays valid, in minutes.
     *
     * TODO(reset-backend): take this from the backend's response when the code is sent, rather than
     *  assuming the ten minutes the design states.
     */
    val expiryMinutes: Int = DEFAULT_EXPIRY_MINUTES

    private val _events = Channel<ResetCodeEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    /**
     * "VERIFY CODE".
     *
     * TODO(reset-backend): send `code.value` to the auth backend to verify (in viewModelScope, off the
     *  main thread). On success keep the reset token it returns — step 3 needs it, so pass it as a
     *  navigation argument — then send CodeAccepted. On a wrong or expired code call `rejectCode()`
     *  instead. For now the code is not checked and the flow simply moves on.
     */
    override fun onVerify() {
        if (!canVerify.value) return
        _events.trySend(ResetCodeEvent.CodeAccepted)
    }

    /**
     * "RESEND".
     *
     * TODO(reset-backend): ask the backend to send a fresh code to the same channel, then reset the
     *  expiry and clear the boxes. Consider a cool-down (disable the link for ~30 seconds) so it cannot
     *  be used to flood someone's phone. For now nothing is sent.
     */
    fun onResend() {
        _events.trySend(ResetCodeEvent.Message(R.string.reset_code_resend_unavailable))
    }

    private companion object {
        const val DEFAULT_EXPIRY_MINUTES = 10
    }
}
