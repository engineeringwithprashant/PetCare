package com.prash.petcare.ui.auth.twofactor

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.prash.petcare.R
import com.prash.petcare.databinding.FragmentRecoveryCodesBinding
import com.prash.petcare.databinding.ItemRecoveryCodeBinding
import com.prash.petcare.ui.session.SessionViewModel
import kotlinx.coroutines.launch

/** The recovery codes, shown once: ten single-use codes, a warning, a tick and a finish button. */
class RecoveryCodesFragment : Fragment() {

    private var _binding: FragmentRecoveryCodesBinding? = null
    private val binding get() = _binding!!

    private val sessionViewModel: SessionViewModel by activityViewModels { SessionViewModel.Factory }

    private val viewModel: RecoveryCodesViewModel by viewModels {
        RecoveryCodesViewModel.factory(sessionViewModel.userId.value)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRecoveryCodesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // isPressed tells a real tap from this screen writing the restored value back into the box.
        binding.savedCheckbox.setOnCheckedChangeListener { button, isChecked ->
            if (button.isPressed) viewModel.onConfirmedSavedChanged(isChecked)
        }

        binding.finishButton.setOnClickListener { viewModel.onFinish() }

        interceptBack()
        observeViewModel()
    }

    /** Asks before leaving with the codes unsaved. */
    private fun interceptBack() {
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (viewModel.uiState.value.hasConfirmedSaved) {
                        leaveWithoutEnabling()
                        return
                    }
                    confirmLeaving()
                }
            }
        )
    }

    /** The "are you sure?" for walking away from the codes. Cancel is first and does nothing. */
    private fun confirmLeaving() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.recovery_codes_leave_title)
            .setMessage(R.string.recovery_codes_leave_message)
            .setNegativeButton(R.string.recovery_codes_leave_stay, null)
            .setPositiveButton(R.string.recovery_codes_leave_confirm) { _, _ -> leaveWithoutEnabling() }
            .show()
    }

    /** Abandons setup and returns to the profile screen. */
    private fun leaveWithoutEnabling() {
        findNavController().popBackStack(R.id.profileFragment, false)
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.uiState.collect(::render) }
                launch { viewModel.events.collect(::handleEvent) }
            }
        }
    }

    private fun render(state: RecoveryCodesUiState) {
        binding.savedCheckbox.isChecked = state.hasConfirmedSaved
        binding.finishButton.isEnabled = state.canFinish

        renderCodes(state.codes)
    }

    /** Fills the two columns of codes. */
    private fun renderCodes(codes: List<String>) {
        binding.codesLeftColumn.removeAllViews()
        binding.codesRightColumn.removeAllViews()
        if (codes.isEmpty()) return

        val inflater = LayoutInflater.from(requireContext())
        val perColumn = (codes.size + 1) / 2

        codes.forEachIndexed { index, code ->
            val column =
                if (index < perColumn) binding.codesLeftColumn else binding.codesRightColumn

            val row = ItemRecoveryCodeBinding.inflate(inflater, column, false)
            // Numbered from one.
            row.codeIndex.text = getString(R.string.recovery_codes_index, index + 1)
            row.codeValue.text = code
            column.addView(row.root)
        }
    }

    private fun handleEvent(event: RecoveryCodesEvent) {
        when (event) {
            // Back to the profile screen, whose two-factor row will already be reading.
            is RecoveryCodesEvent.Enabled -> {
                findNavController().popBackStack(R.id.profileFragment, false)
            }

            is RecoveryCodesEvent.Failed -> Snackbar.make(
                binding.root,
                R.string.two_factor_setup_failed,
                Snackbar.LENGTH_LONG
            ).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {

        /** Navigation argument: the authenticator secret proved on the setup screen. */
        const val ARG_SECRET = "secret"

        /** Navigation argument: the ten codes, in plain text, as a `String[]`. */
        const val ARG_CODES = "codes"
    }
}
