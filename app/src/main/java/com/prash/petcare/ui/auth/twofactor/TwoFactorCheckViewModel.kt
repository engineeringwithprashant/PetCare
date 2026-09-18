package com.prash.petcare.ui.auth.twofactor

import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.prash.petcare.R
import com.prash.petcare.data.local.LoginAttemptStore
import com.prash.petcare.data.repository.TwoFactorCheck
import com.prash.petcare.data.repository.TwoFactorRepository
import com.prash.petcare.data.repository.UserRepository
import com.prash.petcare.ui.auth.CodeEntryViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * One-off outcomes of a two-factor code screen, delivered once each (see AuthEvent for why).
 *
 * Note there is no "message" case: everything these screens have to say about a code — wrong, already
 * used, locked out — is shown *under the code boxes* by [CodeEntryViewModel.rejectCode] rather than
 * sent here, so the explanation sits with the thing it explains and survives until the next attempt.
 * A Snackbar would slide away while the user was still reading it and still holding a wrong code.
 */
sealed interface TwoFactorEvent {

    /**
     * The code checked out; [userId] can now be signed in.
     *
     * @property recoveryCodesRemaining Non-null only when what was accepted was a *recovery* code
     *   rather than an authenticator one, in which case it says how many are left. The screen tells
     *   the user, because this is the one moment they are looking at the app and able to act on
     *   "two left" — and because a code being silently spent is exactly the kind of thing someone
     *   should be told about.
     */
    data class Verified(val userId: Long, val recoveryCodesRemaining: Int? = null) : TwoFactorEvent

    /**
     * "LOST ACCESS?" — open the recovery-code screen for this account.
     *
     * Raised only by [TwoFactorViewModel]: the recovery screen is where that link leads, so it has
     * no such link of its own. It is declared here rather than in a second event type because one
     * sealed set for one pair of near-identical screens is easier to follow than two that overlap on
     * everything but this.
     */
    data class OpenRecovery(val userId: Long) : TwoFactorEvent
}

/**
 * The shared half of the two screens that check a six-digit code against an account's second factor:
 * the two-factor step of signing in ([TwoFactorViewModel]) and the recovery-code screen behind
 * "LOST ACCESS?" ([RecoveryCodeViewModel]).
 *
 * **Why a base class, when [CodeEntryViewModel] is already one.** That class covers *typing* a code —
 * the digits, the error state, when the button lights up — and knows nothing about what a code means.
 * This one covers *checking* a code: which account, the failed-attempt lockout around it, spending a
 * recovery code, and the four different things the answer can be. The two screens differ only in
 * their wording and in whether they offer a way onwards to recovery, so everything that decides
 * whether somebody gets in lives here, once, rather than in two copies that could drift apart on the
 * one path in the app where drifting apart means letting the wrong person in.
 *
 * **What a correct code means.** The account passed its password check on the previous screen but is
 * *not* signed in — nothing has called `SessionViewModel.signIn` yet, and nothing will until this
 * class reports [TwoFactorEvent.Verified]. That is what makes the second factor a gate rather than a
 * formality.
 *
 * **Both kinds of code are accepted on both screens.** A recovery code is by definition a stand-in
 * for the authenticator code, so refusing one on the screen that is asking for a code would be a
 * small cruelty to someone who has just lost their phone; and an authenticator code typed on the
 * recovery screen by someone who found their phone after all should hardly be turned away. The two
 * screens exist for what they *say*, not for what they will take. See [TwoFactorRepository.verify],
 * which tries the authenticator code first so an ordinary typo never burns a recovery code.
 *
 * **The lockout is the same one password sign-in uses.** A six-digit code is one chance in a million
 * per guess, which is only a real barrier if guesses are limited — so wrong codes are counted by the
 * same [LoginAttemptStore], against the same address, under the same five-tries-then-fifteen-minutes
 * rule as wrong passwords. Counting them separately would have handed an attacker a second, fresh
 * budget of attempts on the same account. Note that store's own caveat: it is a local speed bump,
 * not server-side throttling.
 *
 * @param savedStateHandle Holds the typed code (see [CodeEntryViewModel]) and the `userId`
 *   navigation argument both screens are given.
 * @property twoFactorRepository Checks the code and spends a recovery code when one is used.
 * @property userRepository Supplies the account's email address, which is the key the lockout counts
 *   against — the same key the log in screen used a moment ago.
 * @property loginAttemptStore The shared failed-attempt counter.
 */
abstract class TwoFactorCheckViewModel(
    savedStateHandle: SavedStateHandle,
    private val twoFactorRepository: TwoFactorRepository,
    private val userRepository: UserRepository,
    private val loginAttemptStore: LoginAttemptStore
) : CodeEntryViewModel(savedStateHandle) {

    /** The account that passed the password check and is waiting on its second factor. */
    protected val userId: Long = savedStateHandle[ARG_USER_ID] ?: NO_USER

    private val _events = Channel<TwoFactorEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    /**
     * True while a check is in flight.
     *
     * The check reads the database, so it is not instantaneous, and the verify button stays enabled
     * throughout. Without this, three quick taps would run three checks against the same recovery
     * code — and the database's single-use rule would correctly refuse two of them, leaving the user
     * told their own code was "already used" by their own extra taps.
     */
    private var isChecking = false

    /**
     * The verify button on either screen.
     *
     * The order is: refuse outright while locked, then check the code, then act on the answer. The
     * lock is re-read here rather than remembered, so a lock that expired while the screen sat open
     * simply stops applying — the same rule, read the same way, as on the log in screen.
     */
    override fun onVerify() {
        if (!canVerify.value || userId == NO_USER || isChecking) return

        viewModelScope.launch {
            isChecking = true
            try {
                val email = userRepository.getUser(userId)?.email

                if (email != null && loginAttemptStore.recordFor(email).isLocked) {
                    rejectCode(R.string.two_factor_locked)
                    return@launch
                }

                when (val result = twoFactorRepository.verify(userId, code.value)) {
                    TwoFactorCheck.Correct -> succeed(email, recoveryCodesRemaining = null)

                    is TwoFactorCheck.RecoveryCodeAccepted ->
                        succeed(email, recoveryCodesRemaining = result.remaining)

                    TwoFactorCheck.RecoveryCodeAlreadyUsed ->
                        fail(email, R.string.two_factor_recovery_already_used)

                    TwoFactorCheck.Incorrect -> fail(email, wrongCodeMessage)

                    // The account's second factor was turned off while this screen was open — on
                    // another device, or by a sync. There is nothing left to check, and keeping
                    // someone out on the strength of a requirement that no longer exists would be
                    // wrong, so the password they already gave is enough.
                    TwoFactorCheck.NotEnabled -> succeed(email, recoveryCodesRemaining = null)
                }
            } finally {
                isChecking = false
            }
        }
    }

    /**
     * What to say under the boxes when the code is simply not right.
     *
     * The one piece of wording the two screens genuinely disagree about: "check your authenticator"
     * is unhelpful advice for somebody reading a code off a printed sheet.
     */
    @get:StringRes
    protected abstract val wrongCodeMessage: Int

    /**
     * The code was accepted: end the run of failures and let the fragment sign the account in.
     *
     * Clearing the attempt record is what makes the count "consecutive", exactly as a correct
     * password does on the log in screen — four wrong codes followed by the right one must not leave
     * the account one mistake from a lock tomorrow.
     */
    private suspend fun succeed(email: String?, recoveryCodesRemaining: Int?) {
        email?.let(loginAttemptStore::clear)
        _events.send(TwoFactorEvent.Verified(userId, recoveryCodesRemaining))
    }

    /**
     * The code was refused: count it against the account and show [messageRes] under the boxes.
     *
     * The boxes are emptied by [rejectCode] so the next attempt can be typed straight away. Once the
     * count reaches the limit the store returns a locked record, and the message changes to say so —
     * the user finds out they are locked at the moment it happens rather than on the next attempt.
     */
    private fun fail(email: String?, @StringRes messageRes: Int) {
        val isNowLocked = email != null && loginAttemptStore.recordFailure(email).isLocked
        rejectCode(if (isNowLocked) R.string.two_factor_locked else messageRes)
    }

    /** Sends an event from a subclass — the "LOST ACCESS?" link is the only user of this. */
    protected fun send(event: TwoFactorEvent) {
        _events.trySend(event)
    }

    companion object {

        /**
         * The navigation argument both screens are reached with: whose second factor is being
         * checked.
         *
         * Declared here, rather than on each fragment, because the base class reads it and the two
         * fragments must therefore agree on its name. Each fragment re-exposes it so its own callers
         * have something local to name.
         */
        const val ARG_USER_ID = "userId"

        /**
         * Matches SessionViewModel.NO_USER_ID: the app's value for "no account".
         *
         * Not private, because the subclasses compare against it too. Kotlin does not allow
         * `protected` on a member of an object, and a companion object is one.
         */
        const val NO_USER = -1L
    }
}
