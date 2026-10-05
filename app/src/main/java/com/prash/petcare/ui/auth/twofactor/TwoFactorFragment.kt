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

/** The two-factor check: the second step of logging in, for an account protected by an authenticator. */
class TwoFactorFragment : Fragment() {

    private var _binding: FragmentTwoFactorBinding? = null

    /** Valid only between [onCreateView] and [onDestroyView]; see AuthFragment for why. */
    private val binding get() = _binding!!

    /** Built by the factory that supplies the two-factor repository and the shared attempt store. */
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
        // "STEP 2 OF 2" — the password was step 1. The mockup draws this bar with three segments, all filled.
        binding.stepProgress.showStep(step = 2, total = 2, segments = 3)

        // Keypad, boxes and verify button, wired by the shared bindCodeEntry helper.
        bindCodeEntry(binding.codeKeypad, binding.verifyButton, viewModel)
        binding.lostAccessButton.setOnClickListener { viewModel.onLostAccess() }

        // Back to the log in screen, which is still underneath on the back stack with whatever was typed there.
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
                // A recovery code was spent to get in. Said with a Toast rather than a Snackbar for one reason.
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

                // Only now is the account signed in — recorded before navigating.
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

        /** Navigation argument: the id of the account whose second factor is being checked. */
        const val ARG_USER_ID = TwoFactorCheckViewModel.ARG_USER_ID
    }
}
