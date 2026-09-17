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

/**
 * Everything the reset confirmation screen draws, apart from the address, which never changes.
 *
 * @property secondsUntilResend Seconds left before "RESEND" works again. 0 means it works now.
 * @property isSending True while a resend is in progress.
 * @property notice A line to show under the resend row, or null for none. It is either the
 *   "sent again" confirmation or a failure, and [noticeIsError] says which.
 * @property noticeIsError Draw [notice] in the error colour (true) or the muted helper colour.
 */
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

/**
 * The **ViewModel** behind the reset confirmation screen (ResetSentFragment). It holds the address
 * the link went to, runs the resend cooldown, and sends the email again when asked.
 *
 * **The cooldown starts on arrival.** This screen only opens after a successful send, so a link
 * went out moments ago. "RESEND" starts disabled and counts down, and it counts down again after
 * every resend.
 *
 * **Why SavedStateHandle.** The time the cooldown ends is saved there. If the app is killed in the
 * background (while the user checks their email, for example) and later restored, the countdown
 * continues from where it was. Without this, the restored screen would start a fresh countdown, or
 * allow an immediate resend.
 *
 * @param savedStateHandle The `email` navigation argument and the saved cooldown end time.
 * @param repository Sends the email again. The ViewModel's only route to Firebase.
 * @param now The current time in milliseconds. A parameter so a test could supply a fake clock.
 */
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
        // Start the cooldown on the first visit only. After a restore, the saved end time is
        // already there, and the countdown just continues from it.
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
                    // After a rate-limit error, retrying straight away would only fail again, so the
                    // cooldown restarts. After other failures, such as no network, the user can
                    // retry as soon as the problem is fixed.
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

    /**
     * Updates [ResetSentUiState.secondsUntilResend] once per second until it reaches 0.
     *
     * Each wait lasts until the displayed number next changes, not a fixed 1000 ms. This keeps the
     * countdown in step with the real end time. With 42.3 s left, the display reads 43, and the next
     * wait is 300 ms, until it should read 42.
     *
     * It runs in viewModelScope, so it keeps going through a rotation and stops when the screen is
     * closed.
     */
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

        /**
         * Builds the ViewModel with its SavedStateHandle and repository.
         *
         * `createSavedStateHandle()` gives the same handle the default factory would, already
         * holding the fragment's navigation arguments. A custom factory has to ask for it
         * explicitly, because it also needs the repository from PetCareApplication.
         */
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application =
                    this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as PetCareApplication
                ResetSentViewModel(createSavedStateHandle(), application.passwordResetRepository)
            }
        }
    }
}
