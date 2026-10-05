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

/** What the two-factor setup screen draws. */
data class TwoFactorSetupUiState(
    val secret: String? = null,
    val otpAuthUri: String? = null,
    val accountEmail: String? = null,
    val isStarting: Boolean = true,
    val startFailed: Boolean = false
)

/** One-off outcomes of the setup screen. */
sealed interface TwoFactorSetupEvent {

    /** The typed code matched, so the authenticator really is holding this secret. */
    data class Confirmed(val secret: String, val recoveryCodes: List<String>) : TwoFactorSetupEvent
}

/** The ViewModel behind the two-factor setup screen (TwoFactorSetupFragment). */
// The handle is not re-declared as a property here.
class TwoFactorSetupViewModel(
    savedStateHandle: SavedStateHandle,
    private val twoFactorRepository: TwoFactorRepository,
    private val userId: Long
) : CodeEntryViewModel(savedStateHandle) {

    private val _uiState = MutableStateFlow(TwoFactorSetupUiState())
    val uiState: StateFlow<TwoFactorSetupUiState> = _uiState.asStateFlow()

    private val _events = Channel<TwoFactorSetupEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    /** Produces the secret on first construction, or restores the one already made. */
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

    /** Rebuilds the screen's state around a secret that survived the process being killed. */
    private fun restore(secret: String) {
        val email: String = savedStateHandle[KEY_ACCOUNT_EMAIL] ?: ""
        _uiState.value = TwoFactorSetupUiState(
            secret = secret,
            otpAuthUri = twoFactorRepository.otpAuthUriFor(secret, email),
            accountEmail = email,
            isStarting = false
        )
    }

    /** "CONFIRM CODE" — the moment the scan is proved. */
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

        /** Builds the ViewModel for a particular account. */
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
