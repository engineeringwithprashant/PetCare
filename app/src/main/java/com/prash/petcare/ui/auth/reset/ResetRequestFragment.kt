package com.prash.petcare.ui.auth.reset

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
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
import com.prash.petcare.databinding.FragmentResetRequestBinding
import com.prash.petcare.ui.auth.show
import kotlinx.coroutines.launch

/** The password reset screen: type the account's email address and ask for a reset link. */
class ResetRequestFragment : Fragment() {

    private var _binding: FragmentResetRequestBinding? = null

    /** Valid only between [onCreateView] and [onDestroyView]; see AuthFragment for why. */
    private val binding get() = _binding!!

    /** Built by the factory that supplies the password-reset repository. */
    private val viewModel: ResetRequestViewModel by viewModels { ResetRequestViewModel.Factory }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentResetRequestBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.header.show(R.string.reset_request_title, getString(R.string.reset_request_subtitle))

        bindEmailField()
        binding.sendLinkButton.setOnClickListener { viewModel.onSendLink() }

        // The log in screen is directly below this one on the back stack, so going back is a pop.
        binding.backToLoginButton.setOnClickListener {
            findNavController().popBackStack(R.id.authFragment, false)
        }

        observeViewModel()
    }

    /** Sends the field's typing and "finished" moments to the ViewModel. */
    private fun bindEmailField() {
        val input = binding.accountEmailInput
        input.doOnTextChanged { text, _, _, _ ->
            viewModel.onEmailChanged(text?.toString().orEmpty())
        }
        input.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) viewModel.onEmailFinished()
        }
        input.setOnEditorActionListener { _, actionId, _ ->
            (actionId == EditorInfo.IME_ACTION_DONE).also { isDone ->
                if (isDone) {
                    viewModel.onEmailFinished()
                    viewModel.onSendLink()
                }
            }
        }
    }

    /** Collects the state and events only while the screen is visible. */
    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.uiState.collect(::render) }
                launch {
                    viewModel.events.collect { event ->
                        when (event) {
                            is ResetRequestEvent.LinkSent -> findNavController().navigate(
                                R.id.action_reset_request_to_reset_sent,
                                bundleOf(ResetSentFragment.ARG_EMAIL to event.email)
                            )
                        }
                    }
                }
            }
        }
    }

    /** Applies the current state to the views. */
    private fun render(state: ResetRequestUiState) {
        // Resource ids become text here, in the View layer, so the ViewModel needs no Context.
        binding.accountEmailLayout.error = state.emailError?.let(::getString)

        // The charcoal disabled look comes from the button's colour state list.
        binding.sendLinkButton.isEnabled = state.canSend
        binding.sendingIndicator.isVisible = state.isSending

        binding.failureText.isVisible = state.failure != null
        binding.failureText.text = state.failure?.let(::getString)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
