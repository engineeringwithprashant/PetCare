package com.prash.petcare.ui.auth

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.prash.petcare.PetCareApplication
import com.prash.petcare.R
import com.prash.petcare.data.local.entity.User
import com.prash.petcare.data.remote.AuthFailure
import com.prash.petcare.data.repository.AccountResult
import com.prash.petcare.data.repository.UserRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Which of the two forms the log in / register screen is showing. */
enum class AuthMode { LOGIN, REGISTER }

/**
 * Every input on the log in / register screen that can carry a validation error.
 *
 * Errors are keyed by field rather than held in one property per field, so clearing the error on the
 * field being edited is one call ([AuthViewModel.clearError]) whichever field it is, and a new field
 * later is one new entry here rather than a new property threaded through every copy() call.
 */
enum class AuthField {
    IDENTIFIER, LOGIN_PASSWORD,
    FULL_NAME, EMAIL, PHONE, REGISTER_PASSWORD, CONFIRM_PASSWORD, CONSENT
}

/**
 * Everything the log in / register screen needs to draw itself.
 *
 * Errors hold string *resource ids*, not finished text. Resolving a resource needs a Context, and
 * keeping Context out of the ViewModel is what lets it outlive a rotation without leaking the old
 * Activity; the fragment turns the ids into text when it draws.
 *
 * The typed text itself is deliberately **not** here. It lives in the EditTexts, which already keep it
 * across rotation and across switching modes; copying every keystroke into the ViewModel would add a
 * second source of truth for no gain. The fragment hands the text over only when the user submits.
 *
 * @property mode Which form is showing.
 * @property errors The message for each field currently in error; a field absent from the map is fine.
 * @property isSubmitting True while a sign-in or sign-up is being checked, so the buttons can be
 *   disabled and a double tap cannot create two accounts.
 */
data class AuthUiState(
    val mode: AuthMode = AuthMode.LOGIN,
    val errors: Map<AuthField, Int> = emptyMap(),
    val isSubmitting: Boolean = false
)

/**
 * A one-off occurrence the fragment should react to exactly once.
 *
 * Kept apart from [AuthUiState] on purpose. State is *current* and is re-applied after a rotation; an
 * event is a *moment*. If "navigate to home" were state, rotating the device would navigate again.
 */
sealed interface AuthEvent {

    /** Sign-in or sign-up succeeded; [userId] is the now-signed-in account. */
    data class Authenticated(val userId: Long) : AuthEvent

    /** The password was right, but the account also needs its second factor checked. */
    data class TwoFactorRequired(val userId: Long) : AuthEvent

    /** A message that is not tied to one field — a wrong password, or a feature not connected yet. */
    data class Message(@param:StringRes val messageRes: Int) : AuthEvent
}

/**
 * Handles logging in and registering for [AuthFragment].
 *
 * The middle of the MVVM chain: the fragment sends it user input and observes its state, and it
 * reaches accounts only through [UserRepository], which checks passwords with Firebase Authentication
 * and keeps the local profile. This class never sees a Firebase type. It checks the form, hands the
 * values over, and turns the result into what the screen shows. It holds no views and no Context, so
 * it survives configuration changes — a rotation mid-sign-in neither restarts the check nor loses the
 * errors.
 *
 * Log in and register share this one ViewModel because they share one screen: the toggle between them
 * is part of this screen's state, and so is the "busy" flag that must block both forms at once.
 *
 * @property userRepository Passed in through the constructor so a test can substitute a fake.
 */
class AuthViewModel(private val userRepository: UserRepository) : ViewModel() {

    // Private mutable state, public read-only view: only the ViewModel changes the state.
    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    // A Channel rather than a StateFlow because each event must be delivered once and then be gone.
    // BUFFERED holds an event safely if it is sent while the screen is briefly not collecting.
    private val _events = Channel<AuthEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    /**
     * Switches between the log in and register forms.
     *
     * Errors are cleared on the way: they described the last submit of the *other* form, and a red
     * message under a field the user has not touched yet would be confusing.
     */
    fun setMode(mode: AuthMode) {
        _uiState.update { state ->
            if (state.mode == mode) state else state.copy(mode = mode, errors = emptyMap())
        }
    }

    /** Removes the error from one field, called as soon as the user starts correcting it. */
    fun clearError(field: AuthField) {
        _uiState.update { state ->
            if (field in state.errors) state.copy(errors = state.errors - field) else state
        }
    }

    /**
     * Signs an existing user in with an email (or, once connected, a phone number) and a password.
     *
     * The failure message is deliberately vague — "Incorrect email or password" — so the form cannot be
     * used to discover which addresses have accounts. Only problems that say nothing about the account,
     * such as no network, get their own message.
     *
     * The first login on a device needs a connection, since Firebase has to check the password.
     */
    fun login(identifier: String, password: String) {
        if (_uiState.value.isSubmitting) return
        val trimmed = identifier.trim()

        val errors = buildMap {
            identifierError(trimmed)?.let { put(AuthField.IDENTIFIER, it) }
            // Only emptiness is checked, not length: enforcing today's minimum on sign-in would lock
            // out any account whose password predates that rule.
            if (password.isEmpty()) put(AuthField.LOGIN_PASSWORD, R.string.error_password_required)
        }
        if (errors.isNotEmpty()) {
            _uiState.update { it.copy(errors = errors) }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true, errors = emptyMap()) }

            // Firebase checks the password. The repository then finds (or, on a first login on this
            // device, creates) the local profile for the account.
            val result = userRepository.signIn(email = trimmed, password = password)

            _uiState.update { it.copy(isSubmitting = false) }

            when (result) {
                is AccountResult.Failed ->
                    _events.send(AuthEvent.Message(loginFailureMessage(result.reason)))

                is AccountResult.Success -> {
                    val user = result.user
                    if (requiresTwoFactor(user)) {
                        _events.send(AuthEvent.TwoFactorRequired(user.id))
                    } else {
                        _events.send(AuthEvent.Authenticated(user.id))
                    }
                }
            }
        }
    }

    /**
     * Creates a new account and signs it straight in.
     *
     * Every field is validated in one pass before anything is written, so the user sees all of their
     * mistakes at once and a rejected form never leaves a half-created account behind.
     */
    fun register(
        fullName: String,
        email: String,
        phone: String,
        password: String,
        confirmPassword: String,
        hasAcceptedTerms: Boolean
    ) {
        if (_uiState.value.isSubmitting) return
        val trimmedName = fullName.trim()
        val trimmedEmail = email.trim()
        val trimmedPhone = phone.trim()

        val errors = buildMap {
            if (trimmedName.isEmpty()) put(AuthField.FULL_NAME, R.string.error_name_required)
            AuthValidation.validateEmail(trimmedEmail)?.let { put(AuthField.EMAIL, it) }
            AuthValidation.validatePhone(trimmedPhone)?.let { put(AuthField.PHONE, it) }
            AuthValidation.validateNewPassword(password)?.let { put(AuthField.REGISTER_PASSWORD, it) }
            AuthValidation.validateConfirmation(password, confirmPassword)
                ?.let { put(AuthField.CONFIRM_PASSWORD, it) }
            if (!hasAcceptedTerms) put(AuthField.CONSENT, R.string.error_consent_required)
        }
        if (errors.isNotEmpty()) {
            _uiState.update { it.copy(errors = errors) }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true, errors = emptyMap()) }

            // Firebase creates the account (and refuses an address already in use), then the
            // repository writes the local profile. The password is handed over and not kept here.
            val result = userRepository.register(
                fullName = trimmedName,
                email = trimmedEmail,
                phone = trimmedPhone,
                password = password
            )

            when (result) {
                is AccountResult.Success -> {
                    _uiState.update { it.copy(isSubmitting = false) }
                    _events.send(AuthEvent.Authenticated(result.user.id))
                }

                is AccountResult.Failed -> showRegistrationFailure(result.reason)
            }
        }
    }

    /**
     * "Continue with Google".
     *
     * TODO(google-sign-in): not connected. Wire this to Google Identity Services (Credential Manager's
     *  GetGoogleIdOption), sign in to Firebase with the returned ID token (GoogleAuthProvider), find or
     *  create the matching local profile through UserRepository, then send
     *  AuthEvent.Authenticated(userId).
     *  Until then it only says the option is unavailable.
     */
    fun onGoogleSignIn() {
        _events.trySend(AuthEvent.Message(R.string.auth_google_unavailable))
    }

    /**
     * Shows why Firebase refused a sign-up, on the field it concerns where there is one:
     * an address already in use or malformed goes under the email field, and a password the project's
     * policy rejects goes under the password field. Everything else is a general message.
     */
    private suspend fun showRegistrationFailure(reason: AuthFailure) {
        when (reason) {
            AuthFailure.EMAIL_IN_USE -> failRegistration(AuthField.EMAIL, R.string.error_email_taken)
            AuthFailure.INVALID_EMAIL -> failRegistration(AuthField.EMAIL, R.string.error_email_invalid)
            AuthFailure.WEAK_PASSWORD ->
                failRegistration(AuthField.REGISTER_PASSWORD, R.string.error_password_weak)

            else -> {
                _uiState.update { it.copy(isSubmitting = false) }
                _events.send(AuthEvent.Message(connectionFailureMessage(reason)))
            }
        }
    }

    /**
     * The message for a failed login.
     *
     * Wrong password, unknown address, disabled account and a malformed address all get the same
     * vague "Incorrect email or password", so the form reveals nothing about which accounts exist.
     */
    @StringRes
    private fun loginFailureMessage(reason: AuthFailure): Int = when (reason) {
        AuthFailure.INVALID_CREDENTIALS,
        AuthFailure.ACCOUNT_UNAVAILABLE,
        AuthFailure.INVALID_EMAIL -> R.string.error_invalid_credentials

        else -> connectionFailureMessage(reason)
    }

    /** Messages for failures that are about reaching Firebase rather than about the account. */
    @StringRes
    private fun connectionFailureMessage(reason: AuthFailure): Int = when (reason) {
        AuthFailure.NO_NETWORK -> R.string.auth_error_network
        AuthFailure.RATE_LIMITED -> R.string.auth_error_rate_limited
        else -> R.string.auth_error_unknown
    }

    /** Stops the spinner and shows [messageRes] under [field] after a failed sign-up. */
    private fun failRegistration(field: AuthField, @StringRes messageRes: Int) {
        _uiState.update { it.copy(isSubmitting = false, errors = mapOf(field to messageRes)) }
    }

    /**
     * Returns the error for the log-in identifier, or null when the account can be looked up with it.
     *
     * The field accepts an email or a phone number, and which one it is decides the rule: anything
     * containing "@" is treated as an attempted email address, so a typo in one gets the email error
     * rather than a confusing phone error.
     */
    @StringRes
    private fun identifierError(identifier: String): Int? = when {
        identifier.isEmpty() -> R.string.error_identifier_required
        identifier.contains('@') -> AuthValidation.validateEmail(identifier)
        // TODO(phone-login): a well-formed phone number is recognised but cannot sign in yet.
        //  Firebase Authentication signs in by email and password, and signing in by phone number is a
        //  separate Firebase flow (a one-time SMS code). Once that is built, return null here and send
        //  phone numbers to it from login().
        AuthValidation.isPhone(identifier) -> R.string.error_phone_login_unavailable
        else -> R.string.error_identifier_invalid
    }

    /**
     * Whether this account must pass the two-factor screen before it is signed in.
     *
     * TODO(2fa): always false for now — nothing stores whether an account has an authenticator set up
     *  (the profile screen's two-factor switch is deliberately inert, see SettingsStore). When the
     *  backend can say so, return that here; login() already routes such accounts to the two-factor
     *  screen through AuthEvent.TwoFactorRequired, and the navigation for it is in place.
     */
    @Suppress("UNUSED_PARAMETER", "SameReturnValue")
    private fun requiresTwoFactor(user: User): Boolean = false

    companion object {

        /**
         * Builds the ViewModel with its repository.
         *
         * A factory is needed because the default ViewModelProvider can only build ViewModels with no
         * constructor arguments. The application is read through APPLICATION_KEY rather than a static
         * field, so nothing holds a global reference a test cannot replace.
         */
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application =
                    this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as PetCareApplication
                AuthViewModel(application.userRepository)
            }
        }
    }
}
