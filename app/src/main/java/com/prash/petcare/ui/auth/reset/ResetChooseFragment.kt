package com.prash.petcare.ui.auth.reset

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.prash.petcare.R
import com.prash.petcare.databinding.FragmentResetChooseBinding
import com.prash.petcare.ui.auth.show
import com.prash.petcare.ui.auth.showStep
import kotlinx.coroutines.launch

/**
 * Password reset, step 1 of 3: choose whether the code goes to email or by SMS.
 *
 * The **View** in MVVM: it shows the two option cards from [ResetChooseViewModel], marks the chosen one,
 * and forwards taps. The two cards behave as a radio group, but the "only one chosen" rule is simply a
 * consequence of the ViewModel holding one channel — the fragment never has to un-check the other.
 *
 * Sending the code is not connected yet; see the TODO in [ResetChooseViewModel.onSendCode].
 */
class ResetChooseFragment : Fragment() {

    private var _binding: FragmentResetChooseBinding? = null

    /** Valid only between [onCreateView] and [onDestroyView]; see AuthFragment for why. */
    private val binding get() = _binding!!

    /** Built by the default factory, which also puts [ARG_IDENTIFIER] into its SavedStateHandle. */
    private val viewModel: ResetChooseViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentResetChooseBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.header.show(R.string.reset_choose_title, getString(R.string.reset_choose_subtitle))
        binding.stepProgress.showStep(step = 1, total = TOTAL_STEPS)

        // What each card says under its title: the known destination, or a generic line.
        val destinations = viewModel.destinations
        binding.emailDestination.text =
            destinations.email ?: getString(R.string.reset_option_email_unknown)
        binding.smsDestination.text =
            destinations.maskedPhone ?: getString(R.string.reset_option_sms_unknown)

        binding.emailOption.setOnClickListener { viewModel.select(ResetChannel.EMAIL) }
        binding.smsOption.setOnClickListener { viewModel.select(ResetChannel.SMS) }
        binding.sendCodeButton.setOnClickListener { viewModel.onSendCode() }

        // The log in screen is directly beneath this one, so going back to it is a pop, not a new
        // screen — and anything typed there is still there.
        binding.backToLoginButton.setOnClickListener {
            findNavController().popBackStack(R.id.authFragment, false)
        }

        observeViewModel()
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    // `isChecked` is all each card needs: its fill, outline, badge and radio are state
                    // lists keyed on it (see PetCare.Auth.OptionCard in styles.xml).
                    viewModel.selected.collect { channel ->
                        binding.emailOption.isChecked = channel == ResetChannel.EMAIL
                        binding.smsOption.isChecked = channel == ResetChannel.SMS
                    }
                }
                launch {
                    viewModel.events.collect { event ->
                        when (event) {
                            is ResetChooseEvent.CodeSent -> findNavController().navigate(
                                R.id.action_reset_choose_to_reset_code,
                                bundleOf(
                                    ResetCodeFragment.ARG_CHANNEL to event.channel.name,
                                    ResetCodeFragment.ARG_DESTINATION to event.destination
                                )
                            )
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

    companion object {
        /** Navigation argument: what was in the log in screen's "email or phone" field. May be empty. */
        const val ARG_IDENTIFIER = "identifier"

        /** The reset flow has three steps: choose, enter code, new password. */
        const val TOTAL_STEPS = 3
    }
}
