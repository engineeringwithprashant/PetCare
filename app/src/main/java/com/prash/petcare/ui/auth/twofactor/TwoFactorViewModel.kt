package com.prash.petcare.ui.auth.twofactor

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.prash.petcare.PetCareApplication
import com.prash.petcare.R
import com.prash.petcare.data.local.LoginAttemptStore
import com.prash.petcare.data.repository.TwoFactorRepository
import com.prash.petcare.data.repository.UserRepository

/** The ViewModel behind the two-factor check (TwoFactorFragment) — step 2 of signing in. */
class TwoFactorViewModel(
    savedStateHandle: SavedStateHandle,
    twoFactorRepository: TwoFactorRepository,
    userRepository: UserRepository,
    loginAttemptStore: LoginAttemptStore
) : TwoFactorCheckViewModel(
    savedStateHandle,
    twoFactorRepository,
    userRepository,
    loginAttemptStore
) {

    /** This screen asks for an authenticator code, so a wrong one points back at the app. */
    override val wrongCodeMessage: Int = R.string.error_code_incorrect

    /** "LOST ACCESS?" — the way through for someone whose authenticator is gone. */
    fun onLostAccess() {
        if (userId == NO_USER) return
        send(TwoFactorEvent.OpenRecovery(userId))
    }

    companion object {

        /** Builds the ViewModel with its repositories and the shared attempt store. */
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application =
                    this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as PetCareApplication
                TwoFactorViewModel(
                    savedStateHandle = createSavedStateHandle(),
                    twoFactorRepository = application.twoFactorRepository,
                    userRepository = application.userRepository,
                    loginAttemptStore = application.loginAttemptStore
                )
            }
        }
    }
}
