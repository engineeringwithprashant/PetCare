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

/** The ViewModel behind the recovery-code screen (RecoveryCodeFragment). */
class RecoveryCodeViewModel(
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

    /** A wrong code here points at the saved list, not at the authenticator app. */
    override val wrongCodeMessage: Int = R.string.two_factor_recovery_incorrect

    companion object {

        /** Builds the ViewModel, exactly as [TwoFactorViewModel.Factory] does and for the same reasons. */
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application =
                    this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as PetCareApplication
                RecoveryCodeViewModel(
                    savedStateHandle = createSavedStateHandle(),
                    twoFactorRepository = application.twoFactorRepository,
                    userRepository = application.userRepository,
                    loginAttemptStore = application.loginAttemptStore
                )
            }
        }
    }
}
