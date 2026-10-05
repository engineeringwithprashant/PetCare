package com.prash.petcare.ui.auth.reset

import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.prash.petcare.PetCareApplication
import com.prash.petcare.R
import com.prash.petcare.data.auth.PasswordResetRepository
import com.prash.petcare.data.auth.PasswordResetResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Everything the reset confirmation screen draws, apart from the address, which never changes. */
data class ResetSentUiState(
    val secondsUntilResend: Int = 0,
    val isSending: Boolean = false,
    @param:StringRes val notice: Int? = null,
    val noticeIsError: Boolean = false
) {
    /** "RESEND" is tappable only when the cooldown has ended and no send is in progress. */
    val canResend: Boolean
        get() = !isSending && secondsUntilResend == 0
}

/** The ViewModel behind the reset confirmation screen (ResetSentFragment). */
class ResetSentViewModel(
    private val savedStateHandle: SavedStateHandle,
    private val repository: PasswordResetRepository,
    private val now: () -> Long = System::currentTimeMillis
) : ViewModel() {

    /** The address as the user typed it. A required navigation argument, so it is always present. */
    val email: String = checkNotNull(savedStateHandle[ResetSentFragment.ARG_EMAIL]) {
        "ResetSentFragment needs the ${ResetSentFragment.ARG_EMAIL} argument"
    }

    private val _uiState = MutableStateFlow(ResetSentUiState())
    val uiState: StateFlow<ResetSentUiState> = _uiState.asStateFlow()

    /** The countdown loop currently running, so a new cooldown can replace it. */
    private var countdown: Job? = null

    init {
        // Start the cooldown on the first visit only. After a restore, the saved end time is already there.
        if (!savedStateHandle.contains(KEY_READY_AT)) {
            savedStateHandle[KEY_READY_AT] = now() + ResendCooldown.DURATION_MILLIS
        }
        runCountdown()
    }

    /** "RESEND". */
    fun onResend() {
        if (!_uiState.value.canResend) return
        _uiState.update { it.copy(isSending = true, notice = null) }

        viewModelScope.launch {
            val result = repository.sendResetEmail(email)
            _uiState.update { it.copy(isSending = false) }

            when (result) {
                PasswordResetResult.Sent -> {
                    startCooldown()
                    _uiState.update {
                        it.copy(notice = R.string.reset_sent_resent, noticeIsError = false)
                    }
                }

                else -> {
                    // After a rate-limit error, retrying straight away would only fail again.
                    if (result == PasswordResetResult.RateLimited) startCooldown()
                    _uiState.update {
                        it.copy(notice = result.failureMessage(), noticeIsError = true)
                    }
                }
            }
        }
    }

    /** Sets a new cooldown end time and restarts the countdown from it. */
    private fun startCooldown() {
        savedStateHandle[KEY_READY_AT] = now() + ResendCooldown.DURATION_MILLIS
        runCountdown()
    }

    /** Updates [ResetSentUiState.secondsUntilResend] once per second until it reaches 0. */
    private fun runCountdown() {
        countdown?.cancel()
        countdown = viewModelScope.launch {
            val readyAt: Long = savedStateHandle[KEY_READY_AT] ?: 0L
            while (true) {
                val millisLeft = ResendCooldown.millisLeft(now(), readyAt)
                val seconds = ResendCooldown.secondsToShow(millisLeft)
                _uiState.update { it.copy(secondsUntilResend = seconds) }
                if (millisLeft == 0L) break
                delay(millisLeft - (seconds - 1) * 1000L)
            }
        }
    }

    companion object {

        /** SavedStateHandle key for the time the current cooldown ends, in epoch milliseconds. */
        private const val KEY_READY_AT = "reset_sent.ready_at"

        /** Builds the ViewModel with its SavedStateHandle and repository. */
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application =
                    this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as PetCareApplication
                ResetSentViewModel(createSavedStateHandle(), application.passwordResetRepository)
            }
        }
    }
}
