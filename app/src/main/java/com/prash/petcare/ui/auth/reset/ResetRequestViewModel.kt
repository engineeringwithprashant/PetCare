package com.prash.petcare.ui.auth.reset

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.prash.petcare.PetCareApplication
import com.prash.petcare.R
import com.prash.petcare.data.auth.PasswordResetRepository
import com.prash.petcare.data.auth.PasswordResetResult
import com.prash.petcare.ui.auth.AuthValidation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Everything the reset request screen draws.
 *
 * @property email What is typed in the field. The ViewModel keeps it because [canSend] depends on
 *   it.
 * @property emailError The format error under the field, or null. A resource id, not text, so the
 *   ViewModel needs no Context.
 * @property isSending True while Firebase is handling the request. The button is disabled during
 *   this time so a second tap cannot send a second email.
 * @property failure A message for a failure that is not about the address format (no network, rate
 *   limit), shown under the button. Null when there is nothing to report.
 */
data class ResetRequestUiState(
    val email: String = "",
    @param:StringRes val emailError: Int? = null,
    val isSending: Boolean = false,
    @param:StringRes val failure: Int? = null
) {
    /** "SEND RESET LINK" is enabled only while the address is well formed and nothing is sending. */
    val canSend: Boolean
        get() = !isSending && AuthValidation.validateEmail(email) == null
}

/** One-off outcomes of the reset request screen. */
sealed interface ResetRequestEvent {

    /** Firebase accepted the request. Show the confirmation for [email]. */
    data class LinkSent(val email: String) : ResetRequestEvent
}

/**
 * The **ViewModel** behind the reset request screen (ResetRequestFragment). It checks the typed
 * address, then asks [PasswordResetRepository] to send the reset email.
 *
 * **When the error appears, and when the button turns on.**
 *  - The button follows every keystroke. It is enabled exactly while the address is well formed,
 *    so pressing it never fails on format.
 *  - The error message waits until the user has finished with the field: it loses focus, or they
 *    press "done". Showing "Enter a valid email address" after the first letter would scold someone
 *    who has not finished typing. Once shown, the message goes away as soon as the address is valid.
 *
 * **Same result for every address.** The repository reports an unregistered address as
 * [PasswordResetResult.Sent], so this ViewModel moves to the confirmation either way and never
 * shows an "account not found" error.
 *
 * The typed text is not saved for process death. The EditText restores its own text and reports it
 * again through the same change listener, which rebuilds this state.
 *
 * @param repository The only way this ViewModel reaches Firebase, as MVVM requires.
 */
class ResetRequestViewModel(private val repository: PasswordResetRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(ResetRequestUiState())
    val uiState: StateFlow<ResetRequestUiState> = _uiState.asStateFlow()

    /** A Channel so each event is handled once, not re-delivered after a rotation. */
    private val _events = Channel<ResetRequestEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    /**
     * The text in the field changed. This updates the button. If an error is already showing, it is
     * re-checked against the new text, so it disappears as soon as the address becomes valid. If no
     * error is showing, typing never adds one. An older failure message is also cleared, since the
     * user is now working on the address.
     */
    fun onEmailChanged(text: String) {
        _uiState.update { state ->
            state.copy(
                email = text,
                // `?.let` runs only when an error is already showing.
                emailError = state.emailError?.let { AuthValidation.validateEmail(text) },
                failure = null
            )
        }
    }

    /**
     * The user has finished with the field. Show its format error now, if it has one.
     *
     * An empty field is left alone. The disabled button already shows that something is missing,
     * and a red "required" under a field the user has not typed in yet would come too early.
     */
    fun onEmailFinished() {
        val email = _uiState.value.email
        if (email.isBlank()) return
        _uiState.update { it.copy(emailError = AuthValidation.validateEmail(email)) }
    }

    /**
     * "SEND RESET LINK".
     *
     * The format is checked again here even though the button is disabled while it is invalid. The
     * keyboard's "done" key also calls this, so the rule has to hold here and not only in the
     * button's state.
     */
    fun onSendLink() {
        val state = _uiState.value
        if (state.isSending) return

        val formatError = AuthValidation.validateEmail(state.email)
        if (formatError != null) {
            _uiState.update { it.copy(emailError = formatError) }
            return
        }

        val email = state.email.trim()
        _uiState.update { it.copy(isSending = true, failure = null) }

        // viewModelScope, so the request keeps running through a rotation and is cancelled only if
        // the user leaves the screen.
        viewModelScope.launch {
            val result = repository.sendResetEmail(email)
            _uiState.update { it.copy(isSending = false) }

            when (result) {
                PasswordResetResult.Sent ->
                    _events.send(ResetRequestEvent.LinkSent(email))

                // Firebase rejected the address itself, so the error goes under the field, just
                // like the app's own format check.
                PasswordResetResult.InvalidEmail ->
                    _uiState.update { it.copy(emailError = R.string.error_email_invalid) }

                // Nothing is wrong with the address, so the message goes under the button.
                else -> _uiState.update { it.copy(failure = result.failureMessage()) }
            }
        }
    }

    companion object {

        /**
         * Builds the ViewModel with its repository from PetCareApplication. A factory is needed
         * because the default one can only build ViewModels whose constructor takes no arguments.
         */
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application =
                    this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as PetCareApplication
                ResetRequestViewModel(application.passwordResetRepository)
            }
        }
    }
}
