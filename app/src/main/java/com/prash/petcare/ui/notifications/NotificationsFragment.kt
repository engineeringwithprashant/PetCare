package com.prash.petcare.ui.notifications

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
import com.prash.petcare.ui.common.bindHeaderProfilePhoto
import com.prash.petcare.ui.common.openProfile
import com.prash.petcare.ui.common.setUp
import com.prash.petcare.ui.common.showAssistantPill
import com.prash.petcare.ui.common.showPetCount
import com.prash.petcare.ui.pet.PetDetailFragment
import com.prash.petcare.ui.session.SessionViewModel
import kotlinx.coroutines.launch

/**
 * The notifications screen: care coming up, care that was missed, recent activity, milestones and
 * tips — newest first, grouped into today and earlier.
 *
 * Reached from the pet-count badge in the header every redesigned screen shares, rather than from the
 * bottom bar. That is deliberate: alerts are something you go and check, not a section of the app you
 * live in. Because no bottom-navigation item shares this destination's id, NavigationUI leaves
 * whichever tab the user came from highlighted while it is open — so opening and closing this screen
 * cannot move them between tabs.
 *
 * In MVVM this is the **View**: it renders whatever [NotificationsViewModel] publishes and forwards
 * taps back, deriving nothing and querying nothing itself. The two things it works out are the
 * headline's wording, which needs string resources and plural rules a ViewModel must not hold a
 * Context to reach, and which screen a given [AlertTarget] means — navigation ids belong to the graph
 * and so to the View.
 */
class NotificationsFragment : Fragment() {

    /**
     * ViewBinding for fragment_notifications.xml.
     *
     * Nullable because a fragment's view can be destroyed while the fragment itself lives on; holding
     * the binding past that point would leak the whole view hierarchy. Cleared in [onDestroyView].
     */
    private var _binding: FragmentNotificationsBinding? = null
    private val binding get() = _binding!!

    /**
     * Which account is signed in.
     *
     * Read from the Activity-wide session rather than from a navigation argument: this screen is
     * opened by destination id from seven different places, none of which could attach one. See
     * [SessionViewModel].
     */
    private val sessionViewModel: SessionViewModel by activityViewModels { SessionViewModel.Factory }

    private val viewModel: NotificationsViewModel by viewModels {
        NotificationsViewModel.factory(sessionViewModel.userId.value)
    }

    /**
     * The list's adapter.
     *
     * Nullable for the same reason [_binding] is: it belongs to the view, not to the fragment, and
     * only exists between [onViewCreated] and [onDestroyView].
     */
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

        // The shared header, with no action on the badge: the badge's job everywhere else is to open
        // this screen, and a control that reopens the screen already being looked at is a dead end.
        // The same reasoning hides the "Profile" item from the overflow menu while the profile screen
        // is open — see MainActivity.onPrepareOptionsMenu.
        binding.header.setUp(
            title = R.string.nav_notifications,
            onBadgeClick = null,
            onMonogramClick = ::openProfile
        )

        // Keeps the header's mark showing the user's profile photo, falling back to the app's
        // monogram when there is none. See TabHeader.bindHeaderProfilePhoto.
        bindHeaderProfilePhoto(binding.header, sessionViewModel)
        binding.header.showAssistantPill(::openAssistant)

        setUpRecyclerView()
        binding.markReadButton.setOnClickListener { viewModel.markAllRead() }
        observeViewModel()
    }

    /**
     * Prepares the list.
     *
     * Deliberately not `setHasFixedSize(true)`: an alert's card is one line tall or two depending on
     * how long its sentence turns out to be, and the group headings come and go as alerts age out.
     */
    private fun setUpRecyclerView() {
        val adapter = NotificationsAdapter(onAlertClick = ::openAlert)
        alertAdapter = adapter

        binding.alertsRecyclerView.apply {
            // The layout manager decides how items are positioned; a vertical linear list here.
            layoutManager = LinearLayoutManager(requireContext())
            this.adapter = adapter
        }
    }

    /**
     * Handles a tap on an alert: marks it read, then opens what it is about.
     *
     * Both, in that order, and neither instead of the other. Opening without marking would leave the
     * card yellow after the user had plainly seen it; marking without opening would make the tap a
     * dismissal, which is not what a notification's tap means anywhere else on the phone.
     *
     * The read state is written through the ViewModel rather than here, so the View never touches a
     * data source directly — the same rule that keeps it away from the DAOs.
     */
    private fun openAlert(alert: Alert) {
        viewModel.markRead(alert.id)

        when (val target = alert.target) {
            is AlertTarget.PetDetail -> findNavController().navigate(
                R.id.action_notifications_to_pet_detail,
                bundleOf(PetDetailFragment.ARG_PET_ID to target.petId)
            )

            // The Tasks tab as the bottom bar shows it — the action passes no petId, which is what
            // tells that screen to offer the pet chips rather than scope itself to one animal. See
            // the note at the top of nav_graph.xml.
            AlertTarget.Tasks -> findNavController().navigate(R.id.action_notifications_to_checklist)
        }
    }

    /** Opens the assistant, from the header's AI pill. */
    private fun openAssistant() {
        findNavController().navigate(R.id.action_notifications_to_assistant)
    }

    /**
     * Subscribes to the ViewModel's state.
     *
     * `repeatOnLifecycle(STARTED)` begins collecting when the screen becomes visible and cancels when
     * it stops, so the fragment never does work in the background or touches views after they have
     * been destroyed. `viewLifecycleOwner` is used because the view has a shorter life than the
     * fragment, and these are the view's updates.
     */
    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state -> render(state) }
            }
        }
    }

    /** Applies the current state to the views. */
    private fun render(state: NotificationsUiState) {
        binding.header.showPetCount(state.petCount)

        binding.alertsHeadlineText.text = headlineFor(state.unreadCount)

        // Hidden rather than disabled when everything has been read. A greyed-out button still asks
        // to be understood — the user has to work out why it cannot be pressed — whereas its absence
        // beside "You're all caught up" says the same thing without being read at all. It returns the
        // moment a new alert arrives.
        binding.markReadButton.isVisible = state.unreadCount > 0

        // submitList hands the whole new list to DiffUtil, which works out what changed and animates
        // only those rows.
        alertAdapter?.submitList(state.items)

        // The list and the empty state occupy the same space, so exactly one is ever visible. Both
        // stay hidden during the initial load, which stops "Nothing needs you" flashing up before the
        // first query returns.
        binding.alertsRecyclerView.isVisible = state.items.isNotEmpty()
        binding.emptyAlertsContainer.isVisible = state.hasNoAlerts
    }

    /**
     * The headline: "3 new alerts", or "You're all caught up" once nothing is unread.
     *
     * The plurals resource chooses "alert" or "alerts" from the count itself, so the grammar is right
     * in either case and in any language that supplies its own plural forms. Zero is worded as a state
     * rather than as "0 new alerts", which reads like something failed to load.
     */
    private fun headlineFor(unreadCount: Int): String =
        if (unreadCount == 0) {
            getString(R.string.notifications_all_caught_up)
        } else {
            resources.getQuantityString(R.plurals.notifications_new_alerts, unreadCount, unreadCount)
        }

    override fun onDestroyView() {
        super.onDestroyView()
        // The adapter outlives the view it was drawing into, so it is detached here. Without this a
        // RecyclerView keeps a reference to its adapter and the adapter to its holders, and the whole
        // destroyed hierarchy is held alive behind them.
        binding.alertsRecyclerView.adapter = null
        alertAdapter = null

        // Release the reference to the destroyed view hierarchy — see the note on [_binding].
        _binding = null
    }
}
