package com.prash.petcare.ui.auth.reset

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import com.prash.petcare.R
import com.prash.petcare.ui.auth.AuthValidation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update

/**
 * The validation errors showing on reset step 3, as string resource ids (null = no error). Resource
 * ids rather than text keep Context out of the ViewModel; see AuthUiState.
 */
data class NewPasswordErrors(
    @param:StringRes val password: Int? = null,
    @param:StringRes val confirmation: Int? = null
)

/** One-off outcome of reset step 3. */
sealed interface NewPasswordEvent {
    data class Message(@param:StringRes val messageRes: Int) : NewPasswordEvent
}

/**
 * The ViewModel behind reset step 3 (ResetNewPasswordFragment): check the new password, then save it.
 *
 * The rules are the register form's — at least [AuthValidation.MIN_PASSWORD_LENGTH] characters, and
 * the two entries must match — taken from [AuthValidation] so the two screens cannot disagree.
 *
 * No repository yet. Saving a reset password needs the reset token from step 2 and a backend call;
 * the existing [com.prash.petcare.data.repository.UserRepository.changePassword] needs the *current*
 * password, which is exactly what someone resetting it does not have. Since this task leaves the data
 * layer unchanged, the save is a stub.
 */
class ResetNewPasswordViewModel : ViewModel() {

    private val _errors = MutableStateFlow(NewPasswordErrors())
    val errors: StateFlow<NewPasswordErrors> = _errors.asStateFlow()

    private val _events = Channel<NewPasswordEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    /**
     * "SAVE NEW PASSWORD". Validates both fields in one pass, so every problem shows at once.
     *
     * TODO(reset-backend): once both fields are valid, send the new password with the reset token from
     *  step 2 to the auth backend (in viewModelScope, off the main thread). On success, return to the
     *  log in screen with a "Password changed — log in with it now" message; on an expired token, send
     *  the user back to step 1. For now nothing is saved and the screen says so.
     */
    fun onSave(password: String, confirmation: String) {
        val errors = NewPasswordErrors(
            password = AuthValidation.validateNewPassword(password),
            confirmation = AuthValidation.validateConfirmation(password, confirmation)
        )
        _errors.value = errors
        if (errors.password != null || errors.confirmation != null) return

        _events.trySend(NewPasswordEvent.Message(R.string.reset_password_unavailable))
    }

    /** Clears the password field's error as the user edits it. */
    fun clearPasswordError() = _errors.update { it.copy(password = null) }

    /** Clears the confirmation field's error as the user edits it. */
    fun clearConfirmationError() = _errors.update { it.copy(confirmation = null) }
}
