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
 * The ViewModel behind the two-factor check (TwoFactorFragment) — step 2 of signing in.
 *
 * Almost everything it does is inherited: typing the code from [com.prash.petcare.ui.auth.CodeEntryViewModel],
 * and checking it (with the failed-attempt lockout around it) from [TwoFactorCheckViewModel], which
 * explains the rules at length. What is left here is the one thing only *this* screen has: the way
 * out for somebody whose authenticator is gone.
 */
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

    /**
     * "LOST ACCESS?" — the way through for someone whose authenticator is gone.
     *
     * It opens the recovery-code screen rather than doing anything itself. That screen types its code
     * on the same keypad and checks it through the same repository; what it adds is wording aimed at
     * a person holding a printed list rather than a phone. Without a link like this one, a user whose
     * phone is lost has no way of learning from the app that a way through exists at all.
     */
    fun onLostAccess() {
        if (userId == NO_USER) return
        send(TwoFactorEvent.OpenRecovery(userId))
    }

    companion object {

        /**
         * Builds the ViewModel with its repositories and the shared attempt store.
         *
         * A factory is needed because the default ViewModelProvider can only build ViewModels with no
         * constructor arguments beyond a SavedStateHandle. `createSavedStateHandle()` produces the
         * same handle the default factory would have — which matters here, because it is the handle
         * that carries both the typed digits and the `userId` navigation argument.
         */
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
