package com.prash.petcare.ui.auth.reset

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.prash.petcare.R
import com.prash.petcare.databinding.FragmentResetSentBinding
import com.prash.petcare.ui.auth.keepSystemKeyboardClosed
import com.prash.petcare.ui.auth.show
import kotlinx.coroutines.launch

/** The reset confirmation screen: a link has been sent to [ARG_EMAIL]. */
class ResetSentFragment : Fragment() {

    private var _binding: FragmentResetSentBinding? = null

    /** Valid only between [onCreateView] and [onDestroyView]; see AuthFragment for why. */
    private val binding get() = _binding!!

    /** Built by a factory that supplies the repository and the navigation arguments. */
    private val viewModel: ResetSentViewModel by viewModels { ResetSentViewModel.Factory }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentResetSentBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // This screen has no text field.
        keepSystemKeyboardClosed()

        binding.header.show(
            R.string.reset_sent_title,
            getString(R.string.reset_sent_message, viewModel.email)
        )

        binding.resendButton.setOnClickListener { viewModel.onResend() }

        // Pops both reset screens, back to the log in screen still on the back stack.
        binding.backToLoginButton.setOnClickListener {
            findNavController().popBackStack(R.id.authFragment, false)
        }

        observeViewModel()
    }

    /** Redraws the screen on every state change while it is visible. */
    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect(::render)
            }
        }
    }

    private fun render(state: ResetSentUiState) {
        val seconds = state.secondsUntilResend
        binding.resendButton.isEnabled = state.canResend
        // "RESEND" once the cooldown has ended, "RESEND IN 0:42" while it runs.
        binding.resendButton.text = if (seconds > 0) {
            getString(R.string.reset_sent_resend_wait, seconds / 60, seconds % 60)
        } else {
            getString(R.string.reset_sent_resend)
        }

        binding.sendingIndicator.isVisible = state.isSending

        // Two TextViews with different styles, rather than one whose colour is changed in code.
        val message = state.notice?.let(::getString)
        binding.resentNote.isVisible = message != null && !state.noticeIsError
        binding.resentNote.text = message
        binding.failureText.isVisible = message != null && state.noticeIsError
        binding.failureText.text = message
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        /** Navigation argument: the address the link was sent to, as the user typed it. */
        const val ARG_EMAIL = "email"
    }
}
