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

/**
 * The ViewModel behind the recovery-code screen (RecoveryCodeFragment), reached from "LOST ACCESS?"
 * on the two-factor check.
 *
 * It is [TwoFactorCheckViewModel] with different wording, and deliberately nothing more: the same
 * repository spends the code, the same lockout counts wrong ones, and the same event tells the
 * fragment to sign the account in. A second implementation of any of that would be a second set of
 * rules about who gets into an account, which is the last thing worth having two of.
 *
 * What the screen adds is not behaviour but *address*: it speaks to someone holding a printed list
 * rather than a phone, so its heading, its helper line and its wrong-code message all assume the
 * authenticator is gone rather than mistyped.
 */
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

    /**
     * A wrong code here points at the saved list, not at the authenticator app.
     *
     * "Check it and try again" is advice a user can act on when the code is on their phone in front
     * of them. Someone working down a sheet of paper needs to be told the code is not one of theirs,
     * so they look at a different line rather than retyping the same one.
     */
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
