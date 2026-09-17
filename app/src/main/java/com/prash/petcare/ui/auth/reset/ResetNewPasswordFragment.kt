package com.prash.petcare.ui.auth.reset

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.google.android.material.snackbar.Snackbar
import com.prash.petcare.R
import com.prash.petcare.databinding.FragmentResetNewPasswordBinding
import com.prash.petcare.ui.auth.AuthValidation
import com.prash.petcare.ui.auth.show
import com.prash.petcare.ui.auth.showStep
import com.prash.petcare.ui.auth.textString
import kotlinx.coroutines.launch

/**
 * Password reset, step 3 of 3: choose the new password.
 *
 * Not in the mockups; built from the same parts as the other sign-in screens so it reads as the last
 * page of the same flow. The **View** in MVVM: it passes the two fields to [ResetNewPasswordViewModel]
 * and shows the errors it returns under the matching field. Saving is not connected yet — see the TODO
 * in [ResetNewPasswordViewModel.onSave].
 */
class ResetNewPasswordFragment : Fragment() {

    private var _binding: FragmentResetNewPasswordBinding? = null

    /** Valid only between [onCreateView] and [onDestroyView]; see AuthFragment for why. */
    private val binding get() = _binding!!

    private val viewModel: ResetNewPasswordViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentResetNewPasswordBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.header.show(R.string.reset_password_title, getString(R.string.reset_password_subtitle))
        binding.stepProgress.showStep(step = 3, total = ResetChooseFragment.TOTAL_STEPS)

        // The placeholder states the enforced rule, from the constant that enforces it.
        binding.newPasswordInput.hint = resources.getQuantityString(
            R.plurals.register_password_hint,
            AuthValidation.MIN_PASSWORD_LENGTH,
            AuthValidation.MIN_PASSWORD_LENGTH
        )

        binding.saveButton.setOnClickListener { save() }
        binding.confirmPasswordInput.setOnEditorActionListener { _, actionId, _ ->
            (actionId == EditorInfo.IME_ACTION_DONE).also { if (it) save() }
        }
        binding.backToLoginButton.setOnClickListener {
            findNavController().popBackStack(R.id.authFragment, false)
        }

        observeViewModel()
    }

    /**
     * Attaches the clear-error-on-edit listeners after Android has restored the typed text, so a
     * rotation does not wipe the errors — the same reasoning as in AuthFragment.
     */
    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)
        binding.newPasswordInput.doOnTextChanged { _, _, _, _ -> viewModel.clearPasswordError() }
        binding.confirmPasswordInput.doOnTextChanged { _, _, _, _ -> viewModel.clearConfirmationError() }
    }

    private fun save() {
        viewModel.onSave(
            password = binding.newPasswordInput.textString(),
            confirmation = binding.confirmPasswordInput.textString()
        )
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.errors.collect { errors ->
                        binding.newPasswordLayout.error = errors.password?.let(::getString)
                        binding.confirmPasswordLayout.error = errors.confirmation?.let(::getString)
                    }
                }
                launch {
                    viewModel.events.collect { event ->
                        when (event) {
                            is NewPasswordEvent.Message ->
                                Snackbar.make(binding.root, event.messageRes, Snackbar.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
