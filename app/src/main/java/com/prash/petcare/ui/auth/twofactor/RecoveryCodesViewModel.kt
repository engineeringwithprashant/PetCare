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

/** What the recovery-codes screen draws. */
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

/** The ViewModel behind the recovery-codes screen (RecoveryCodesFragment). */
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
            // Restored from the handle, so turning the phone.
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

    /** "FINISH SETUP": stores the secret and the hashed codes, turning two-factor authentication on. */
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
