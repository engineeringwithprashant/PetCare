package com.prash.petcare.ui.auth

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.google.android.material.snackbar.Snackbar
import com.prash.petcare.R
import com.prash.petcare.databinding.FragmentRegisterBinding
import com.prash.petcare.ui.home.HomeFragment
import kotlinx.coroutines.launch

/**
 * Account creation screen.
 *
 * Mirrors [LoginFragment] — it renders whatever [AuthViewModel] reports and forwards user input,
 * holding no validation or database logic itself. See [LoginFragment] for the detailed notes on
 * the ViewBinding lifecycle and the flow-collection pattern, which are identical here.
 */
class RegisterFragment : Fragment() {

    /** Nulled in [onDestroyView] to avoid leaking the destroyed view hierarchy. */
    private var _binding: FragmentRegisterBinding? = null
    private val binding get() = _binding!!

    private val viewModel: AuthViewModel by viewModels { AuthViewModel.Factory }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRegisterBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpHelperText()
        setUpInputListeners()
        observeViewModel()
    }

    /**
     * Fills in the password rule shown under the field.
     *
     * Built here from [AuthViewModel.MIN_PASSWORD_LENGTH] rather than typed into the layout, so
     * the rule shown to the user can never drift out of step with the rule actually enforced.
     */
    private fun setUpHelperText() {
        // getQuantityString picks the right plural form for the device's language. The first
        // argument selects the form; the one after it fills the placeholder.
        binding.passwordInputLayout.helperText = resources.getQuantityString(
            R.plurals.register_password_helper,
            AuthViewModel.MIN_PASSWORD_LENGTH,
            AuthViewModel.MIN_PASSWORD_LENGTH
        )
    }

    /** Wires the buttons and text fields to the ViewModel. */
    private fun setUpInputListeners() {
        binding.registerButton.setOnClickListener {
            viewModel.register(
                fullName = binding.fullNameInput.text?.toString().orEmpty(),
                email = binding.emailInput.text?.toString().orEmpty(),
                password = binding.passwordInput.text?.toString().orEmpty(),
                confirmPassword = binding.confirmPasswordInput.text?.toString().orEmpty()
            )
        }

        binding.goToLoginButton.setOnClickListener {
            // This screen is only ever reached from the login screen, so going back up the stack
            // returns there — no separate navigation action is needed, and it avoids stacking a
            // second copy of the login screen on top of the first.
            findNavController().navigateUp()
        }

        // Clear stale validation messages as the user corrects each field.
        binding.fullNameInput.doOnTextChanged { _, _, _, _ -> viewModel.clearFieldErrors() }
        binding.emailInput.doOnTextChanged { _, _, _, _ -> viewModel.clearFieldErrors() }
        binding.passwordInput.doOnTextChanged { _, _, _, _ -> viewModel.clearFieldErrors() }
        binding.confirmPasswordInput.doOnTextChanged { _, _, _, _ -> viewModel.clearFieldErrors() }
    }

    /** Collects state and events for as long as the screen is visible. */
    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.formState.collect { state -> render(state) }
                }
                launch {
                    viewModel.events.collect { event -> handleEvent(event) }
                }
            }
        }
    }

    /** Applies the current form state to the views. */
    private fun render(state: AuthFormState) {
        binding.fullNameInputLayout.error = state.fullNameError?.let { getString(it) }
        binding.emailInputLayout.error = state.emailError?.let { getString(it) }
        binding.passwordInputLayout.error = state.passwordError?.let { getString(it) }
        binding.confirmPasswordInputLayout.error =
            state.confirmPasswordError?.let { getString(it) }

        binding.progressIndicator.visibility = if (state.isSubmitting) View.VISIBLE else View.GONE

        // Prevents a double tap creating two accounts while the first insert is still running.
        binding.registerButton.isEnabled = !state.isSubmitting
        binding.goToLoginButton.isEnabled = !state.isSubmitting
    }

    /** Reacts to a one-off event from the ViewModel. */
    private fun handleEvent(event: AuthEvent) {
        when (event) {
            is AuthEvent.Authenticated -> {
                // A new account is signed in straight away, so registration lands on the same
                // destination as a successful login.
                findNavController().navigate(
                    R.id.action_register_to_home,
                    bundleOf(HomeFragment.ARG_USER_ID to event.userId)
                )
            }

            is AuthEvent.Error -> {
                Snackbar.make(binding.root, getString(event.messageRes), Snackbar.LENGTH_LONG)
                    .show()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
