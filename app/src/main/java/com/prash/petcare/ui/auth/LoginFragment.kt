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
import com.prash.petcare.databinding.FragmentLoginBinding
import com.prash.petcare.ui.home.HomeFragment
import kotlinx.coroutines.launch

/**
 * Sign-in screen.
 *
 * In MVVM this fragment is the **View**: it displays what [AuthViewModel] tells it to display and
 * forwards taps and typing back. It contains no validation and no database access of its own —
 * putting either here would tie the logic to a screen that is destroyed on every rotation.
 */
class LoginFragment : Fragment() {

    /**
     * The ViewBinding for fragment_login.xml, giving type-safe access to every view with an id.
     *
     * Held as a nullable backing field because a fragment's *view* can be destroyed while the
     * fragment itself lives on — for example when it is put on the back stack. Keeping the
     * binding past that point would leak the entire destroyed view hierarchy, so it is cleared
     * in [onDestroyView].
     */
    private var _binding: FragmentLoginBinding? = null

    /**
     * Convenience accessor for [_binding], only valid between [onCreateView] and [onDestroyView].
     * Using it outside that window is a programming error, which the non-null assertion surfaces
     * immediately rather than failing silently later.
     */
    private val binding get() = _binding!!

    /**
     * Obtains the shared [AuthViewModel].
     *
     * `by viewModels` scopes it to this fragment, so the instance — and any sign-in already in
     * progress — survives a screen rotation instead of restarting.
     */
    private val viewModel: AuthViewModel by viewModels { AuthViewModel.Factory }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLoginBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpInputListeners()
        observeViewModel()
    }

    /** Wires the buttons and text fields to the ViewModel. */
    private fun setUpInputListeners() {
        binding.loginButton.setOnClickListener {
            // The fragment passes the raw text straight through; deciding whether it is valid is
            // the ViewModel's job, not this screen's.
            viewModel.login(
                email = binding.emailInput.text?.toString().orEmpty(),
                password = binding.passwordInput.text?.toString().orEmpty()
            )
        }

        binding.goToRegisterButton.setOnClickListener {
            // Navigating by action id rather than naming a destination keeps the route itself
            // defined in nav_graph.xml, where the whole flow can be read in one place.
            findNavController().navigate(R.id.action_login_to_register)
        }

        // Clear a validation message as soon as the user starts correcting the field, so a stale
        // error never sits under text they have already fixed.
        binding.emailInput.doOnTextChanged { _, _, _, _ -> viewModel.clearFieldErrors() }
        binding.passwordInput.doOnTextChanged { _, _, _, _ -> viewModel.clearFieldErrors() }
    }

    /**
     * Subscribes to the ViewModel's state and events.
     *
     * `repeatOnLifecycle(STARTED)` starts collecting when the screen becomes visible and cancels
     * automatically when it stops. That is what stops the fragment doing work while in the
     * background, and stops it touching views after they have been destroyed.
     *
     * `viewLifecycleOwner` is used rather than the fragment itself because the view has the
     * shorter life of the two, and the views being updated here belong to it.
     */
    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Two independent collectors, each in its own coroutine: collect() never returns,
                // so calling them one after another in a single coroutine would mean the second
                // never ran.
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
        // Resolving the string resource here, in the View layer, is what keeps Context out of the
        // ViewModel. A null error id clears the message rather than showing empty red text.
        binding.emailInputLayout.error = state.emailError?.let { getString(it) }
        binding.passwordInputLayout.error = state.passwordError?.let { getString(it) }

        binding.progressIndicator.visibility = if (state.isSubmitting) View.VISIBLE else View.GONE

        // Disabling the button while a sign-in is running stops an impatient double tap starting
        // a second check.
        binding.loginButton.isEnabled = !state.isSubmitting
        binding.goToRegisterButton.isEnabled = !state.isSubmitting
    }

    /** Reacts to a one-off event from the ViewModel. */
    private fun handleEvent(event: AuthEvent) {
        when (event) {
            is AuthEvent.Authenticated -> {
                // Pass the signed-in account's id on to the home screen, matching the `userId`
                // argument declared for that destination in nav_graph.xml.
                findNavController().navigate(
                    R.id.action_login_to_home,
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
        // Releases the reference to the destroyed view hierarchy — see the note on [_binding].
        _binding = null
    }
}
