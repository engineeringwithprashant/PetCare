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
import com.prash.petcare.data.local.LoginAttemptStore
import com.prash.petcare.ui.auth.AuthValidation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Everything the reset request screen draws. */
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

/** The ViewModel behind the reset request screen (ResetRequestFragment). */
class ResetRequestViewModel(
    private val repository: PasswordResetRepository,
    private val loginAttemptStore: LoginAttemptStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(ResetRequestUiState())
    val uiState: StateFlow<ResetRequestUiState> = _uiState.asStateFlow()

    /** A Channel so each event is handled once, not re-delivered after a rotation. */
    private val _events = Channel<ResetRequestEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    /** The text in the field changed. This updates the button. */
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

    /** The user has finished with the field. Show its format error now, if it has one. */
    fun onEmailFinished() {
        val email = _uiState.value.email
        if (email.isBlank()) return
        _uiState.update { it.copy(emailError = AuthValidation.validateEmail(email)) }
    }

    /** "SEND RESET LINK". */
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

        // viewModelScope, so the request keeps running through a rotation and is cancelled only if the user leaves.
        viewModelScope.launch {
            val result = repository.sendResetEmail(email)
            _uiState.update { it.copy(isSending = false) }

            when (result) {
                PasswordResetResult.Sent -> {
                    // Resetting the password gets the user back in straight away, as the locked screen promises.
                    loginAttemptStore.clear(email)
                    _events.send(ResetRequestEvent.LinkSent(email))
                }

                // Firebase rejected the address itself, so the error goes under the field.
                PasswordResetResult.InvalidEmail ->
                    _uiState.update { it.copy(emailError = R.string.error_email_invalid) }

                // Nothing is wrong with the address, so the message goes under the button.
                else -> _uiState.update { it.copy(failure = result.failureMessage()) }
            }
        }
    }

    companion object {

        /** Builds the ViewModel with its repository from PetCareApplication. */
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application =
                    this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as PetCareApplication
                ResetRequestViewModel(
                    repository = application.passwordResetRepository,
                    loginAttemptStore = application.loginAttemptStore
                )
            }
        }
    }
}
