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

    /**
     * Something was typed in the phone box, but it is not a real number for the country chosen.
     *
     * An *empty* number has no event of its own, because it is not an error here — this field is
     * optional, unlike the one on the register form.
     */
    data object PhoneInvalid : AccountDetailsEvent

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
 * **What is deliberately not here: the email.** The address is the account's sign-in identifier in
 * Firebase Authentication *and* the key the Firestore sync files this account's data under, so
 * changing it would strand everything already uploaded under the old one. The form shows it and says
 * why it cannot be edited. [UserRepository.updateDetails] reads the row and copies it inside the data
 * layer, so this class only ever handles the fields it edits.
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
     * Two rules. A name must be present: an account with a blank name would leave the profile card,
     * the greeting and every delegation message with a gap where a person should be.
     *
     * **And a number, if one is given, must be a real number for the country chosen.** This form used
     * to accept whatever was typed, on the reasoning that number formats differ too much by country
     * for any check to be safe — which was true of a hand-written pattern and is no longer true now
     * that the rules come from a per-country library (see [PhoneNumbers]). The check earns its place
     * here specifically because this number is now used for something: it is what the account is
     * found by when its owner signs in with their phone number instead of their email, and a number
     * stored in a form nothing can parse would silently never match.
     *
     * The number is stored in international form rather than as typed, for the same reason — one
     * canonical form is what makes that lookup possible at all.
     *
     * Blank and null still mean the same thing — "none given" — and the repository stores an emptied
     * field as null rather than as an empty string that would later print as a gap.
     *
     * @param phoneRegion The country chosen beside the number box ("NP"), and [phone] the local
     *   number typed next to it.
     */
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
                // EMPTY passes the check above and lands here as an empty string, which the
                // repository stores as null. Anything else has just been confirmed valid, so toE164
                // cannot return null — the elvis is there because the function is nullable, not
                // because the fallback is reachable.
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
