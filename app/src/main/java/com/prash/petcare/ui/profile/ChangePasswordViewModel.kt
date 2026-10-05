package com.prash.petcare.ui.profile

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.prash.petcare.PetCareApplication
import com.prash.petcare.R
import com.prash.petcare.data.repository.PasswordChangeResult
import com.prash.petcare.data.repository.UserRepository
import com.prash.petcare.ui.auth.AuthValidation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** What the change-password form should do once, after an attempt. */
sealed interface ChangePasswordEvent {

    /** Changed; the screen reports it and goes back. */
    data object Changed : ChangePasswordEvent

    /** The current-password field was left empty. */
    data object CurrentRequired : ChangePasswordEvent

    /** The current password typed does not match the stored one. */
    data object CurrentWrong : ChangePasswordEvent

    /** The new password does not satisfy the rules, and [messageRes] says which rule it broke first. */
    data class NewPasswordRejected(@param:StringRes val messageRes: Int) : ChangePasswordEvent

    /** The new password and its confirmation differ. */
    data object Mismatch : ChangePasswordEvent

    /** The new password is the one already in use, so there is nothing to change. */
    data object SameAsCurrent : ChangePasswordEvent

    /** The account was deleted while the form was open. */
    data object AccountMissing : ChangePasswordEvent

    /** Firebase rejected the new password under the project's password policy. */
    data object WeakPassword : ChangePasswordEvent

    /** The change could not be made for a reason that is not about one field. */
    data class Message(@param:StringRes val messageRes: Int) : ChangePasswordEvent
}

/** Backs the form behind the profile screen's "UPDATE" pill. */
class ChangePasswordViewModel(
    private val userRepository: UserRepository,
    private val userId: Long
) : ViewModel() {

    private val _isWorking = MutableStateFlow(false)

    /** True while the change is in flight, so the screen can disable its button. */
    val isWorking: StateFlow<Boolean> = _isWorking.asStateFlow()

    private val _events = Channel<ChangePasswordEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private val _passwordRequirements =
        MutableStateFlow(AuthValidation.PasswordRequirements())

    /** Which password rules the new-password field currently meets, for the live checklist under it. */
    val passwordRequirements: StateFlow<AuthValidation.PasswordRequirements> =
        _passwordRequirements.asStateFlow()

    /** The new-password field changed; re-measure it for the checklist. */
    fun onNewPasswordChanged(password: String) {
        _passwordRequirements.value = AuthValidation.requirementsFor(password)
    }

    /** Checks the form, then asks the repository to make the change. */
    fun onSubmit(currentPassword: String, newPassword: String, confirmPassword: String) {
        if (currentPassword.isEmpty()) return send(ChangePasswordEvent.CurrentRequired)

        AuthValidation.validateNewPassword(newPassword)?.let {
            return send(ChangePasswordEvent.NewPasswordRejected(it))
        }

        if (newPassword != confirmPassword) return send(ChangePasswordEvent.Mismatch)

        // Guards against a second submission slipping through while the first is still in flight.
        if (_isWorking.value) return
        _isWorking.value = true

        viewModelScope.launch {
            val result = userRepository.changePassword(
                userId = userId,
                currentPassword = currentPassword,
                newPassword = newPassword
            )

            _isWorking.value = false

            _events.send(
                when (result) {
                    PasswordChangeResult.Success -> ChangePasswordEvent.Changed
                    PasswordChangeResult.WrongCurrentPassword -> ChangePasswordEvent.CurrentWrong
                    PasswordChangeResult.SameAsCurrent -> ChangePasswordEvent.SameAsCurrent
                    PasswordChangeResult.AccountMissing -> ChangePasswordEvent.AccountMissing
                    PasswordChangeResult.WeakPassword -> ChangePasswordEvent.WeakPassword
                    PasswordChangeResult.NoNetwork ->
                        ChangePasswordEvent.Message(R.string.auth_error_network)
                    PasswordChangeResult.RateLimited ->
                        ChangePasswordEvent.Message(R.string.auth_error_rate_limited)
                    PasswordChangeResult.SignInAgain ->
                        ChangePasswordEvent.Message(R.string.auth_error_sign_in_again)
                    PasswordChangeResult.Failed ->
                        ChangePasswordEvent.Message(R.string.auth_error_unknown)
                }
            )
        }
    }

    private fun send(event: ChangePasswordEvent) {
        viewModelScope.launch { _events.send(event) }
    }

    companion object {

        /** The longest password the form accepts, applied as a cap on the two new-password fields. */
        const val MAXIMUM_LENGTH = AuthValidation.MAX_PASSWORD_LENGTH

        fun factory(userId: Long): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application =
                    this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as PetCareApplication

                ChangePasswordViewModel(
                    userRepository = application.userRepository,
                    userId = userId
                )
            }
        }
    }
}
