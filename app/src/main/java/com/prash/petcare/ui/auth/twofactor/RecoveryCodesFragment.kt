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

/**
 * The recovery codes, shown once: ten single-use codes, a warning, a tick and a finish button.
 *
 * The last step of setting two-factor authentication up, reached from [TwoFactorSetupFragment] once
 * a code from the authenticator has been typed back correctly. The **View** in MVVM: it draws the
 * codes [RecoveryCodesViewModel] was given, reports the tick, and leaves when the ViewModel says the
 * account is protected.
 *
 * **"Once" is literal.** The database stores only hashes — see
 * [com.prash.petcare.data.local.entity.RecoveryCode] — so there is no screen anywhere in the app that
 * can show these again, and this one cannot either once it is left. That is why the finish button
 * waits on a tick and why leaving early asks first: both are the app taking seriously that this is
 * the user's only chance to write them down.
 *
 * **Why the rows are inflated rather than put in a RecyclerView.** There are exactly ten, they never
 * change, and nothing scrolls past them — the same reasoning the profile screen's contacts card
 * gives. A RecyclerView would add an adapter, a view holder and a diff for a list that is fixed in
 * length and lifetime.
 */
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

        // isPressed tells a real tap from this screen writing the restored value back into the box,
        // exactly as the profile screen's switches do — without it, rendering state would look like
        // the user having ticked it.
        binding.savedCheckbox.setOnCheckedChangeListener { button, isChecked ->
            if (button.isPressed) viewModel.onConfirmedSavedChanged(isChecked)
        }

        binding.finishButton.setOnClickListener { viewModel.onFinish() }

        interceptBack()
        observeViewModel()
    }

    /**
     * Asks before leaving with the codes unsaved.
     *
     * **Why this screen intercepts back when no other in the app does.** Everywhere else, backing out
     * of a form loses some typing. Backing out of here loses ten codes that cannot be shown again, on
     * the one screen whose entire purpose is to hand them over. Leaving is still allowed — it is the
     * user's account and there is a legitimate "actually, not now" — but it is worth one sentence
     * first, and the sentence says what is really at stake: two-factor authentication will simply not
     * be turned on, so nothing is broken and nothing is half-done.
     *
     * Once the tick is in, the dialog stops appearing: the user has said they have the codes, and
     * asking again would be doubting an answer the app just took. Leaving at that point still does
     * not turn two-factor authentication on — only "FINISH SETUP" does — which is the right way
     * round: having the codes and having decided to go ahead are two different statements.
     *
     * `OnBackPressedCallback` on the *view* lifecycle owner, so the interception ends with the view
     * rather than outliving it and swallowing back presses meant for another screen.
     */
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

    /**
     * Abandons setup and returns to the profile screen.
     *
     * Nothing has to be undone. The secret was never stored and the codes were never stored — see
     * [RecoveryCodesViewModel] — so two-factor authentication is exactly as off as it was before the
     * user opened the setup screen. `popBackStack` to the profile destination clears both setup
     * screens at once, so back from the profile does not walk into a half-finished flow.
     */
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

    /**
     * Fills the two columns of codes.
     *
     * The container is emptied and refilled rather than diffed — as on the profile screen's contacts
     * card, and for the same reason: the list is fixed, and the simplest correct thing is also the
     * right one. Odd-numbered rows go in the left column and even in the right, so the codes read
     * **down** each column in the order they are numbered rather than snaking across.
     */
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
            // Numbered from one, so "the third code" means the same thing to the user reading the
            // screen and to the user reading what they wrote down.
            row.codeIndex.text = getString(R.string.recovery_codes_index, index + 1)
            row.codeValue.text = code
            column.addView(row.root)
        }
    }

    private fun handleEvent(event: RecoveryCodesEvent) {
        when (event) {
            // Back to the profile screen, whose two-factor row will already be reading "On" — it
            // observes the same database rows this write just changed.
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
