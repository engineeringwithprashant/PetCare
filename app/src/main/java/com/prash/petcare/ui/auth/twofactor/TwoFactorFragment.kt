package com.prash.petcare.ui.auth.twofactor

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
import com.prash.petcare.databinding.FragmentTwoFactorBinding
import com.prash.petcare.ui.auth.CodeKeypadView
import com.prash.petcare.ui.auth.bindCodeEntry
import com.prash.petcare.ui.auth.keepSystemKeyboardClosed
import com.prash.petcare.ui.auth.show
import com.prash.petcare.ui.auth.showStep
import com.prash.petcare.ui.session.SessionViewModel
import kotlinx.coroutines.launch

/**
 * The two-factor check: the second step of logging in, for an account protected by an authenticator.
 *
 * The **View** in MVVM. It forwards key presses from [CodeKeypadView] to [TwoFactorViewModel], draws
 * the code and the verify button's state from it, and navigates when it reports an outcome. It checks
 * no code itself.
 *
 * Reached from the log in screen with the account's id as an argument ([ARG_USER_ID]); the account is
 * *not* signed in until this screen says so. Checking the code is not connected yet — see the TODOs in
 * [TwoFactorViewModel].
 */
class TwoFactorFragment : Fragment() {

    private var _binding: FragmentTwoFactorBinding? = null

    /** Valid only between [onCreateView] and [onDestroyView]; see AuthFragment for why. */
    private val binding get() = _binding!!

    /**
     * No factory needed: a ViewModel whose only constructor parameter is a SavedStateHandle is built
     * by the fragment's default factory, which also copies this fragment's arguments into the handle.
     */
    private val viewModel: TwoFactorViewModel by viewModels()

    private val sessionViewModel: SessionViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTwoFactorBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // The code is typed on the keypad below, never on the system keyboard.
        keepSystemKeyboardClosed()

        binding.header.show(R.string.two_factor_title, getString(R.string.two_factor_subtitle))
        // "STEP 2 OF 2" — the password was step 1. The mockup draws this bar with three segments, all
        // filled, and that is matched exactly.
        binding.stepProgress.showStep(step = 2, total = 2, segments = 3)

        // Keypad, boxes and verify button, wired by the shared bindCodeEntry helper.
        bindCodeEntry(binding.codeKeypad, binding.verifyButton, viewModel)
        binding.lostAccessButton.setOnClickListener { viewModel.onLostAccess() }

        // Back to the log in screen, which is still underneath on the back stack with whatever was
        // typed there. popBackStack rather than navigate, so a second log in screen is not stacked.
        binding.differentAccountButton.setOnClickListener {
            findNavController().popBackStack(R.id.authFragment, false)
        }

        observeViewModel()
    }

    /** Collects this screen's own events; the code and button state are handled by bindCodeEntry. */
    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.events.collect(::handleEvent)
            }
        }
    }

    private fun handleEvent(event: TwoFactorEvent) {
        when (event) {
            is TwoFactorEvent.Verified -> {
                // Only now is the account signed in — recorded before navigating, for the same reason
                // as in AuthFragment.
                sessionViewModel.signIn(event.userId)
                findNavController().navigate(R.id.action_two_factor_to_home)
            }

            is TwoFactorEvent.Message ->
                Snackbar.make(binding.root, event.messageRes, Snackbar.LENGTH_LONG).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        /** Navigation argument: the id of the account whose second factor is being checked. */
        const val ARG_USER_ID = "userId"
    }
}
