package com.prash.petcare.ui.profile

import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.navigation.navOptions
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.prash.petcare.R
import com.prash.petcare.data.sync.SyncStatus
import com.prash.petcare.databinding.FragmentProfileBinding
import com.prash.petcare.ui.common.formatStoredDate
import com.prash.petcare.ui.session.SessionViewModel
import kotlinx.coroutines.launch

/**
 * The profile screen: who is signed in, how their data is backed up, and the way out.
 *
 * Reached from the top app bar's overflow menu rather than from the bottom navigation bar — see the
 * note at the top of res/menu/top_app_bar_menu.xml on why the account actions live there.
 *
 * In MVVM this is the **View**: it renders whatever [ProfileViewModel] publishes. The screen is
 * read-only, so there is nothing to forward back except signing out, which is not a database
 * operation at all.
 */
class ProfileFragment : Fragment() {

    /** Nulled in [onDestroyView] to avoid leaking the destroyed view hierarchy. */
    private var _binding: FragmentProfileBinding? = null
    private val binding get() = _binding!!

    /**
     * Which account is signed in — both the one to display, and the one signing out clears.
     *
     * `by activityViewModels` so this is the Activity's instance, shared with every other screen.
     * See [SessionViewModel].
     */
    private val sessionViewModel: SessionViewModel by activityViewModels()

    private val viewModel: ProfileViewModel by viewModels {
        ProfileViewModel.factory(sessionViewModel.userId.value)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProfileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpClickListeners()
        observeViewModel()
    }

    private fun setUpClickListeners() {
        binding.signOutButton.setOnClickListener { confirmSignOut() }
    }

    /**
     * Subscribes to the ViewModel's state.
     *
     * `repeatOnLifecycle(STARTED)` begins collecting when the screen becomes visible and cancels
     * when it stops, so the fragment never does work in the background or touches views after they
     * have been destroyed.
     */
    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Two collectors in their own coroutines: collect() never returns, so calling the
                // second after the first would mean it never ran.
                launch {
                    viewModel.uiState.collect { state -> render(state) }
                }
                launch {
                    viewModel.syncStatus.collect { status -> renderSyncStatus(status) }
                }
            }
        }
    }

    /** Applies the current state to the views. */
    private fun render(state: ProfileUiState) {
        // Null only for the instant before the first database read returns. The fields are left
        // blank rather than filled with a placeholder, which would flash and be replaced.
        val user = state.user ?: return

        binding.profileNameText.text = user.fullName
        binding.profileEmailText.text = user.email

        // The account's creation date is a real timestamp rather than a picked calendar date, so it
        // is formatted with the same helper the pet screens use — which fixes the formatter to UTC.
        // The difference is at most a few hours on a date shown to the day, and using one formatter
        // means a date reads identically everywhere in the app.
        binding.profileCreatedText.text = formatStoredDate(user.createdAt)
    }

    /**
     * Shows how the backup is getting on.
     *
     * The same wording the dashboard uses, from the same source, deliberately — this is the screen
     * a user opens after glimpsing that line, and a second differently-worded answer would invite
     * the question of which one is right.
     *
     * Hidden entirely in the idle state, before any sync has had a chance to run. An empty line
     * under the explanation would read as something that had failed to load.
     */
    private fun renderSyncStatus(status: SyncStatus) {
        val text = when (status) {
            is SyncStatus.Idle -> null

            is SyncStatus.Syncing -> getString(R.string.sync_status_syncing)

            // getQuantityString picks the right plural form for the device's language. The first
            // argument selects the form; the second fills the placeholder.
            is SyncStatus.Pending -> resources.getQuantityString(
                R.plurals.sync_status_pending,
                status.changeCount,
                status.changeCount
            )

            is SyncStatus.Synced -> getString(
                R.string.sync_status_synced,
                DateUtils.getRelativeTimeSpanString(
                    status.atMillis,
                    System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS
                )
            )

            is SyncStatus.Offline -> getString(R.string.sync_status_offline)
        }

        binding.profileSyncStatusText.text = text.orEmpty()
        binding.profileSyncStatusText.isVisible = text != null
    }

    /**
     * Asks before signing out.
     *
     * A dialog because the action is not reversible from where the user is standing: the way back is
     * typing a password. The message says what is *not* lost, since that is the actual worry at this
     * point — an app that keeps everything locally could plausibly be discarding it.
     *
     * Cancel is listed first and does nothing, so the safe choice is the easy one.
     */
    private fun confirmSignOut() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.profile_sign_out_dialog_title)
            .setMessage(R.string.profile_sign_out_dialog_message)
            .setNegativeButton(R.string.profile_sign_out_dialog_cancel, null)
            .setPositiveButton(R.string.profile_sign_out_dialog_confirm) { _, _ -> signOut() }
            .show()
    }

    /**
     * Clears the session and returns to the sign-in screen.
     *
     * The same two steps the app bar's "Sign out" item performs — see MainActivity — because it is
     * the same action offered in two places. `popUpTo` the graph itself with `inclusive` empties the
     * entire back stack on the way, which is what makes signing out final: without it the system
     * back button from the sign-in screen would walk straight back into the account just left.
     *
     * Nothing is deleted. The app keeps no persisted login, so there is no stored credential to
     * clear — signing out is forgetting the id and going back to the front door.
     */
    private fun signOut() {
        sessionViewModel.signOut()

        findNavController().navigate(
            R.id.loginFragment,
            null,
            navOptions { popUpTo(R.id.nav_graph) { inclusive = true } }
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Release the reference to the destroyed view hierarchy — see the note on [_binding].
        _binding = null
    }
}
