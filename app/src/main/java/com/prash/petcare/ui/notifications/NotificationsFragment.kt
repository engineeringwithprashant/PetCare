package com.prash.petcare.ui.notifications

import com.prash.petcare.data.alert.Alert
import com.prash.petcare.data.alert.AlertTarget
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.prash.petcare.R
import com.prash.petcare.databinding.FragmentNotificationsBinding
import com.prash.petcare.ui.common.bindHeaderFromSession
import com.prash.petcare.ui.common.openProfile
import com.prash.petcare.ui.common.setUp
import com.prash.petcare.ui.common.showAssistantPill
import com.prash.petcare.ui.pet.PetDetailFragment
import com.prash.petcare.ui.session.SessionViewModel
import kotlinx.coroutines.launch

/** The notifications screen: care coming up, care that was missed, recent activity, milestones and tips. */
class NotificationsFragment : Fragment() {

    /** ViewBinding for fragment_notifications.xml. */
    private var _binding: FragmentNotificationsBinding? = null
    private val binding get() = _binding!!

    /** Which account is signed in. */
    private val sessionViewModel: SessionViewModel by activityViewModels { SessionViewModel.Factory }

    private val viewModel: NotificationsViewModel by viewModels {
        NotificationsViewModel.factory(sessionViewModel.userId.value)
    }

    /** The list's adapter. */
    private var alertAdapter: NotificationsAdapter? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentNotificationsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // The shared header, with no action on the badge: the badge's job everywhere else is to open this screen.
        binding.header.setUp(
            title = R.string.nav_notifications,
            onBadgeClick = null,
            onMonogramClick = ::openProfile
        )

        // Keeps the header's mark showing the user's profile photo, falling back to the app's monogram.
        bindHeaderFromSession(binding.header, sessionViewModel)
        binding.header.showAssistantPill(::openAssistant)

        setUpRecyclerView()
        binding.markReadButton.setOnClickListener { viewModel.markAllRead() }
        observeViewModel()
    }

    /** Prepares the list. */
    private fun setUpRecyclerView() {
        val adapter = NotificationsAdapter(onAlertClick = ::openAlert)
        alertAdapter = adapter

        binding.alertsRecyclerView.apply {
            // The layout manager decides how items are positioned; a vertical linear list here.
            layoutManager = LinearLayoutManager(requireContext())
            this.adapter = adapter
        }
    }

    /** Handles a tap on an alert: marks it read, then opens what it is about. */
    private fun openAlert(alert: Alert) {
        viewModel.markRead(alert.id)

        when (val target = alert.target) {
            is AlertTarget.PetDetail -> findNavController().navigate(
                R.id.action_notifications_to_pet_detail,
                bundleOf(PetDetailFragment.ARG_PET_ID to target.petId)
            )

            // The Tasks tab as the bottom bar shows it — the action passes no petId.
            AlertTarget.Tasks -> findNavController().navigate(R.id.action_notifications_to_checklist)
        }
    }

    /** Opens the assistant, from the header's AI pill. */
    private fun openAssistant() {
        findNavController().navigate(R.id.action_notifications_to_assistant)
    }

    /** Subscribes to the ViewModel's state. */
    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state -> render(state) }
            }
        }
    }

    /** Applies the current state to the views. */
    private fun render(state: NotificationsUiState) {

        binding.alertsHeadlineText.text = headlineFor(state.unreadCount)

        // Hidden rather than disabled when everything has been read.
        binding.markReadButton.isVisible = state.unreadCount > 0

        // submitList hands the whole new list to DiffUtil, which works out what changed and animates only those rows.
        alertAdapter?.submitList(state.items)

        // The list and the empty state occupy the same space, so exactly one is ever visible.
        binding.alertsRecyclerView.isVisible = state.items.isNotEmpty()
        binding.emptyAlertsContainer.isVisible = state.hasNoAlerts
    }

    /** The headline: "3 new alerts", or "You're all caught up" once nothing is unread. */
    private fun headlineFor(unreadCount: Int): String =
        if (unreadCount == 0) {
            getString(R.string.notifications_all_caught_up)
        } else {
            resources.getQuantityString(R.plurals.notifications_new_alerts, unreadCount, unreadCount)
        }

    override fun onDestroyView() {
        super.onDestroyView()
        // The adapter outlives the view it was drawing into, so it is detached here.
        binding.alertsRecyclerView.adapter = null
        alertAdapter = null

        // Release the reference to the destroyed view hierarchy — see the note on [_binding].
        _binding = null
    }
}
