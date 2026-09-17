package com.prash.petcare.ui.auth.reset

import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/**
 * Everything reset step 1 draws.
 *
 * @property channel The chosen card, which also decides which account field is visible.
 * @property emailError The format error under the email field, or null.
 * @property phoneError The format error under the phone field, or null.
 * @property canSend Whether "SEND RESET CODE" can be pressed: the visible field is correctly
 *   formatted.
 */
data class ResetChooseUiState(
    val channel: ResetChannel,
    @param:StringRes val emailError: Int? = null,
    @param:StringRes val phoneError: Int? = null,
    val canSend: Boolean = false
)

/** One-off outcome of reset step 1. */
sealed interface ResetChooseEvent {

    /**
     * A code has (notionally) been sent; move on to step 2.
     *
     * @property destination How step 2 should name where the code went — the address as typed, or
     *   the number masked.
     */
    data class CodeSent(val channel: ResetChannel, val destination: String) : ResetChooseEvent
}

/**
 * The ViewModel behind reset step 1 (ResetChooseFragment): which channel is chosen, what the user has
 * typed as the account's email or phone number, and whether that is good enough to send.
 *
 * **The two fields are validated differently on purpose.**
 *  - *Whether the button is enabled* follows every keystroke: it is on exactly while the visible
 *    field is correctly formatted, so there is never a moment where pressing it could fail on format.
 *  - *The error message* waits until the user has finished with the field (it loses focus, or they
 *    press "done"). Showing "Enter a valid email address" after the first letter typed would scold
 *    someone who has simply not finished yet. Once shown, the message disappears the moment the value
 *    becomes valid.
 *
 * Nothing is prefilled: the fields start empty and the user types the address on their account.
 *
 * The typed text is held here (forwarded from the fragment on every change) because the button's state
 * depends on it. It is *not* saved separately for process death: the EditTexts restore their own text,
 * and re-report it through the same change listener, which rebuilds this state.
 *
 * @param savedStateHandle Keeps the chosen channel across process death.
 */
class ResetChooseViewModel(private val savedStateHandle: SavedStateHandle) : ViewModel() {

    /**
     * The chosen channel. Starts on SMS — the log in screen promises "Reset by SMS".
     */
    private val channel: StateFlow<ResetChannel> =
        savedStateHandle.getStateFlow(KEY_SELECTED, ResetChannel.SMS)

    // What is currently typed in each field, keyed by the channel the field belongs to.
    private val typed = MutableStateFlow(mapOf(ResetChannel.EMAIL to "", ResetChannel.SMS to ""))

    // Errors currently showing, keyed the same way.
    private val errors = MutableStateFlow<Map<ResetChannel, Int>>(emptyMap())

    /**
     * The screen state, derived from the three sources above so the button and the errors can never
     * disagree with the text or the chosen card. Eagerly, so a value is ready the instant the
     * fragment starts collecting.
     */
    val uiState: StateFlow<ResetChooseUiState> =
        combine(channel, typed, errors) { selected, text, shownErrors ->
            ResetChooseUiState(
                channel = selected,
                emailError = shownErrors[ResetChannel.EMAIL],
                phoneError = shownErrors[ResetChannel.SMS],
                canSend = selected.validate(text.getValue(selected)) == null
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, ResetChooseUiState(channel.value))

    private val _events = Channel<ResetChooseEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    /** An option card was tapped. */
    fun select(selected: ResetChannel) {
        savedStateHandle[KEY_SELECTED] = selected
    }

    /**
     * The text in [field]'s account field changed. Updates the button, and removes that field's error
     * as soon as the value is valid — but never *adds* an error mid-typing (see the class notes).
     */
    fun onDestinationChanged(field: ResetChannel, text: String) {
        typed.update { it + (field to text) }
        if (field.validate(text) == null) errors.update { it - field }
    }

    /**
     * The user has finished with [field]'s account field (focus left it, or "done" was pressed): show
     * its format error now, if it has one. An untouched empty field is left alone — the disabled
     * button already says something is missing, and a red "required" under a field the user has not
     * reached yet would be premature.
     */
    fun onDestinationFinished(field: ResetChannel) {
        val text = typed.value.getValue(field)
        if (text.isBlank()) return
        setError(field, field.validate(text))
    }

    /**
     * "SEND RESET CODE".
     *
     * The format is checked again here even though the button is disabled while it is invalid: a
     * disabled button is a UI convenience, and the rule that matters must hold even if a tap slips
     * through (a keyboard "done" action, say).
     *
     * TODO(reset-backend): ask the auth backend to send a reset code to the typed address or number
     *  through the chosen channel (in viewModelScope, off the main thread), and only move on once it
     *  confirms. To avoid revealing which addresses have accounts, the backend should respond the same
     *  way whether or not the destination matches an account. Show a message and stay here if sending
     *  fails or is rate-limited. For now no code is sent; this moves straight to step 2 so the flow can
     *  be walked through.
     */
    fun onSendCode() {
        val selected = channel.value
        val text = typed.value.getValue(selected)
        val error = selected.validate(text)
        setError(selected, error)
        if (error != null) return

        _events.trySend(ResetChooseEvent.CodeSent(selected, selected.display(text)))
    }

    private fun setError(field: ResetChannel, @StringRes error: Int?) {
        errors.update { if (error == null) it - field else it + (field to error) }
    }

    private companion object {
        const val KEY_SELECTED = "reset_choose.selected"
    }
}
