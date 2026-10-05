package com.prash.petcare.ui.auth

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.prash.petcare.PetCareApplication
import com.prash.petcare.R
import com.prash.petcare.data.local.LoginAttemptRecord
import com.prash.petcare.data.local.LoginAttemptStore
import com.prash.petcare.data.local.entity.User
import com.prash.petcare.data.remote.AuthFailure
import com.prash.petcare.data.repository.AccountResult
import com.prash.petcare.data.repository.TwoFactorRepository
import com.prash.petcare.data.repository.UserRepository
import com.prash.petcare.ui.common.messageRes
import com.prash.petcare.util.PhoneNumbers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Which of the two forms the log in / register screen is showing. */
enum class AuthMode { LOGIN, REGISTER }

/** Every input on the log in / register screen that can carry a validation error. */
enum class AuthField {
    IDENTIFIER, LOGIN_PASSWORD,
    FULL_NAME, EMAIL, PHONE, REGISTER_PASSWORD, CONFIRM_PASSWORD, CONSENT
}

/** How loudly the inline warning under the password field is speaking. */
enum class LoginAlertStyle { WARNING, LAST_CHANCE }

/** The warning card shown between the password helper line and "FORGOT PASSWORD?" after a wrong password. */
data class LoginAlert(
    val style: LoginAlertStyle,
    val attemptsRemaining: Int
)

/** The locked screen's state, shown in place of the whole log in form. */
data class LoginLock(
    val failedAttempts: Int,
    val unlockAt: Long,
    val lastAttemptAt: Long,
    val minutesRemaining: Int,
    val lockDurationMinutes: Int
)

/** Everything the log in / register screen needs to draw itself. */
data class AuthUiState(
    val mode: AuthMode = AuthMode.LOGIN,
    val errors: Map<AuthField, Int> = emptyMap(),
    val isSubmitting: Boolean = false,
    val identifier: String = "",
    val passwordRequirements: AuthValidation.PasswordRequirements =
        AuthValidation.PasswordRequirements(),
    val alert: LoginAlert? = null,
    val lock: LoginLock? = null
)

/** A one-off occurrence the fragment should react to exactly once. */
sealed interface AuthEvent {

    /** Sign-in or sign-up succeeded; [userId] is the now-signed-in account. */
    data class Authenticated(val userId: Long) : AuthEvent

    /** The password was right, but the account also needs its second factor checked. */
    data class TwoFactorRequired(val userId: Long) : AuthEvent

    /** A message that is not tied to one field — a wrong password, or a cancelled Google sign-in. */
    data class Message(@param:StringRes val messageRes: Int) : AuthEvent
}

/** Handles logging in and registering for [AuthFragment]. */
class AuthViewModel(
    private val userRepository: UserRepository,
    private val loginAttemptStore: LoginAttemptStore,
    private val twoFactorRepository: TwoFactorRepository
) : ViewModel() {

    // Private mutable state, public read-only view: only the ViewModel changes the state.
    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    // A Channel rather than a StateFlow because each event must be delivered once and then be gone.
    private val _events = Channel<AuthEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    /** The coroutine re-reading the lock once a second while one is in force, or null when sign-in is open. */
    private var countdown: Job? = null

    /** Restores a lock left behind by an earlier run of the app, before the screen first draws. */
    init {
        val lockedEmail = loginAttemptStore.lockedEmail()
        if (lockedEmail != null) {
            _uiState.update { it.copy(identifier = lockedEmail) }
            startCountdown(loginAttemptStore.recordFor(lockedEmail))
        }
    }

    /** Switches between the log in and register forms. */
    fun setMode(mode: AuthMode) {
        _uiState.update { state ->
            if (state.mode == mode) state else state.copy(mode = mode, errors = emptyMap())
        }
    }

    /** The address in the log in field changed — including the first time the screen is drawn. */
    fun onIdentifierChanged(identifier: String) {
        if (_uiState.value.identifier == identifier) return
        _uiState.update { it.copy(identifier = identifier) }
        refreshAttemptState()
    }

    /** Re-reads the failed-attempt record for the address being typed and rebuilds [AuthUiState.alert]. */
    private fun refreshAttemptState() {
        val email = _uiState.value.identifier.trim()
        val record = if (email.isEmpty()) LoginAttemptRecord() else loginAttemptStore.recordFor(email)

        if (record.isLocked) {
            startCountdown(record)
            return
        }

        stopCountdown()
        _uiState.update { it.copy(lock = null, alert = alertFor(record.failedAttempts)) }
    }

    /** The warning card for [failedAttempts] consecutive failures, or null when there is nothing to say. */
    private fun alertFor(failedAttempts: Int): LoginAlert? {
        if (failedAttempts <= 0 || failedAttempts >= LoginAttemptStore.MAX_ATTEMPTS) return null
        val remaining = LoginAttemptStore.MAX_ATTEMPTS - failedAttempts
        return LoginAlert(
            style = if (remaining == 1) LoginAlertStyle.LAST_CHANCE else LoginAlertStyle.WARNING,
            attemptsRemaining = remaining
        )
    }

    /** Shows the locked screen for [record] and keeps its countdown running until the lock expires. */
    private fun startCountdown(record: LoginAttemptRecord) {
        stopCountdown()
        countdown = viewModelScope.launch {
            var current = record
            while (current.isLocked) {
                _uiState.update {
                    it.copy(alert = null, errors = emptyMap(), lock = lockFrom(current))
                }
                delay(COUNTDOWN_TICK_MILLIS)
                // Re-read rather than subtract: the store expires the lock itself.
                current = loginAttemptStore.recordFor(_uiState.value.identifier.trim())
            }
            // The wait is over. The form returns, with a clean slate rather than the old warning.
            _uiState.update { it.copy(lock = null, alert = null) }
        }
    }

    /** Stops the countdown, if one is running. */
    private fun stopCountdown() {
        countdown?.cancel()
        countdown = null
    }

    /** Turns a stored record into what the locked screen draws, working out the minutes left now. */
    private fun lockFrom(record: LoginAttemptRecord): LoginLock {
        val millisLeft = (record.lockedUntil - System.currentTimeMillis()).coerceAtLeast(0L)
        return LoginLock(
            failedAttempts = record.failedAttempts,
            unlockAt = record.lockedUntil,
            lastAttemptAt = record.lastAttemptAt,
            minutesRemaining = ((millisLeft + 59_999L) / 60_000L).toInt().coerceAtLeast(1),
            lockDurationMinutes = (LoginAttemptStore.LOCK_DURATION_MILLIS / 60_000L).toInt()
        )
    }

    /** The register form's password changed; re-measure it for the checklist under the field. */
    fun onPasswordChanged(password: String) {
        val requirements = AuthValidation.requirementsFor(password)
        _uiState.update { state ->
            if (state.passwordRequirements == requirements) {
                state
            } else {
                state.copy(passwordRequirements = requirements)
            }
        }
    }

    /** Removes the error from one field, called as soon as the user starts correcting it. */
    fun clearError(field: AuthField) {
        _uiState.update { state ->
            if (field in state.errors) state.copy(errors = state.errors - field) else state
        }
    }

    /** Signs an existing user in with either an email address or a phone number, and a password. */
    fun login(identifier: String, password: String) {
        if (_uiState.value.isSubmitting) return
        val trimmed = identifier.trim()

        // A locked address cannot be tried at all — the form is not even on screen while the locked panel is showing.
        if (_uiState.value.lock != null) return

        val errors = buildMap {
            identifierError(trimmed)?.let { put(AuthField.IDENTIFIER, it) }
            // Only emptiness is checked, not the password rules.
            if (password.isEmpty()) put(AuthField.LOGIN_PASSWORD, R.string.error_password_required)
        }
        if (errors.isNotEmpty()) {
            _uiState.update { it.copy(errors = errors) }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true, errors = emptyMap()) }

            // A number becomes the address on its account; an address is already one.
            val email = resolveSignInEmail(trimmed)

            val result = if (email == null) {
                AccountResult.Failed(AuthFailure.INVALID_CREDENTIALS)
            } else {
                // Firebase checks the password. The repository then finds.
                userRepository.signIn(email = email, password = password)
            }

            _uiState.update { it.copy(isSubmitting = false) }

            when (result) {
                is AccountResult.Failed -> {
                    // A rejected password is reported by the warning card (or the locked screen).
                    if (!countFailedAttempt(trimmed, result.reason)) {
                        _events.send(AuthEvent.Message(loginFailureMessage(result.reason)))
                    }
                }

                is AccountResult.Success -> {
                    // A successful sign-in ends the run of failures, which is what makes the count "consecutive".
                    loginAttemptStore.clear(trimmed)
                    refreshAttemptState()
                    continueSignedIn(result.user)
                }
            }
        }
    }

    /** The email address to sign in with for whatever was typed into the log-in field, or null. */
    private suspend fun resolveSignInEmail(identifier: String): String? =
        if (identifier.contains('@')) {
            identifier
        } else {
            userRepository.findEmailForPhone(identifier)
        }

    /** Records a wrong password against [email] and redraws the warning — or the locked screen. */
    private fun countFailedAttempt(email: String, reason: AuthFailure): Boolean {
        if (reason != AuthFailure.INVALID_CREDENTIALS) return false

        val record = loginAttemptStore.recordFailure(email)
        if (record.isLocked) {
            startCountdown(record)
        } else {
            _uiState.update { it.copy(alert = alertFor(record.failedAttempts)) }
        }
        return true
    }

    /** Stops the countdown when the screen is finished with, so no coroutine outlives the ViewModel. */
    override fun onCleared() {
        stopCountdown()
    }

    /** Creates a new account and signs it straight in. */
    fun register(
        fullName: String,
        email: String,
        phoneRegion: String,
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
            // requireNumber = true: registration is the one form that insists on a number.
            PhoneNumbers.check(phoneRegion, trimmedPhone)
                .messageRes(requireNumber = true)
                ?.let { put(AuthField.PHONE, it) }
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

            // Firebase creates the account (and refuses an address already in use).
            val result = userRepository.register(
                fullName = trimmedName,
                email = trimmedEmail,
                phone = PhoneNumbers.toE164(phoneRegion, trimmedPhone) ?: trimmedPhone,
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

    /** "Continue with Google" was tapped. Returns whether the fragment should open Google's account picker. */
    fun onGoogleSignInClicked(hasAcceptedTerms: Boolean): Boolean {
        val state = _uiState.value
        if (state.isSubmitting) return false

        if (state.mode == AuthMode.REGISTER && !hasAcceptedTerms) {
            _uiState.update {
                it.copy(errors = mapOf(AuthField.CONSENT to R.string.error_consent_required))
            }
            return false
        }
        return true
    }

    /** Google's account picker has closed. With a token, sign in to Firebase and continue into the app. */
    fun onGoogleCredentialResult(result: GoogleCredentialResult) {
        if (result !is GoogleCredentialResult.Token) {
            _events.trySend(AuthEvent.Message(pickerProblemMessage(result)))
            return
        }
        if (_uiState.value.isSubmitting) return

        viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true, errors = emptyMap()) }
            val signIn = userRepository.signInWithGoogle(result.idToken)
            _uiState.update { it.copy(isSubmitting = false) }

            when (signIn) {
                is AccountResult.Success -> continueSignedIn(signIn.user)
                is AccountResult.Failed ->
                    _events.send(AuthEvent.Message(googleFailureMessage(signIn.reason)))
            }
        }
    }

    /** The last step of every successful login, by password or by Google: into the app. */
    private suspend fun continueSignedIn(user: User) {
        if (requiresTwoFactor(user)) {
            _events.send(AuthEvent.TwoFactorRequired(user.id))
        } else {
            _events.send(AuthEvent.Authenticated(user.id))
        }
    }

    /** Why Google's account picker produced no token, as the message to show. */
    @StringRes
    private fun pickerProblemMessage(result: GoogleCredentialResult): Int = when (result) {
        GoogleCredentialResult.Cancelled -> R.string.auth_google_cancelled
        GoogleCredentialResult.NoGoogleAccount -> R.string.auth_google_no_account
        GoogleCredentialResult.NoNetwork -> R.string.auth_error_network
        GoogleCredentialResult.Unavailable -> R.string.auth_google_unavailable
        GoogleCredentialResult.Failed, is GoogleCredentialResult.Token -> R.string.auth_google_failed
    }

    /** The message for a Google sign-in that Firebase refused. */
    @StringRes
    private fun googleFailureMessage(reason: AuthFailure): Int = when (reason) {
        AuthFailure.EMAIL_IN_USE -> R.string.auth_google_account_exists
        AuthFailure.ACCOUNT_UNAVAILABLE -> R.string.auth_google_account_disabled
        AuthFailure.NO_NETWORK, AuthFailure.RATE_LIMITED -> connectionFailureMessage(reason)
        else -> R.string.auth_google_failed
    }

    /** Shows why Firebase refused a sign-up, on the field it concerns where there. */
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

    /** The message for a failed login. */
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

    /** Returns the error for the log-in identifier, or null when the account can be looked up with it. */
    @StringRes
    private fun identifierError(identifier: String): Int? = when {
        identifier.isEmpty() -> R.string.error_identifier_required
        identifier.contains('@') -> AuthValidation.validateEmail(identifier)
        PhoneNumbers.parseLoginNumber(identifier) != null -> null
        // Digits and separators, but not a real number anywhere: say so as a phone problem.
        PhoneNumbers.looksLikePhoneAttempt(identifier) -> R.string.error_phone_invalid
        else -> R.string.error_identifier_invalid
    }

    /** Whether this account must pass the two-factor screen before it is signed in. */
    private suspend fun requiresTwoFactor(user: User): Boolean =
        twoFactorRepository.isEnabled(user.id)

    companion object {

        /** How often the locked screen's countdown redraws. */
        private const val COUNTDOWN_TICK_MILLIS = 1_000L

        /** Builds the ViewModel with its repository and the failed-attempt store. */
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application =
                    this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as PetCareApplication
                AuthViewModel(
                    userRepository = application.userRepository,
                    loginAttemptStore = application.loginAttemptStore,
                    twoFactorRepository = application.twoFactorRepository
                )
            }
        }
    }
}
