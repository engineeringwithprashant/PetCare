package com.prash.petcare.ui.auth.twofactor

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.prash.petcare.PetCareApplication
import com.prash.petcare.data.repository.TwoFactorRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * What the recovery-codes screen draws.
 *
 * @property codes The ten codes, in plain text, as generated on the previous screen.
 * @property hasConfirmedSaved Whether the user has ticked "I have saved these". The finish button
 *   waits on it — see [RecoveryCodesViewModel].
 * @property isFinishing True while the switch-on is being written, so a second tap cannot start a
 *   second one.
 */
data class RecoveryCodesUiState(
    val codes: List<String> = emptyList(),
    val hasConfirmedSaved: Boolean = false,
    val isFinishing: Boolean = false
) {

    /** The finish button lights up only once the codes are saved and nothing is already in flight. */
    val canFinish: Boolean get() = hasConfirmedSaved && !isFinishing && codes.isNotEmpty()
}

/** One-off outcomes of the recovery-codes screen. */
sealed interface RecoveryCodesEvent {

    /** Two-factor authentication is now on. The screen returns to the profile. */
    data object Enabled : RecoveryCodesEvent

    /** The account had gone by the time the write was attempted, so nothing was turned on. */
    data object Failed : RecoveryCodesEvent
}

/**
 * The ViewModel behind the recovery-codes screen (RecoveryCodesFragment) — the last step of setting
 * two-factor authentication up.
 *
 * ## This screen is where the feature is actually switched on
 *
 * Both halves are written together, here, in [onFinish]: the authenticator secret proved on the
 * previous screen, and the hashes of the codes shown on this one. Nothing before this point touched
 * the database.
 *
 * Splitting it that way is what lets the flow insist on the confirmation. If the secret had been
 * stored as soon as the code was confirmed, someone who closed the app on this screen would be left
 * with an account protected by an authenticator on one phone and no way back if that phone were
 * lost — the precise situation recovery codes exist to prevent, created by the screen that hands them
 * out. Waiting until the tick means walking away costs nothing: two-factor authentication is still
 * off, and setup can be started again from the profile screen.
 *
 * ## Why the codes arrive as an argument
 *
 * They are generated on the setup screen, at the moment the code was confirmed, and travel here in
 * the navigation arguments. Generating them here instead would be a second set — the one shown and
 * the one stored could not then be guaranteed to be the same set, which is a bug the user would only
 * discover on the day they needed a code.
 *
 * Arguments live in the [SavedStateHandle], so the list survives the process being killed while the
 * user is photographing the screen or writing the codes down — which is exactly what they are being
 * asked to go and do.
 *
 * @param savedStateHandle Carries the secret and the codes, and the tick across a rotation.
 * @param twoFactorRepository Where the secret and the hashed codes are written.
 * @param userId Which account is being protected.
 */
class RecoveryCodesViewModel(
    private val savedStateHandle: SavedStateHandle,
    private val twoFactorRepository: TwoFactorRepository,
    private val userId: Long
) : ViewModel() {

    /** The secret proved on the setup screen, waiting to be stored. */
    private val secret: String = savedStateHandle[RecoveryCodesFragment.ARG_SECRET] ?: ""

    private val _uiState = MutableStateFlow(
        RecoveryCodesUiState(
            codes = savedStateHandle.get<Array<String>>(RecoveryCodesFragment.ARG_CODES)
                ?.toList()
                .orEmpty(),
            // Restored from the handle, so turning the phone while reading the codes does not silently
            // untick a box the user has already ticked and re-disable the finish button.
            hasConfirmedSaved = savedStateHandle[KEY_CONFIRMED] ?: false
        )
    )
    val uiState: StateFlow<RecoveryCodesUiState> = _uiState.asStateFlow()

    private val _events = Channel<RecoveryCodesEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    /** The "I have saved these codes" tick. */
    fun onConfirmedSavedChanged(isConfirmed: Boolean) {
        savedStateHandle[KEY_CONFIRMED] = isConfirmed
        _uiState.update { it.copy(hasConfirmedSaved = isConfirmed) }
    }

    /**
     * "FINISH SETUP": stores the secret and the hashed codes, turning two-factor authentication on.
     *
     * Guarded by [RecoveryCodesUiState.canFinish] rather than trusting the button's enabled state:
     * a disabled button can still be reached by an accessibility action or a stray click during a
     * transition, and this is the one call in the flow that changes how the account signs in.
     */
    fun onFinish() {
        val state = _uiState.value
        if (!state.canFinish || secret.isEmpty()) return

        viewModelScope.launch {
            _uiState.update { it.copy(isFinishing = true) }

            val enabled = twoFactorRepository.enable(userId, secret, state.codes)

            _uiState.update { it.copy(isFinishing = false) }
            _events.send(if (enabled) RecoveryCodesEvent.Enabled else RecoveryCodesEvent.Failed)
        }
    }

    companion object {

        /** Namespaced so it cannot collide with a navigation argument in the same handle. */
        private const val KEY_CONFIRMED = "recovery_codes.confirmed"

        /** Builds the ViewModel for a particular account; see [TwoFactorSetupViewModel.factory]. */
        fun factory(userId: Long): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application =
                    this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as PetCareApplication
                RecoveryCodesViewModel(
                    savedStateHandle = createSavedStateHandle(),
                    twoFactorRepository = application.twoFactorRepository,
                    userId = userId
                )
            }
        }
    }
}
