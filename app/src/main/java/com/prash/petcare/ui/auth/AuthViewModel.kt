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
import com.prash.petcare.data.repository.UserRepository
import com.prash.petcare.util.PasswordHasher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The validation errors currently shown on an auth form.
 *
 * Each field holds a string *resource id* rather than a finished message. The ViewModel must not
 * build user-facing text itself: resolving a resource needs a Context, and keeping Context out of
 * the ViewModel is what lets it survive screen rotation without leaking the old Activity. The
 * fragment turns these ids into text at display time, which also means the right translation is
 * picked up automatically.
 *
 * A null field means "no error on this input".
 *
 * @property isSubmitting True while a database operation is in flight, so the UI can disable the
 *   submit button and stop a double tap creating two accounts.
 */
data class AuthFormState(
    // @param: states explicitly that the annotation applies to the constructor parameter. Kotlin
    // currently does that by default but is changing the default to cover the backing field too,
    // and being explicit keeps the meaning fixed across compiler versions.
    @param:StringRes val fullNameError: Int? = null,
    @param:StringRes val emailError: Int? = null,
    @param:StringRes val passwordError: Int? = null,
    @param:StringRes val confirmPasswordError: Int? = null,
    val isSubmitting: Boolean = false
)

/**
 * A one-off occurrence the fragment should react to exactly once.
 *
 * These are kept separate from [AuthFormState] on purpose. State is *current*: it should be
 * re-applied after a rotation. An event is a *moment*: navigating to the home screen, or showing
 * a snackbar. If navigation were modelled as state, rotating the device would replay it and
 * navigate a second time.
 */
sealed interface AuthEvent {

    /** Sign-in or sign-up succeeded; [userId] identifies the now-logged-in account. */
    data class Authenticated(val userId: Long) : AuthEvent

    /** Something failed in a way that is not tied to one input field. */
    data class Error(@param:StringRes val messageRes: Int) : AuthEvent
}

/**
 * Handles registration and login for [RegisterFragment] and [LoginFragment].
 *
 * Sits in the middle of the MVVM chain: the fragments send it user input and observe its output,
 * and it reaches the database only through [UserRepository]. It holds no views and no Context, so
 * it survives configuration changes — a rotation mid-login does not restart the request or lose
 * the typed-in errors.
 *
 * @property userRepository Injected via the constructor so a test can supply a fake instead of a
 *   real database.
 */
class AuthViewModel(private val userRepository: UserRepository) : ViewModel() {

    // Private mutable state, public read-only view. Only the ViewModel may change the state;
    // the fragment can observe it but never write to it.
    private val _formState = MutableStateFlow(AuthFormState())
    val formState: StateFlow<AuthFormState> = _formState.asStateFlow()

    // A Channel rather than a StateFlow, because each event must be delivered once and then be
    // gone. Channel.BUFFERED holds an event safely if it is sent while the screen is briefly not
    // collecting, for example mid-rotation.
    private val _events = Channel<AuthEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    /**
     * Creates a new account, then reports success so the caller can move on to the home screen.
     *
     * All validation happens before anything is written, so a rejected form never leaves a
     * half-created account behind.
     */
    fun register(fullName: String, email: String, password: String, confirmPassword: String) {
        val trimmedName = fullName.trim()
        val trimmedEmail = email.trim()

        // Validate every field in one pass rather than stopping at the first problem, so the user
        // sees all of their mistakes at once instead of fixing them one submit at a time.
        val localErrors = AuthFormState(
            fullNameError = if (trimmedName.isEmpty()) R.string.error_name_required else null,
            emailError = validateEmail(trimmedEmail),
            passwordError = validateNewPassword(password),
            confirmPasswordError = when {
                confirmPassword.isEmpty() -> R.string.error_confirm_password_required
                confirmPassword != password -> R.string.error_password_mismatch
                else -> null
            }
        )

        if (localErrors.hasErrors()) {
            _formState.value = localErrors
            return
        }

        viewModelScope.launch {
            _formState.value = AuthFormState(isSubmitting = true)

            // The unique index on User.email would reject a duplicate anyway, but checking first
            // lets the error appear under the email field instead of surfacing as a crash.
            if (userRepository.isEmailTaken(trimmedEmail)) {
                _formState.value = AuthFormState(emailError = R.string.error_email_taken)
                return@launch
            }

            // PBKDF2 is intentionally slow, so it runs on a background thread. Without this the
            // hashing would block the main thread and visibly freeze the screen.
            val passwordHash = withContext(Dispatchers.Default) { PasswordHasher.hash(password) }

            val newUser = User(
                fullName = trimmedName,
                email = trimmedEmail,
                passwordHash = passwordHash
            )

            // Guard against the race where another insert claims the same email between the check
            // above and this write — the database's unique index is the real enforcement.
            val userId = runCatching { userRepository.addUser(newUser) }.getOrNull()

            if (userId == null) {
                _formState.value = AuthFormState(emailError = R.string.error_email_taken)
                return@launch
            }

            _formState.value = AuthFormState()
            _events.send(AuthEvent.Authenticated(userId))
        }
    }

    /**
     * Signs an existing user in.
     *
     * Note the deliberately vague failure message: see [R.string.error_invalid_credentials].
     */
    fun login(email: String, password: String) {
        val trimmedEmail = email.trim()

        val localErrors = AuthFormState(
            emailError = validateEmail(trimmedEmail),
            // Only emptiness is checked here, not length. Enforcing the current minimum length on
            // sign-in would lock out any older account whose password predates that rule.
            passwordError = if (password.isEmpty()) R.string.error_password_required else null
        )

        if (localErrors.hasErrors()) {
            _formState.value = localErrors
            return
        }

        viewModelScope.launch {
            _formState.value = AuthFormState(isSubmitting = true)

            val user = userRepository.getUserByEmail(trimmedEmail)

            // Verification runs off the main thread for the same reason hashing does.
            val isPasswordCorrect = user != null &&
                withContext(Dispatchers.Default) {
                    PasswordHasher.verify(password, user.passwordHash)
                }

            if (user == null || !isPasswordCorrect) {
                _formState.value = AuthFormState()
                _events.send(AuthEvent.Error(R.string.error_invalid_credentials))
                return@launch
            }

            _formState.value = AuthFormState()
            _events.send(AuthEvent.Authenticated(user.id))
        }
    }

    /**
     * Clears the error shown on a field, called as the user edits it.
     *
     * Leaving a stale "invalid email" message under a field the user is actively correcting is
     * confusing, so the message disappears as soon as they start fixing it.
     */
    fun clearFieldErrors() {
        _formState.value = _formState.value.copy(
            fullNameError = null,
            emailError = null,
            passwordError = null,
            confirmPasswordError = null
        )
    }

    /** Returns the error for [email], or null when it is acceptable. */
    @StringRes
    private fun validateEmail(email: String): Int? = when {
        email.isEmpty() -> R.string.error_email_required
        !EMAIL_PATTERN.matches(email) -> R.string.error_email_invalid
        else -> null
    }

    /** Returns the error for a newly chosen password, or null when it is acceptable. */
    @StringRes
    private fun validateNewPassword(password: String): Int? = when {
        password.isEmpty() -> R.string.error_password_required
        password.length < MIN_PASSWORD_LENGTH -> R.string.error_password_too_short
        else -> null
    }

    /** True when any field carries an error. */
    private fun AuthFormState.hasErrors(): Boolean =
        fullNameError != null ||
            emailError != null ||
            passwordError != null ||
            confirmPasswordError != null

    companion object {

        /** Shortest password the app accepts. Also shown as helper text on the sign-up form. */
        const val MIN_PASSWORD_LENGTH = 8

        /**
         * A pragmatic email check: some text, an `@`, a dotted domain, no spaces.
         *
         * `android.util.Patterns.EMAIL_ADDRESS` would be the obvious alternative, but it is part
         * of the Android framework and returns null in plain JVM unit tests, which would make
         * this ViewModel's validation untestable without an emulator. A fully RFC-compliant
         * pattern is not the goal either — the only way to truly confirm an address is to send
         * mail to it, so this just catches obvious typos before hitting the database.
         */
        private val EMAIL_PATTERN = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]{2,}$")

        /**
         * Builds the ViewModel with its repository.
         *
         * A factory is needed because [AuthViewModel] takes a constructor argument, and the
         * default ViewModelProvider can only create ViewModels with no-argument constructors.
         * The application instance is retrieved through APPLICATION_KEY rather than a static
         * field, so nothing holds a global reference that a test cannot replace.
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
