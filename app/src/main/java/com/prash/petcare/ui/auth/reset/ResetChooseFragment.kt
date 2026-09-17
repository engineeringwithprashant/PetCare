package com.prash.petcare.ui.auth.reset

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.core.widget.doOnTextChanged
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
 * Password reset, step 1 of 3: choose whether the code goes to email or by SMS, and type the account's
 * email address or phone number.
 *
 * The **View** in MVVM: it marks the chosen card, shows the matching account field, and forwards taps
 * and typing to [ResetChooseViewModel], which owns every rule — which format the field needs, when an
 * error appears, and when "SEND RESET CODE" is enabled. The two cards behave as a radio group simply
 * because the ViewModel holds one channel; the fragment never has to un-check the other.
 *
 * Sending the code is not connected yet; see the TODO in [ResetChooseViewModel.onSendCode].
 */
class ResetChooseFragment : Fragment() {

    private var _binding: FragmentResetChooseBinding? = null

    /** Valid only between [onCreateView] and [onDestroyView]; see AuthFragment for why. */
    private val binding get() = _binding!!

    /** Built by the default factory, which supplies its SavedStateHandle. */
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

        binding.emailOption.setOnClickListener { viewModel.select(ResetChannel.EMAIL) }
        binding.smsOption.setOnClickListener { viewModel.select(ResetChannel.SMS) }

        bindAccountField(binding.accountEmailInput, ResetChannel.EMAIL)
        bindAccountField(binding.accountPhoneInput, ResetChannel.SMS)

        binding.sendCodeButton.setOnClickListener { viewModel.onSendCode() }

        // The log in screen is directly beneath this one, so going back to it is a pop, not a new
        // screen — and anything typed there is still there.
        binding.backToLoginButton.setOnClickListener {
            findNavController().popBackStack(R.id.authFragment, false)
        }

        observeViewModel()
    }

    /**
     * Forwards one account field's typing and "finished" moments to the ViewModel.
     *
     * The text listener is attached here, *before* Android restores the typed text after a rotation, on
     * purpose: that restore then passes through the listener too, which is how the ViewModel learns the
     * text again after the app was killed in the background — and so how the button comes back enabled.
     * (It is safe here because a text change only ever *clears* an error, never shows one.)
     *
     * "Finished" is either focus leaving the field or the keyboard's "done" key; only then may an error
     * appear under it. "Done" also tries to send, which fails harmlessly with the error shown if the
     * value is not valid yet.
     */
    private fun bindAccountField(input: EditText, channel: ResetChannel) {
        input.doOnTextChanged { text, _, _, _ ->
            viewModel.onDestinationChanged(channel, text?.toString().orEmpty())
        }
        input.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) viewModel.onDestinationFinished(channel)
        }
        input.setOnEditorActionListener { _, actionId, _ ->
            (actionId == EditorInfo.IME_ACTION_DONE).also { isDone ->
                if (isDone) {
                    viewModel.onDestinationFinished(channel)
                    viewModel.onSendCode()
                }
            }
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.uiState.collect(::render) }
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

    /** Applies the current state to the views. */
    private fun render(state: ResetChooseUiState) {
        val isEmail = state.channel == ResetChannel.EMAIL

        // `isChecked` is all each card needs: its fill, outline, badge and radio are state lists keyed
        // on it (see PetCare.Auth.OptionCard in styles.xml).
        binding.emailOption.isChecked = isEmail
        binding.smsOption.isChecked = !isEmail

        // Only the field for the chosen card is shown. The hidden one keeps its text for when the user
        // switches back.
        binding.accountEmailGroup.isVisible = isEmail
        binding.accountPhoneGroup.isVisible = !isEmail

        // Errors resolved to text here, in the View layer, keeping Context out of the ViewModel.
        binding.accountEmailLayout.error = state.emailError?.let(::getString)
        binding.accountPhoneLayout.error = state.phoneError?.let(::getString)

        // The charcoal disabled look comes from the button's colour state list.
        binding.sendCodeButton.isEnabled = state.canSend
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        /** The reset flow has three steps: choose, enter code, new password. */
        const val TOTAL_STEPS = 3
    }
}
