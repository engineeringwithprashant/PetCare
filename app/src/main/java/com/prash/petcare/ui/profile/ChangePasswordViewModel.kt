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

    /**
     * The new password does not satisfy the rules, and [messageRes] says which rule it broke first.
     *
     * This replaces the old "too short" case. Length is now one of six rules rather than the only
     * one, and the message comes straight from [AuthValidation.validateNewPassword] — so the form
     * cannot say something different from what the checklist above the field is showing, and adding a
     * rule later needs no new event here.
     */
    data class NewPasswordRejected(@param:StringRes val messageRes: Int) : ChangePasswordEvent

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

    private val _passwordRequirements =
        MutableStateFlow(AuthValidation.PasswordRequirements())

    /**
     * Which password rules the new-password field currently meets, for the live checklist under it.
     *
     * **Why the ViewModel measures this and not the fragment.** The checklist has to answer every
     * keystroke, and what it shows must be exactly what the submit below enforces. Measuring in one
     * place — with [AuthValidation.requirementsFor], the same function
     * [AuthValidation.validateNewPassword] is built on — is what guarantees that. A copy of the rules
     * in the View layer would eventually disagree, and a checklist showing six green ticks over a
     * form that then refuses the password is worse than no checklist at all.
     *
     * Only the six booleans are held here, never the password itself: a credential kept in a
     * ViewModel outlives the screen and is written into the saved-state bundle on process death,
     * whereas these measurements say nothing about what was typed.
     */
    val passwordRequirements: StateFlow<AuthValidation.PasswordRequirements> =
        _passwordRequirements.asStateFlow()

    /** The new-password field changed; re-measure it for the checklist. */
    fun onNewPasswordChanged(password: String) {
        _passwordRequirements.value = AuthValidation.requirementsFor(password)
    }

    /**
     * Checks the form, then asks the repository to make the change.
     *
     * The three form rules are applied first and in order, so the user is told about the nearest
     * problem rather than the last one — and so Firebase is never contacted for a form that could not
     * have succeeded anyway.
     *
     * The new password is measured by [AuthValidation.validateNewPassword], the very function the
     * register form uses. That matters in a specific way: a stricter rule here would tell people the
     * password they already have is not good enough while leaving it in place and still accepting it
     * at sign-in, and a looser one would let them set a password sign-up would have refused.
     *
     * Passwords are deliberately **not** trimmed. A leading or trailing space is a legitimate part of
     * a password, and silently removing it here would let someone set a password they could then never
     * type — the sign-in screen does not trim either, so the two agree.
     */
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

        /**
         * The longest password the form accepts, applied as a cap on the two new-password fields so
         * an over-long paste is trimmed as it lands rather than refused after submit.
         *
         * **Referenced from the sign-in screens' shared rules (AuthValidation) rather than repeated**,
         * so sign-up and this form can never disagree. The *whole* rule set is shared the same way —
         * see [onSubmit], which calls the same validation function the register form does. This
         * constant is here only because the fragment needs the number itself to build the field's
         * input filter.
         */
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
