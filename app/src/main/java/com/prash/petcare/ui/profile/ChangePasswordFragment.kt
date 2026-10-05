package com.prash.petcare.ui.profile

import android.os.Bundle
import android.text.InputFilter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.google.android.material.snackbar.Snackbar
import com.prash.petcare.R
import com.prash.petcare.databinding.FragmentChangePasswordBinding
import com.prash.petcare.ui.session.SessionViewModel
import kotlinx.coroutines.launch

/** The form behind the profile screen's "UPDATE" pill: confirm the current password, set a new one. */
class ChangePasswordFragment : Fragment() {

    private var _binding: FragmentChangePasswordBinding? = null
    private val binding get() = _binding!!

    private val sessionViewModel: SessionViewModel by activityViewModels { SessionViewModel.Factory }

    private val viewModel: ChangePasswordViewModel by viewModels {
        ChangePasswordViewModel.factory(sessionViewModel.userId.value)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentChangePasswordBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // The upper bound, applied from the ViewModel's constant rather than typed into the layout.
        listOf(binding.newPasswordInput, binding.confirmPasswordInput).forEach { field ->
            field.filters = arrayOf(InputFilter.LengthFilter(ChangePasswordViewModel.MAXIMUM_LENGTH))
        }

        // The checklist under the field answers every keystroke.
        binding.newPasswordInput.doAfterTextChanged { text ->
            viewModel.onNewPasswordChanged(text?.toString().orEmpty())
        }

        binding.saveButton.setOnClickListener { submit() }
        observeViewModel()
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.events.collect(::handleEvent) }
                launch {
                    // Drawn from the ViewModel's measurement.
                    viewModel.passwordRequirements.collect(binding.passwordChecklist::show)
                }
                launch {
                    // The change is a round trip to Firebase, so the button is disabled while it runs.
                    viewModel.isWorking.collect { isWorking ->
                        binding.saveButton.isEnabled = !isWorking
                    }
                }
            }
        }
    }

    private fun submit() {
        viewModel.onSubmit(
            currentPassword = binding.currentPasswordInput.text?.toString().orEmpty(),
            newPassword = binding.newPasswordInput.text?.toString().orEmpty(),
            confirmPassword = binding.confirmPasswordInput.text?.toString().orEmpty()
        )
    }

    /** Reacts to the attempt. */
    private fun handleEvent(event: ChangePasswordEvent) {
        clearErrors()

        when (event) {
            is ChangePasswordEvent.Changed -> {
                // Back to the profile screen.
                showMessage(getString(R.string.change_password_changed))
                findNavController().navigateUp()
            }

            is ChangePasswordEvent.CurrentRequired ->
                binding.currentPasswordInputLayout.error =
                    getString(R.string.change_password_current_required)

            is ChangePasswordEvent.CurrentWrong ->
                binding.currentPasswordInputLayout.error =
                    getString(R.string.change_password_current_wrong)

            // The message comes from the shared validation.
            is ChangePasswordEvent.NewPasswordRejected ->
                binding.newPasswordInputLayout.error = getString(event.messageRes)

            // On the confirmation field, not the new-password.
            is ChangePasswordEvent.Mismatch ->
                binding.confirmPasswordInputLayout.error =
                    getString(R.string.change_password_mismatch)

            is ChangePasswordEvent.SameAsCurrent ->
                binding.newPasswordInputLayout.error = getString(R.string.change_password_same)

            is ChangePasswordEvent.WeakPassword ->
                binding.newPasswordInputLayout.error = getString(R.string.error_password_weak)

            // Not about any one field, so a Snackbar rather than a field error.
            is ChangePasswordEvent.Message -> showMessage(getString(event.messageRes))

            is ChangePasswordEvent.AccountMissing -> {
                showMessage(getString(R.string.account_details_gone))
                findNavController().navigateUp()
            }
        }
    }

    private fun clearErrors() {
        binding.currentPasswordInputLayout.error = null
        binding.newPasswordInputLayout.error = null
        binding.confirmPasswordInputLayout.error = null
    }

    private fun showMessage(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
