package com.prash.petcare.ui.auth.twofactor

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
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
 * *not* signed in until this screen says so — see [TwoFactorViewModel], which does the checking.
 */
class TwoFactorFragment : Fragment() {

    private var _binding: FragmentTwoFactorBinding? = null

    /** Valid only between [onCreateView] and [onDestroyView]; see AuthFragment for why. */
    private val binding get() = _binding!!

    /**
     * Built by the factory that supplies the two-factor repository and the shared attempt store. The
     * factory's `createSavedStateHandle()` still carries this fragment's arguments, which is how the
     * ViewModel reads [ARG_USER_ID].
     */
    private val viewModel: TwoFactorViewModel by viewModels { TwoFactorViewModel.Factory }

    private val sessionViewModel: SessionViewModel by activityViewModels { SessionViewModel.Factory }

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
                // A recovery code was spent to get in. Said with a Toast rather than a Snackbar for
                // one reason: a Snackbar belongs to the view it is shown on, and this view is about
                // to be destroyed by the navigation on the next line, so it would flash and vanish.
                // A Toast outlives the screen and is still readable on the dashboard, which is where
                // the user will be by the time they read it.
                event.recoveryCodesRemaining?.let { remaining ->
                    Toast.makeText(
                        requireContext(),
                        resources.getQuantityString(
                            R.plurals.two_factor_recovery_used,
                            remaining,
                            remaining
                        ),
                        Toast.LENGTH_LONG
                    ).show()
                }

                // Only now is the account signed in — recorded before navigating, for the same reason
                // as in AuthFragment.
                sessionViewModel.signIn(event.userId)
                findNavController().navigate(R.id.action_two_factor_to_home)
            }

            is TwoFactorEvent.OpenRecovery -> findNavController().navigate(
                R.id.action_two_factor_to_recovery_code,
                bundleOf(RecoveryCodeFragment.ARG_USER_ID to event.userId)
            )
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {

        /**
         * Navigation argument: the id of the account whose second factor is being checked.
         *
         * Aliased from [TwoFactorCheckViewModel.ARG_USER_ID] rather than spelled again, because the
         * ViewModel reads the argument out of the SavedStateHandle by that name — two literals that
         * had to match would be one rename away from a screen that silently checks nobody's account.
         */
        const val ARG_USER_ID = TwoFactorCheckViewModel.ARG_USER_ID
    }
}
