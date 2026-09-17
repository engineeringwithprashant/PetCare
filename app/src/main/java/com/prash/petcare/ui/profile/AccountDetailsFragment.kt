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
import com.prash.petcare.ui.session.SessionViewModel
import kotlinx.coroutines.launch

/**
 * The form behind the profile screen's "EDIT" pill: the account's own name, contact number and role.
 *
 * In MVVM this is the **View**: it fills the fields from what [AccountDetailsViewModel] publishes and
 * forwards what the user typed. No validation happens here — the ViewModel decides whether a name is
 * acceptable and this class only renders the answer, which is what keeps that rule testable without a
 * device.
 *
 * It takes no navigation arguments. The account is the signed-in one, read from [SessionViewModel] as
 * on every other screen.
 */
class AccountDetailsFragment : Fragment() {

    private var _binding: FragmentAccountDetailsBinding? = null
    private val binding get() = _binding!!

    private val sessionViewModel: SessionViewModel by activityViewModels { SessionViewModel.Factory }

    private val viewModel: AccountDetailsViewModel by viewModels {
        AccountDetailsViewModel.factory(sessionViewModel.userId.value)
    }

    /**
     * Whether the fields have been filled from the database yet.
     *
     * The account is observed as a flow, so it can emit again while the form is open — and re-filling
     * the fields on a later emission would wipe out whatever the user had typed in the meantime. This
     * flag makes the fill happen exactly once, on the first emission that carries an account.
     *
     * A field rather than a check for "are the fields empty": the user may legitimately have cleared
     * the phone field, and that is a state worth preserving rather than treating as "not filled yet".
     */
    private var hasFilledForm = false

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
        binding.phoneInput.setText(user.phone.orEmpty())

        // The email is shown but not editable. The field is disabled rather than absent, because the
        // address is one of the account's defining facts and a form that simply omitted it would look
        // as though it had been forgotten; the helper text underneath says why it cannot change here.
        binding.emailInput.setText(user.email)

        // The role, as a two-button toggle. checkedButtonId is set rather than each button's checked
        // state, so the group can never end up with both selected or neither.
        binding.roleToggleGroup.check(
            if (user.role == UserRole.CARER) R.id.roleCarerButton else R.id.roleOwnerButton
        )
    }

    private fun save() {
        viewModel.onSave(
            fullName = binding.nameInput.text?.toString().orEmpty(),
            phone = binding.phoneInput.text?.toString().orEmpty(),
            role = if (binding.roleToggleGroup.checkedButtonId == R.id.roleCarerButton) {
                UserRole.CARER
            } else {
                UserRole.OWNER
            }
        )
    }

    /**
     * Reacts to the save attempt.
     *
     * The name error is shown on the field itself rather than as a Snackbar, because it belongs to one
     * particular input and a message at the bottom of the screen would leave the user hunting for
     * which. The error is cleared on every attempt, so a fixed field stops complaining.
     */
    private fun handleEvent(event: AccountDetailsEvent) {
        binding.nameInputLayout.error = null

        when (event) {
            is AccountDetailsEvent.Saved -> {
                // Back to the profile screen, which re-reads the account from Room and redraws itself
                // — so there is nothing to hand back and no result to deliver.
                showMessage(getString(R.string.account_details_saved))
                findNavController().navigateUp()
            }

            is AccountDetailsEvent.NameRequired ->
                binding.nameInputLayout.error = getString(R.string.account_details_name_required)

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
        _binding = null
    }
}
