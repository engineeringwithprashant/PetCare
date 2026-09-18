package com.prash.petcare.ui.auth.twofactor

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.prash.petcare.PetCareApplication
import com.prash.petcare.R
import com.prash.petcare.data.repository.TwoFactorRepository
import com.prash.petcare.ui.auth.CodeEntryViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * What the two-factor setup screen draws.
 *
 * @property secret The new authenticator secret, Base32-encoded, shown as text for anyone who would
 *   rather type it into their authenticator than scan. Null until the first read returns.
 * @property otpAuthUri What the QR code encodes. Null for the same moment.
 * @property accountEmail The address the authenticator will file the entry under, shown so the user
 *   can see which account they are about to protect — it matters on a shared device.
 * @property isStarting True while the secret is being made, so the screen can wait rather than draw
 *   an empty QR frame.
 * @property startFailed True when the account could not be read at all, which in practice means it
 *   was deleted while this screen was opening. The screen says so instead of showing a QR code for
 *   nobody.
 */
data class TwoFactorSetupUiState(
    val secret: String? = null,
    val otpAuthUri: String? = null,
    val accountEmail: String? = null,
    val isStarting: Boolean = true,
    val startFailed: Boolean = false
)

/** One-off outcomes of the setup screen. */
sealed interface TwoFactorSetupEvent {

    /**
     * The typed code matched, so the authenticator really is holding this secret.
     *
     * Carries both halves of what the *next* screen needs to finish the job. Nothing has been stored
     * yet — see [TwoFactorSetupViewModel.onVerify].
     *
     * @property secret The secret to store once the user confirms they have saved their codes.
     * @property recoveryCodes The ten codes, in plain text. This is the only moment they are readable;
     *   the database will hold only their hashes.
     */
    data class Confirmed(val secret: String, val recoveryCodes: List<String>) : TwoFactorSetupEvent
}

/**
 * The ViewModel behind the two-factor setup screen (TwoFactorSetupFragment).
 *
 * Typing the confirmation code is inherited from [CodeEntryViewModel], the same base the sign-in code
 * screens use, so the six boxes behave identically here to everywhere else in the app. What this
 * class adds is the setup itself: making a secret, and deciding what a correct code means.
 *
 * ## Nothing is stored until it has been proved to work
 *
 * The secret exists here and in this screen's saved state, and nowhere else. It reaches the database
 * only at the very end of the flow, after two things have happened: a code generated from it has been
 * typed back correctly, and the user has confirmed they saved their recovery codes.
 *
 * That ordering is the whole point of the screen. A QR code can fail to scan silently — a blurred
 * camera, the wrong app, a manual entry with a transposed character — and if the secret were written
 * on arrival, the account would come out of it protected by something the user cannot produce. The
 * only proof that the scan worked is a code that came *back* from the authenticator, so that is what
 * the flow waits for.
 *
 * It also makes abandoning setup free. Backing out, rotating away, the process being killed, closing
 * the app at the recovery-codes screen — none of them needs undoing, because none of them left
 * anything behind. That is why there is no cleanup code anywhere in this flow.
 *
 * ## Why the secret lives in the SavedStateHandle
 *
 * The user is being asked to leave PetCare, open their authenticator, and come back. That is exactly
 * the moment Android is most likely to kill a backgrounded process. A secret held in a plain property
 * would be gone when they returned, so the code they had just carefully scanned would no longer match
 * anything and the only explanation the screen could offer would be "wrong code". Keeping it in the
 * handle means they come back to the setup they left. [CodeEntryViewModel] keeps the typed digits
 * there for the same reason.
 *
 * @param savedStateHandle Carries the typed code and the generated secret across process death.
 * @param twoFactorRepository Makes the secret, checks the confirmation code, generates the codes.
 * @param userId Which account is being protected, taken from the session by the fragment.
 */
// The handle is not re-declared as a property here: CodeEntryViewModel already exposes the one it was
// given as `savedStateHandle`, and a second property of the same name would shadow it.
class TwoFactorSetupViewModel(
    savedStateHandle: SavedStateHandle,
    private val twoFactorRepository: TwoFactorRepository,
    private val userId: Long
) : CodeEntryViewModel(savedStateHandle) {

    private val _uiState = MutableStateFlow(TwoFactorSetupUiState())
    val uiState: StateFlow<TwoFactorSetupUiState> = _uiState.asStateFlow()

    private val _events = Channel<TwoFactorSetupEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    /**
     * Produces the secret on first construction, or restores the one already made.
     *
     * **Restoring matters more than it looks.** A new secret on every rotation would invalidate the
     * QR code the user is in the middle of scanning, and the entry they had just added to their
     * authenticator would be for a secret this screen had already thrown away. So the handle is
     * consulted first, and a new secret is made only when there genuinely is not one.
     */
    init {
        val existing: String? = savedStateHandle[KEY_SECRET]
        if (existing != null) {
            restore(existing)
        } else {
            start()
        }
    }

    /** Builds a fresh secret and its QR URI. */
    private fun start() {
        viewModelScope.launch {
            val setup = twoFactorRepository.beginSetup(userId)

            if (setup == null) {
                _uiState.update { it.copy(isStarting = false, startFailed = true) }
                return@launch
            }

            savedStateHandle[KEY_SECRET] = setup.secret
            savedStateHandle[KEY_ACCOUNT_EMAIL] = setup.accountEmail

            _uiState.value = TwoFactorSetupUiState(
                secret = setup.secret,
                otpAuthUri = setup.otpAuthUri,
                accountEmail = setup.accountEmail,
                isStarting = false
            )
        }
    }

    /**
     * Rebuilds the screen's state around a secret that survived the process being killed.
     *
     * The URI is derived again rather than saved: it is a pure function of the secret and the address,
     * so storing it too would be a second copy of the same fact that could be restored out of step
     * with the first.
     */
    private fun restore(secret: String) {
        val email: String = savedStateHandle[KEY_ACCOUNT_EMAIL] ?: ""
        _uiState.value = TwoFactorSetupUiState(
            secret = secret,
            otpAuthUri = twoFactorRepository.otpAuthUriFor(secret, email),
            accountEmail = email,
            isStarting = false
        )
    }

    /**
     * "CONFIRM CODE" — the moment the scan is proved.
     *
     * A correct code means the authenticator is genuinely holding this secret and producing codes
     * this app agrees with, clock drift and all. The recovery codes are generated here and handed on
     * with it, so the next screen has everything it needs and nothing has to be regenerated (which
     * would show the user one set and store another).
     *
     * A wrong code empties the boxes and says so, leaving the QR code on screen to be scanned again.
     * Nothing is counted and nothing locks: this is the account's owner, already signed in, setting
     * up their own second factor — there is no one to keep out, and locking them out of a screen they
     * chose to open would only punish a bad camera.
     */
    override fun onVerify() {
        val secret = _uiState.value.secret
        if (!canVerify.value || secret == null) return

        if (!twoFactorRepository.isSetupCodeValid(secret, code.value)) {
            rejectCode(R.string.two_factor_setup_code_wrong)
            return
        }

        _events.trySend(
            TwoFactorSetupEvent.Confirmed(
                secret = secret,
                recoveryCodes = twoFactorRepository.generateRecoveryCodes()
            )
        )
    }

    companion object {

        // Namespaced so they cannot collide with a navigation argument, since the handle holds both.
        private const val KEY_SECRET = "two_factor_setup.secret"
        private const val KEY_ACCOUNT_EMAIL = "two_factor_setup.email"

        /**
         * Builds the ViewModel for a particular account.
         *
         * A function rather than a property, as on [com.prash.petcare.ui.profile.ProfileViewModel],
         * because the signed-in id is only known at runtime and has to be captured to reach the
         * constructor.
         */
        fun factory(userId: Long): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application =
                    this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as PetCareApplication
                TwoFactorSetupViewModel(
                    savedStateHandle = createSavedStateHandle(),
                    twoFactorRepository = application.twoFactorRepository,
                    userId = userId
                )
            }
        }
    }
}
