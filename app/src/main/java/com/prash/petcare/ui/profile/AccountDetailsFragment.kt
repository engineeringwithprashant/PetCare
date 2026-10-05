package com.prash.petcare.ui.profile

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.google.android.material.snackbar.Snackbar
import com.prash.petcare.R
import com.prash.petcare.data.local.entity.User
import com.prash.petcare.data.local.entity.UserRole
import com.prash.petcare.databinding.FragmentAccountDetailsBinding
import com.prash.petcare.ui.common.PhoneNumberInput
import com.prash.petcare.ui.session.SessionViewModel
import kotlinx.coroutines.launch

/** The form behind the profile screen's "EDIT" pill: the account's own name, contact number and role. */
class AccountDetailsFragment : Fragment() {

    private var _binding: FragmentAccountDetailsBinding? = null
    private val binding get() = _binding!!

    private val sessionViewModel: SessionViewModel by activityViewModels { SessionViewModel.Factory }

    private val viewModel: AccountDetailsViewModel by viewModels {
        AccountDetailsViewModel.factory(sessionViewModel.userId.value)
    }

    /** Whether the fields have been filled from the database yet. */
    private var hasFilledForm = false

    /** The country selector and number box, driven as one control. */
    private var phoneInput: PhoneNumberInput? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAccountDetailsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // The two phone boxes behave as one control. Editing either clears the error left by a previous save attempt.
        phoneInput = PhoneNumberInput(
            countryLayout = binding.phoneCountryLayout,
            countryInput = binding.phoneCountryInput,
            numberLayout = binding.phoneInputLayout,
            numberInput = binding.phoneInput,
            onChanged = { binding.phoneInputLayout.error = null }
        )

        binding.saveButton.setOnClickListener { save() }
        observeViewModel()
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collect { state -> state.user?.let(::fillForm) }
                }
                launch { viewModel.events.collect(::handleEvent) }
            }
        }
    }

    /** Fills the fields from the stored account — once; see [hasFilledForm]. */
    private fun fillForm(user: User) {
        if (hasFilledForm) return
        hasFilledForm = true

        binding.nameInput.setText(user.fullName)

        // Splits the stored number back into a country and the local digits.
        phoneInput?.setStoredNumber(user.phone)

        // The email is shown but not editable. The field is disabled rather than absent.
        binding.emailInput.setText(user.email)

        // The role, as a two-button toggle. checkedButtonId is set rather than each button's checked state.
        binding.roleToggleGroup.check(
            if (user.role == UserRole.CARER) R.id.roleCarerButton else R.id.roleOwnerButton
        )
    }

    private fun save() {
        val phone = phoneInput ?: return

        viewModel.onSave(
            fullName = binding.nameInput.text?.toString().orEmpty(),
            // The country and the digits go over separately: whether the two make a real number.
            phoneRegion = phone.regionCode(),
            phone = phone.nationalNumber(),
            role = if (binding.roleToggleGroup.checkedButtonId == R.id.roleCarerButton) {
                UserRole.CARER
            } else {
                UserRole.OWNER
            }
        )
    }

    /** Reacts to the save attempt. */
    private fun handleEvent(event: AccountDetailsEvent) {
        binding.nameInputLayout.error = null
        binding.phoneInputLayout.error = null

        when (event) {
            is AccountDetailsEvent.Saved -> {
                // Back to the profile screen, which re-reads the account from Room and redraws itself.
                showMessage(getString(R.string.account_details_saved))
                findNavController().navigateUp()
            }

            is AccountDetailsEvent.NameRequired ->
                binding.nameInputLayout.error = getString(R.string.account_details_name_required)

            // Under the number rather than the country, because the number is usually the part to change.
            is AccountDetailsEvent.PhoneInvalid ->
                binding.phoneInputLayout.error = getString(R.string.error_phone_invalid)

            is AccountDetailsEvent.AccountMissing -> {
                showMessage(getString(R.string.account_details_gone))
                findNavController().navigateUp()
            }
        }
    }

    private fun showMessage(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Dropped with the binding: it holds views from this hierarchy.
        phoneInput = null
        _binding = null
    }
}
