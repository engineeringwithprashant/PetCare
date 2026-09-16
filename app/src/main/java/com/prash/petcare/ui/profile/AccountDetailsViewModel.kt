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

    /** The account was deleted while the form was open — on another device, pulled down by a sync. */
    data object AccountMissing : AccountDetailsEvent
}

/**
 * Backs the form behind the profile screen's "EDIT" pill: the account's own name, contact number and
 * role.
 *
 * Sits in the middle of the MVVM chain — it reads from [UserRepository] and writes back through it,
 * and holds no Android view types.
 *
 * **What is deliberately not here.** The email, and the password hash. The address is the account's
 * sign-in identifier *and* the key the Firestore sync files this account's data under, so changing it
 * would strand everything already uploaded under the old one — the form shows it and says why it
 * cannot be edited. The hash never reaches this class at all: [UserRepository.updateDetails] reads the
 * row and copies it inside the data layer, so a screen that edits a name never holds a credential.
 *
 * @param userId Which account to edit, read from the session.
 */
class AccountDetailsViewModel(
    private val userRepository: UserRepository,
    private val userId: Long
) : ViewModel() {

    /**
     * The account being edited.
     *
     * Read from the database rather than passed in as arguments, which is what keeps the form correct
     * if a sync pulls down a change made on another device while it is open: Room re-emits and the
     * screen can fill itself from the new values.
     */
    val uiState: StateFlow<AccountDetailsUiState> = userRepository.observeUser(userId)
        .map { user -> AccountDetailsUiState(user = user) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = AccountDetailsUiState()
        )

    private val _events = Channel<AccountDetailsEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    /**
     * Validates and saves.
     *
     * The only rule is that a name is present: an account with a blank name would leave the profile
     * card, the greeting and every delegation message with a gap where a person should be. The phone
     * number is deliberately *not* validated beyond being trimmed — numbers are written with spaces,
     * dashes, brackets and country codes that differ by country, and a pattern strict enough to be
     * worth having would reject somebody's real number. It is never parsed by the app; it is shown as
     * typed and handed to the SMS manager as typed.
     *
     * Blank and null mean the same thing for the number — "none given" — and the repository stores an
     * emptied field as null rather than as an empty string that would later print as a gap.
     */
    fun onSave(fullName: String, phone: String, role: UserRole) {
        val trimmedName = fullName.trim()

        if (trimmedName.isEmpty()) {
            viewModelScope.launch { _events.send(AccountDetailsEvent.NameRequired) }
            return
        }

        viewModelScope.launch {
            val saved = userRepository.updateDetails(
                userId = userId,
                fullName = trimmedName,
                phone = phone,
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
