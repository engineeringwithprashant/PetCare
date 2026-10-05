package com.prash.petcare.ui.auth.twofactor

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.prash.petcare.R
import com.prash.petcare.databinding.FragmentRecoveryCodeBinding
import com.prash.petcare.ui.auth.CodeKeypadView
import com.prash.petcare.ui.auth.bindCodeEntry
import com.prash.petcare.ui.auth.keepSystemKeyboardClosed
import com.prash.petcare.ui.auth.show
import com.prash.petcare.ui.session.SessionViewModel
import kotlinx.coroutines.launch

/** The recovery-code screen: the way into an account whose authenticator app is gone. */
class RecoveryCodeFragment : Fragment() {

    private var _binding: FragmentRecoveryCodeBinding? = null

    /** Valid only between [onCreateView] and [onDestroyView]; see AuthFragment for why. */
    private val binding get() = _binding!!

    private val viewModel: RecoveryCodeViewModel by viewModels { RecoveryCodeViewModel.Factory }

    private val sessionViewModel: SessionViewModel by activityViewModels { SessionViewModel.Factory }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRecoveryCodeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // The code is typed on the keypad below, never on the system keyboard.
        keepSystemKeyboardClosed()

        binding.header.show(
            R.string.recovery_code_title,
            getString(R.string.recovery_code_subtitle)
        )

        // Keypad, boxes and verify button, wired by the shared bindCodeEntry helper.
        bindCodeEntry(binding.codeKeypad, binding.verifyButton, viewModel)

        // Back to the two-factor screen, which is still underneath on the back stack.
        binding.backToCodeButton.setOnClickListener { findNavController().popBackStack() }

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
                // How many codes are left after this one was spent. Said with a Toast.
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

                // Only now is the account signed in — recorded before navigating, as in AuthFragment.
                sessionViewModel.signIn(event.userId)
                findNavController().navigate(R.id.action_recovery_code_to_home)
            }

            // Raised only by the two-factor screen — this is the recovery screen.
            is TwoFactorEvent.OpenRecovery -> Unit
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {

        /** Navigation argument: the account whose recovery code is being checked. */
        const val ARG_USER_ID = TwoFactorCheckViewModel.ARG_USER_ID
    }
}
