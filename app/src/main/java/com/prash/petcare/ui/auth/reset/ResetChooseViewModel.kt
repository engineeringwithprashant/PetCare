package com.prash.petcare.ui.auth.reset

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.prash.petcare.ui.auth.AuthValidation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Where the two option cards say the code would go.
 *
 * A null destination means "not known on this device" — the fragment then shows a generic line such
 * as "The email on your account" instead of an address.
 */
data class ResetDestinations(val email: String?, val maskedPhone: String?)

/** One-off outcome of reset step 1. */
sealed interface ResetChooseEvent {

    /**
     * A code has (notionally) been sent; move on to step 2.
     *
     * @property destination What step 2 should name as the destination — the address, or the masked
     *   number — or null if it is not known.
     */
    data class CodeSent(val channel: ResetChannel, val destination: String?) : ResetChooseEvent
}

/**
 * The ViewModel behind reset step 1 (ResetChooseFragment): which channel is chosen, and what each
 * option card shows.
 *
 * **Where the destinations come from.** For now, from whatever the user had typed into the log in
 * screen's "email or phone" field, passed along as a navigation argument. An email fills the email
 * card; a phone number fills the SMS card, masked. The card the user did not type anything for shows a
 * generic line.
 *
 * TODO(reset-backend): look the account up from the identifier (via the auth backend, not the device)
 *  and show the masked email *and* phone it returns. Asking the backend rather than the local database
 *  also avoids this screen confirming which addresses have accounts.
 *
 * @param savedStateHandle Holds the chosen channel across process death, and the `identifier`
 *   navigation argument.
 */
class ResetChooseViewModel(private val savedStateHandle: SavedStateHandle) : ViewModel() {

    private val identifier: String =
        savedStateHandle.get<String>(ResetChooseFragment.ARG_IDENTIFIER).orEmpty().trim()

    /** What each card shows beneath its title. Fixed for the life of the screen. */
    val destinations: ResetDestinations = ResetDestinations(
        email = identifier.takeIf(AuthValidation::isEmail),
        maskedPhone = identifier.takeIf(AuthValidation::isPhone)?.let(DestinationMasking::maskPhone)
    )

    /**
     * The chosen channel. Starts on SMS — the log in screen promises "Reset by SMS", and the mockup
     * shows it chosen — unless the user typed an email, in which case email is clearly what they know.
     */
    val selected: StateFlow<ResetChannel> = savedStateHandle.getStateFlow(
        KEY_SELECTED,
        if (destinations.email != null) ResetChannel.EMAIL else ResetChannel.SMS
    )

    private val _events = Channel<ResetChooseEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    /** An option card was tapped. */
    fun select(channel: ResetChannel) {
        savedStateHandle[KEY_SELECTED] = channel
    }

    /**
     * "SEND RESET CODE".
     *
     * TODO(reset-backend): ask the auth backend to send a reset code to the chosen channel for this
     *  account (in viewModelScope, off the main thread), and only move on once it confirms. Show a
     *  message and stay here if sending fails or is rate-limited. For now no code is sent; this moves
     *  straight to step 2 so the flow can be walked through.
     */
    fun onSendCode() {
        val channel = selected.value
        val destination = when (channel) {
            ResetChannel.EMAIL -> destinations.email
            ResetChannel.SMS -> destinations.maskedPhone
        }
        _events.trySend(ResetChooseEvent.CodeSent(channel, destination))
    }

    private companion object {
        const val KEY_SELECTED = "reset_choose.selected"
    }
}
