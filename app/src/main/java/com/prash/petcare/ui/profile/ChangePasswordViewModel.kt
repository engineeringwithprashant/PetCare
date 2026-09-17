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

/**
 * What the change-password form should do once, after an attempt.
 *
 * Distinct cases rather than a Boolean, because the user has to be told *which* thing went wrong and
 * on which field — "that is not your current password" and "the two new passwords do not match" want
 * the error in different places, and a false could not say where.
 */
sealed interface ChangePasswordEvent {

    /** Changed; the screen reports it and goes back. */
    data object Changed : ChangePasswordEvent

    /** The current-password field was left empty. */
    data object CurrentRequired : ChangePasswordEvent

    /** The current password typed does not match the stored one. */
    data object CurrentWrong : ChangePasswordEvent

    /** The new password is shorter than [ChangePasswordViewModel.MINIMUM_LENGTH]. */
    data object TooShort : ChangePasswordEvent

    /** The new password and its confirmation differ. */
    data object Mismatch : ChangePasswordEvent

    /** The new password is the one already in use, so there is nothing to change. */
    data object SameAsCurrent : ChangePasswordEvent

    /** The account was deleted while the form was open. */
    data object AccountMissing : ChangePasswordEvent

    /** Firebase rejected the new password under the project's password policy. */
    data object WeakPassword : ChangePasswordEvent

    /**
     * The change could not be made for a reason that is not about one field (no network, too many
     * attempts, signed out of Firebase). [messageRes] says which, and nothing was changed.
     */
    data class Message(@param:StringRes val messageRes: Int) : ChangePasswordEvent
}

/**
 * Backs the form behind the profile screen's "UPDATE" pill.
 *
 * Sits in the middle of the MVVM chain, and is unusually thin on purpose: the part that matters —
 * having Firebase Authentication check the current password and replace it — happens in
 * [UserRepository.changePassword], not here. Everything that touches the credential stays in the data
 * layer.
 *
 * What this class does own is the *form's* rules: that a current password was typed at all, that the
 * new one is long enough, and that it was typed the same way twice. None of those needs Firebase, and
 * all of them can be checked before making the network call.
 *
 * @param userId Which account's password to change, read from the session.
 */
class ChangePasswordViewModel(
    private val userRepository: UserRepository,
    private val userId: Long
) : ViewModel() {

    private val _isWorking = MutableStateFlow(false)

    /**
     * True while the change is in flight, so the screen can disable its button.
     *
     * It matters here more than on most forms: the change is a round trip to Firebase that takes a
     * noticeable moment, so without this a user could tap "Change password" three times and queue three
     * changes.
     */
    val isWorking: StateFlow<Boolean> = _isWorking.asStateFlow()

    private val _events = Channel<ChangePasswordEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    /**
     * Checks the form, then asks the repository to make the change.
     *
     * The three form rules are applied first and in order, so the user is told about the nearest
     * problem rather than the last one — and so Firebase is never contacted for a form that could not
     * have succeeded anyway.
     *
     * Passwords are deliberately **not** trimmed. A leading or trailing space is a legitimate part of
     * a password, and silently removing it here would let someone set a password they could then never
     * type — the sign-in screen does not trim either, so the two agree.
     */
    fun onSubmit(currentPassword: String, newPassword: String, confirmPassword: String) {
        when {
            currentPassword.isEmpty() -> return send(ChangePasswordEvent.CurrentRequired)
            newPassword.length < MINIMUM_LENGTH -> return send(ChangePasswordEvent.TooShort)
            newPassword != confirmPassword -> return send(ChangePasswordEvent.Mismatch)
        }

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

        /**
         * The shortest password the form accepts.
         *
         * **Referenced from the sign-in screens' shared rules (AuthValidation) rather than repeated**,
         * so sign-up, password reset and this form can never disagree.
         * That matters in a specific way: a stricter rule here would tell people their existing
         * password is not good enough while leaving it in place and still accepting it at sign-in,
         * and a looser one would let them set a password sign-up would have refused. One constant,
         * raised or lowered in one file, keeps both screens honest.
         */
        const val MINIMUM_LENGTH = AuthValidation.MIN_PASSWORD_LENGTH

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
