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
 * How loudly the inline warning under the password field is speaking.
 *
 * Two looks rather than one, because the last attempt before a lock is a different message from the
 * three before it: the earlier ones are information, the last one is a consequence about to happen.
 * The fragment maps each to the yellow or the red card in the design.
 */
enum class LoginAlertStyle { WARNING, LAST_CHANCE }

/**
 * The warning card shown between the password helper line and "FORGOT PASSWORD?" after a wrong
 * password.
 *
 * @property style Yellow for attempts 1–3, red for the last attempt before the lock.
 * @property attemptsRemaining How many tries are left before sign-in locks — the "4" in "4 of 5
 *   attempts left".
 */
data class LoginAlert(
    val style: LoginAlertStyle,
    val attemptsRemaining: Int
)

/**
 * The locked screen's state, shown in place of the whole log in form.
 *
 * [minutesRemaining] is a *derived* figure rather than something the store holds: it is recomputed
 * once a second by [AuthViewModel]'s countdown, which is what makes the ring and the "TRY AGAIN IN
 * 14 MIN" button tick down while the screen is open.
 *
 * @property failedAttempts How many wrong passwords caused this lock — "5 failed attempts".
 * @property unlockAt When sign-in reopens, shown as a clock time ("Sign-in reopens at 5:53 PM").
 * @property lastAttemptAt When the most recent wrong password was entered.
 * @property minutesRemaining Whole minutes left, rounded *up* so the last part-minute still reads as
 *   "1 min" rather than "0 min" — a countdown that reaches zero before the button works would look
 *   broken.
 * @property lockDurationMinutes The full length of the lock, which the countdown ring draws as its
 *   100% — so the ring empties over the whole fifteen minutes rather than jumping.
 */
data class LoginLock(
    val failedAttempts: Int,
    val unlockAt: Long,
    val lastAttemptAt: Long,
    val minutesRemaining: Int,
    val lockDurationMinutes: Int
)

/**
 * Everything the log in / register screen needs to draw itself.
 *
 * Errors hold string *resource ids*, not finished text. Resolving a resource needs a Context, and
 * keeping Context out of the ViewModel is what lets it outlive a rotation without leaking the old
 * Activity; the fragment turns the ids into text when it draws.
 *
 * Most typed text is deliberately **not** here. It lives in the EditTexts, which already keep it
 * across rotation and across switching modes; copying every keystroke into the ViewModel would add a
 * second source of truth for no gain. The fragment hands the text over only when the user submits.
 * The one exception is the log in identifier, in [identifier] — see [AuthViewModel.onIdentifierChanged]
 * for why the failed-attempt count forces that one field to be watched.
 *
 * @property mode Which form is showing.
 * @property errors The message for each field currently in error; a field absent from the map is fine.
 * @property isSubmitting True while a sign-in or sign-up is being checked, so the buttons can be
 *   disabled and a double tap cannot create two accounts.
 * @property identifier The address currently typed in the log in field, which decides whose failed
 *   attempts [alert] and [lock] describe.
 * @property passwordRequirements Which password rules the register form's password currently meets,
 *   for the live checklist under the field. The second exception to the paragraph above, and for a
 *   similar reason: the checklist has to answer every keystroke, and the rule it answers with must be
 *   the same rule the submit enforces — so the password is measured in one place, here, rather than
 *   measured again in the fragment where the two could drift apart.
 * @property alert The wrong-password warning, or null when there is nothing to warn about.
 * @property lock The lock covering the form, or null when sign-in is open. Never both this and
 *   [alert]: a locked account has run out of attempts to count down.
 */
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

    /** A message that is not tied to one field — a wrong password, or a cancelled Google sign-in. */
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
 * Log in and register share this one ViewModel because they share one screen: which form is showing
 * is part of this screen's state, and so is the "busy" flag that must block both forms at once.
 *
 * It also owns the **failed-attempt lockout**: it counts wrong passwords per address through
 * [LoginAttemptStore], turns that count into the warning card or the locked screen, and runs the
 * countdown that clears the lock when its fifteen minutes are up. See [LoginAttemptStore] for the
 * important caveat that this is a local speed bump rather than account security.
 *
 * @property userRepository Passed in through the constructor so a test can substitute a fake.
 * @property loginAttemptStore Counts wrong passwords per address and decides when this device locks
 *   sign-in. Injected for the same reason, and reached only through this property — the ViewModel
 *   never touches SharedPreferences itself.
 * @property twoFactorRepository Asked one question only: does this account have an authenticator set
 *   up? That is what decides whether a correct password finishes the sign-in or hands over to the
 *   two-factor screen — see [requiresTwoFactor].
 */
class AuthViewModel(
    private val userRepository: UserRepository,
    private val loginAttemptStore: LoginAttemptStore,
    private val twoFactorRepository: TwoFactorRepository
) : ViewModel() {

    // Private mutable state, public read-only view: only the ViewModel changes the state.
    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    // A Channel rather than a StateFlow because each event must be delivered once and then be gone.
    // BUFFERED holds an event safely if it is sent while the screen is briefly not collecting.
    private val _events = Channel<AuthEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    /**
     * The coroutine re-reading the lock once a second while one is in force, or null when sign-in is
     * open. Held so it can be cancelled: a countdown left running for an address the user has
     * stopped typing would keep overwriting the state with the wrong account's lock.
     */
    private var countdown: Job? = null

    /**
     * Restores a lock left behind by an earlier run of the app, before the screen first draws.
     *
     * **Why this is needed even though the lock was already on disk.** Every other read here is
     * keyed by the address in the log in field, which is empty on a cold start — so until this
     * existed, reopening the app showed an ordinary form, and the lock only reappeared if the user
     * happened to retype the same address. The lock was in force the whole time, but it did not look
     * like it, which is indistinguishable from a lock that closing the app had cleared.
     *
     * [LoginAttemptStore.lockedEmail] asks the opposite question — "is anything locked?" — and the
     * remaining time comes from the stored expiry measured against the clock now (see [lockFrom]),
     * never from a timer that starts again at fifteen minutes each launch. So the wait continues
     * across a force stop, a reboot, or the process being killed in the background.
     *
     * The address is put back into [AuthUiState.identifier] as well, because the countdown and every
     * later read work from it; the fragment writes it into the empty field so the two agree when the
     * form returns.
     */
    init {
        val lockedEmail = loginAttemptStore.lockedEmail()
        if (lockedEmail != null) {
            _uiState.update { it.copy(identifier = lockedEmail) }
            startCountdown(loginAttemptStore.recordFor(lockedEmail))
        }
    }

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

    /**
     * The address in the log in field changed — including the first time the screen is drawn, so the
     * state always describes whatever is actually in the box.
     *
     * **Why this one field is watched when no other is.** Failed attempts are counted *per account*,
     * so "3 of 5 attempts left" and the lock itself are facts about the address currently typed, not
     * about the screen. Without following the field, someone locked out of one address could clear
     * the box, type another, and still be looking at the first one's lock; and a lock would only
     * ever appear after a failed submit, never when returning to a locked address with the app
     * freshly opened.
     *
     * It is also why the *stored* count, rather than a count kept in memory, drives the card: that
     * is what survives the app being closed, and it means a single code path draws the warning
     * whether it was earned a second ago or yesterday.
     */
    fun onIdentifierChanged(identifier: String) {
        if (_uiState.value.identifier == identifier) return
        _uiState.update { it.copy(identifier = identifier) }
        refreshAttemptState()
    }

    /**
     * Re-reads the failed-attempt record for the address being typed and rebuilds [AuthUiState.alert]
     * and [AuthUiState.lock] from it.
     *
     * One function for both, because they are two views of the same record and must never disagree:
     * a locked account shows the locked screen and *no* warning card, and an account with attempts
     * left shows the card and no lock.
     *
     * A blank field, or one holding something that is not an email address, simply has no record —
     * there is no account to have failed at yet.
     */
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

    /**
     * The warning card for [failedAttempts] consecutive failures, or null when there is nothing to
     * say (none yet, or the account is locked and the locked screen is saying it instead).
     *
     * The last attempt before the lock gets the red treatment; everything before it the yellow one.
     */
    private fun alertFor(failedAttempts: Int): LoginAlert? {
        if (failedAttempts <= 0 || failedAttempts >= LoginAttemptStore.MAX_ATTEMPTS) return null
        val remaining = LoginAttemptStore.MAX_ATTEMPTS - failedAttempts
        return LoginAlert(
            style = if (remaining == 1) LoginAlertStyle.LAST_CHANCE else LoginAlertStyle.WARNING,
            attemptsRemaining = remaining
        )
    }

    /**
     * Shows the locked screen for [record] and keeps its countdown running until the lock expires.
     *
     * **Why a loop rather than a single delay to the unlock time.** The screen shows live minutes
     * remaining, so something has to redraw while it is open; ticking once a second also means the
     * figure is right after the phone has been asleep, where a single long `delay` would come back
     * late. Each tick re-reads the store rather than counting down a local number, so the one place
     * that decides whether a lock is still in force is [LoginAttemptStore.recordFor] — and the
     * moment it reports the lock gone, this clears it and the form comes back by itself.
     *
     * Any previous countdown is cancelled first, so switching between two locked addresses cannot
     * leave two loops writing over each other.
     */
    private fun startCountdown(record: LoginAttemptRecord) {
        stopCountdown()
        countdown = viewModelScope.launch {
            var current = record
            while (current.isLocked) {
                _uiState.update {
                    it.copy(alert = null, errors = emptyMap(), lock = lockFrom(current))
                }
                delay(COUNTDOWN_TICK_MILLIS)
                // Re-read rather than subtract: the store expires the lock itself, and this also
                // picks up a lock cleared elsewhere (a password reset) without any signal.
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

    /**
     * Turns a stored record into what the locked screen draws, working out the minutes left now.
     *
     * Rounded up (`+ 59_999`), so any part of a minute still counts as a whole one — see
     * [LoginLock.minutesRemaining]. Never below 1 while the lock stands, because a screen saying
     * "try again in 0 min" beside a disabled button contradicts itself.
     */
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

    /**
     * The register form's password changed; re-measure it for the checklist under the field.
     *
     * **Why the password is watched when most fields are not.** The checklist exists precisely so
     * the user learns the rules while inventing the password rather than after pressing the button,
     * which means it has to redraw on every keystroke. Measuring here rather than in the fragment is
     * what guarantees the ticks on screen and the rules applied at submit are the same function's
     * answer — a second copy in the View layer would eventually disagree with this one, and a
     * checklist showing six green ticks over a form that then refuses the password is worse than no
     * checklist at all.
     *
     * The password itself is deliberately *not* kept in state — only the six booleans derived from
     * it. A credential held in a ViewModel would outlive the screen and be written into the saved
     * state bundle on process death; the measurements say nothing about what the password is.
     */
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

    /**
     * Signs an existing user in with either an email address or a phone number, and a password.
     *
     * **How signing in by number works.** Firebase Authentication signs in by email and password; a
     * phone number is not a credential it has ever heard of. So a number typed here is looked up
     * against the numbers on the profiles this device holds, and the *email address* behind it is
     * what is then signed in with — see [UserRepository.findEmailForPhone], which also sets out the
     * one limit worth knowing: a brand-new handset has no profiles to look the number up in, so the
     * first sign-in there needs the email address.
     *
     * **An unregistered number and a wrong password are deliberately indistinguishable.** A number
     * that matches no account produces the same generic failure as a wrong password *and is counted
     * as a failed attempt in the same way*, which is the part that actually matters: a message that
     * differed, or a lockout that did not tick, would turn this form into a way of discovering which
     * numbers have accounts, one guess at a time.
     *
     * The failure message is vague for the same reason — "Incorrect email or password". Only problems
     * that say nothing about the account, such as no network, get their own message.
     *
     * The first login on a device needs a connection, since Firebase has to check the password.
     */
    fun login(identifier: String, password: String) {
        if (_uiState.value.isSubmitting) return
        val trimmed = identifier.trim()

        // A locked address cannot be tried at all — the form is not even on screen while the locked
        // panel is showing, but the keyboard's "done" key can still reach this.
        if (_uiState.value.lock != null) return

        val errors = buildMap {
            identifierError(trimmed)?.let { put(AuthField.IDENTIFIER, it) }
            // Only emptiness is checked, not the password rules: enforcing today's rules on sign-in
            // would lock out any account whose password predates them.
            if (password.isEmpty()) put(AuthField.LOGIN_PASSWORD, R.string.error_password_required)
        }
        if (errors.isNotEmpty()) {
            _uiState.update { it.copy(errors = errors) }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true, errors = emptyMap()) }

            // A number becomes the address on its account; an address is already one. A number that
            // belongs to no account here resolves to null, and is then failed exactly as a wrong
            // password is — see the note above on why the two must look the same.
            val email = resolveSignInEmail(trimmed)

            val result = if (email == null) {
                AccountResult.Failed(AuthFailure.INVALID_CREDENTIALS)
            } else {
                // Firebase checks the password. The repository then finds (or, on a first login on
                // this device, creates) the local profile for the account.
                userRepository.signIn(email = email, password = password)
            }

            _uiState.update { it.copy(isSubmitting = false) }

            when (result) {
                is AccountResult.Failed -> {
                    // A rejected password is reported by the warning card (or the locked screen),
                    // which says the same thing and stays on screen while the next attempt is typed.
                    // A Snackbar as well would tell the user twice — so it is kept for the failures
                    // the card says nothing about, such as being offline.
                    if (!countFailedAttempt(trimmed, result.reason)) {
                        _events.send(AuthEvent.Message(loginFailureMessage(result.reason)))
                    }
                }

                is AccountResult.Success -> {
                    // A successful sign-in ends the run of failures, which is what makes the count
                    // "consecutive": five wrong passwords spread around one correct one never lock.
                    loginAttemptStore.clear(trimmed)
                    refreshAttemptState()
                    continueSignedIn(result.user)
                }
            }
        }
    }

    /**
     * The email address to sign in with for whatever was typed into the log-in field, or null when a
     * phone number matches no account on this device.
     *
     * The identifier has already passed [identifierError], so it is either a well-formed address or a
     * valid phone number; anything containing "@" is the former. Note the *failed attempt is still
     * counted against what the user typed* rather than against the address it resolved to, so the
     * lockout follows the number they are actually entering.
     */
    private suspend fun resolveSignInEmail(identifier: String): String? =
        if (identifier.contains('@')) {
            identifier
        } else {
            userRepository.findEmailForPhone(identifier)
        }

    /**
     * Records a wrong password against [email] and redraws the warning — or the locked screen, if
     * that was the fifth. Returns whether this failure was counted, which is also whether the screen
     * is now showing the user what went wrong.
     *
     * **Only a rejected credential counts.** A sign-in that failed because the phone is offline or
     * because Firebase was busy says nothing about whether the password was right, and locking
     * someone out of their own account for having poor signal would be indefensible. Firebase
     * reports a wrong password and an unknown address identically ([AuthFailure.INVALID_CREDENTIALS]),
     * which is deliberate — see [loginFailureMessage] — and both are worth counting here.
     */
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

    /**
     * Creates a new account and signs it straight in.
     *
     * Every field is validated in one pass before anything is written, so the user sees all of their
     * mistakes at once and a rejected form never leaves a half-created account behind.
     *
     * @param phoneRegion The country chosen beside the phone field ("NP"), and [phone] the local
     *   number typed next to it. The two are checked together — a number is only meaningful against a
     *   country — and stored as one international number, which is what lets the same account later
     *   be found by that number at sign-in. See [PhoneNumbers].
     */
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
            // requireNumber = true: registration is the one form that insists on a number, because
            // it is what makes signing in by phone possible for this account at all.
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

            // Firebase creates the account (and refuses an address already in use), then the
            // repository writes the local profile. The password is handed over and not kept here.
            //
            // The number is stored in international form rather than as typed. The `?: trimmedPhone`
            // can only be reached if the number stopped being valid between the check above and this
            // line, which it cannot — it is there because toE164 is nullable, not because the
            // fallback is expected.
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

    /**
     * "Continue with Google" was tapped. Returns whether the fragment should open Google's account
     * picker.
     *
     * **Two steps.** The picker needs an Activity, so the fragment shows it (through
     * [GoogleSignInLauncher]) and then calls [onGoogleCredentialResult] with what came back. This
     * first step only decides whether to start.
     *
     * **The terms still apply on the register form.** A Google sign-in there creates an account just
     * as the "Create account" button does, so it needs the same consent tick. Without the tick, the
     * picker stays closed and the usual consent error shows. On the log in form there is nothing to
     * agree to, as for a password login.
     *
     * Nothing is marked busy yet. The picker covers the screen while it is open, and marking the form
     * busy here would leave it stuck if the picker was closed by the screen rotating.
     */
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

    /**
     * Google's account picker has closed. With a token, sign in to Firebase and continue into the app.
     * Otherwise say why nothing happened.
     *
     * A first Google sign-in creates both the Firebase account and the local profile, the profile
     * named after the Google account. An address that already has a profile on this device signs
     * in to it rather than creating a second one (see [UserRepository.signInWithGoogle]).
     */
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

    /**
     * The last step of every successful login, by password or by Google: into the app, or first to
     * the two-factor check if the account needs one.
     */
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

    /**
     * The message for a Google sign-in that Firebase refused.
     *
     * [AuthFailure.EMAIL_IN_USE] means an account with a password already uses this address and
     * Firebase would not attach Google to it, so the message points the user to their password.
     */
    @StringRes
    private fun googleFailureMessage(reason: AuthFailure): Int = when (reason) {
        AuthFailure.EMAIL_IN_USE -> R.string.auth_google_account_exists
        AuthFailure.ACCOUNT_UNAVAILABLE -> R.string.auth_google_account_disabled
        AuthFailure.NO_NETWORK, AuthFailure.RATE_LIMITED -> connectionFailureMessage(reason)
        else -> R.string.auth_google_failed
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
     *
     * **A well-formed number always passes here**, whether or not any account has it. That is not an
     * oversight: rejecting an unregistered number at *this* point — before the password has even been
     * looked at — would announce that the number has no account, which is exactly what
     * [login] goes to some trouble to conceal. An unknown number therefore travels the whole way
     * through and fails as a wrong password would.
     *
     * The field has no country selector of its own, unlike the register form: a returning user types
     * the number they know, so [PhoneNumbers.parseLoginNumber] reads a bare local number as a Nepali
     * one and an international one by its own dialling code.
     */
    @StringRes
    private fun identifierError(identifier: String): Int? = when {
        identifier.isEmpty() -> R.string.error_identifier_required
        identifier.contains('@') -> AuthValidation.validateEmail(identifier)
        PhoneNumbers.parseLoginNumber(identifier) != null -> null
        // Digits and separators, but not a real number anywhere: say so as a phone problem rather
        // than complaining about an email address the user plainly was not typing.
        PhoneNumbers.looksLikePhoneAttempt(identifier) -> R.string.error_phone_invalid
        else -> R.string.error_identifier_invalid
    }

    /**
     * Whether this account must pass the two-factor screen before it is signed in.
     *
     * The answer is simply whether the account holds an authenticator secret — see
     * [TwoFactorRepository]. It is asked *after* the password has been accepted and *before*
     * [SessionViewModel.signIn] is called, which is what makes the second factor a real gate rather
     * than a screen shown to somebody who is already in: nothing between here and the code screen
     * records a session.
     *
     * A password sign-in and a Google sign-in both come through here, so "Continue with Google" is
     * gated too. Google's own account security is not this app's second factor, and an account the
     * user chose to protect should not have a way round it.
     */
    private suspend fun requiresTwoFactor(user: User): Boolean =
        twoFactorRepository.isEnabled(user.id)

    companion object {

        /**
         * How often the locked screen's countdown redraws.
         *
         * A second rather than a minute: the ring and the "TRY AGAIN IN 14 MIN" button have to
         * become accurate the instant the lock expires, and a minute-long tick could leave a
         * finished lock on screen for most of a minute.
         */
        private const val COUNTDOWN_TICK_MILLIS = 1_000L

        /**
         * Builds the ViewModel with its repository and the failed-attempt store.
         *
         * A factory is needed because the default ViewModelProvider can only build ViewModels with no
         * constructor arguments. The application is read through APPLICATION_KEY rather than a static
         * field, so nothing holds a global reference a test cannot replace.
         */
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
