package com.prash.petcare.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.prash.petcare.PetCareApplication
import com.prash.petcare.data.local.entity.User
import com.prash.petcare.data.local.entity.UserRole
import com.prash.petcare.data.repository.UserRepository
import com.prash.petcare.util.PhoneCheck
import com.prash.petcare.util.PhoneNumbers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the account-details form shows: the account, or null while the first read is in flight. */
data class AccountDetailsUiState(val user: User? = null)

/** What the form should do once, after a save is attempted. */
sealed interface AccountDetailsEvent {

    /** Saved; the screen reports it and goes back. */
    data object Saved : AccountDetailsEvent

    /** The name was blank. */
    data object NameRequired : AccountDetailsEvent

    /** Something was typed in the phone box, but it is not a real number for the country chosen. */
    data object PhoneInvalid : AccountDetailsEvent

    /** The account was deleted while the form was open — on another device, pulled down by a sync. */
    data object AccountMissing : AccountDetailsEvent
}

/** Backs the form behind the profile screen's "EDIT" pill: the account's own name, contact number and role. */
class AccountDetailsViewModel(
    private val userRepository: UserRepository,
    private val userId: Long
) : ViewModel() {

    /** The account being edited. */
    val uiState: StateFlow<AccountDetailsUiState> = userRepository.observeUser(userId)
        .map { user -> AccountDetailsUiState(user = user) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = AccountDetailsUiState()
        )

    private val _events = Channel<AccountDetailsEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    /** Validates and saves. */
    fun onSave(fullName: String, phoneRegion: String, phone: String, role: UserRole) {
        val trimmedName = fullName.trim()
        val trimmedPhone = phone.trim()

        if (trimmedName.isEmpty()) {
            viewModelScope.launch { _events.send(AccountDetailsEvent.NameRequired) }
            return
        }

        val phoneCheck = PhoneNumbers.check(phoneRegion, trimmedPhone)
        if (phoneCheck == PhoneCheck.INVALID) {
            viewModelScope.launch { _events.send(AccountDetailsEvent.PhoneInvalid) }
            return
        }

        viewModelScope.launch {
            val saved = userRepository.updateDetails(
                userId = userId,
                fullName = trimmedName,
                // EMPTY passes the check above and lands here as an empty string.
                phone = if (phoneCheck == PhoneCheck.EMPTY) {
                    ""
                } else {
                    PhoneNumbers.toE164(phoneRegion, trimmedPhone) ?: trimmedPhone
                },
                role = role
            )

            _events.send(
                if (saved) AccountDetailsEvent.Saved else AccountDetailsEvent.AccountMissing
            )
        }
    }

    companion object {

        fun factory(userId: Long): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application =
                    this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as PetCareApplication

                AccountDetailsViewModel(
                    userRepository = application.userRepository,
                    userId = userId
                )
            }
        }
    }
}
