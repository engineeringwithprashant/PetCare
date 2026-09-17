package com.prash.petcare.ui.auth.reset

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.google.android.material.snackbar.Snackbar
import com.prash.petcare.R
import com.prash.petcare.databinding.FragmentResetCodeBinding
import com.prash.petcare.ui.auth.bindCodeEntry
import com.prash.petcare.ui.auth.keepSystemKeyboardClosed
import com.prash.petcare.ui.auth.show
import com.prash.petcare.ui.auth.showStep
import kotlinx.coroutines.launch

/**
 * Password reset, step 2 of 3: type the six-digit code that was sent.
 *
 * The **View** in MVVM. The code boxes and keypad are the shared [com.prash.petcare.ui.auth.CodeKeypadView],
 * wired to [ResetCodeViewModel] by the same `bindCodeEntry` call the two-factor screen uses, so the
 * two behave identically. This fragment adds only the text around them and the navigation.
 *
 * Reached from step 1 with the channel ([ARG_CHANNEL]) and the destination to name ([ARG_DESTINATION]).
 * Verifying and resending are not connected yet; see the TODOs in [ResetCodeViewModel].
 */
class ResetCodeFragment : Fragment() {

    private var _binding: FragmentResetCodeBinding? = null

    /** Valid only between [onCreateView] and [onDestroyView]; see AuthFragment for why. */
    private val binding get() = _binding!!

    /** Built by the default factory, which copies this fragment's arguments into its handle. */
    private val viewModel: ResetCodeViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentResetCodeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // The code is typed on the keypad, never on the system keyboard.
        keepSystemKeyboardClosed()

        // What step 1's user typed — "+977 98•• ••12" or their email — with "your phone" / "your
        // email" only as a fallback should the argument ever be missing.
        val destination = viewModel.destination ?: getString(
            when (viewModel.channel) {
                ResetChannel.EMAIL -> R.string.reset_code_destination_email
                ResetChannel.SMS -> R.string.reset_code_destination_sms
            }
        )
        val minutes = viewModel.expiryMinutes

        binding.header.show(
            R.string.reset_code_title,
            // getQuantityString picks "minute" or "minutes"; the first `minutes` selects the form,
            // the arguments after it fill the placeholders.
            resources.getQuantityString(R.plurals.reset_code_subtitle, minutes, destination, minutes)
        )
        binding.stepProgress.showStep(step = 2, total = ResetChooseFragment.TOTAL_STEPS)
        binding.sentNote.text = getString(R.string.reset_code_sent_note, destination, minutes)

        bindCodeEntry(binding.codeKeypad, binding.verifyButton, viewModel)
        binding.resendButton.setOnClickListener { viewModel.onResend() }
        binding.backToLoginButton.setOnClickListener {
            // Pops step 1 as well, landing on the log in screen still on the stack beneath.
            findNavController().popBackStack(R.id.authFragment, false)
        }

        observeViewModel()
    }

    /** Collects this screen's own events; the code and button state are handled by bindCodeEntry. */
    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.events.collect { event ->
                    when (event) {
                        // TODO(reset-backend): pass the reset token from verification to step 3 as
                        //  a navigation argument here.
                        ResetCodeEvent.CodeAccepted ->
                            findNavController().navigate(R.id.action_reset_code_to_new_password)

                        is ResetCodeEvent.Message ->
                            Snackbar.make(binding.root, event.messageRes, Snackbar.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        /** Navigation argument: the [ResetChannel] name the code was sent through. */
        const val ARG_CHANNEL = "channel"

        /** Navigation argument: the address (as typed) or masked number to name on screen. */
        const val ARG_DESTINATION = "destination"
    }
}
