package com.prash.petcare.ui.home

import android.os.Bundle
import android.text.format.DateUtils
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.AttrRes
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.navigation.navOptions
import androidx.recyclerview.widget.LinearLayoutManager
import com.prash.petcare.R
import com.prash.petcare.data.sync.SyncStatus
import com.prash.petcare.databinding.FragmentHomeBinding
import com.prash.petcare.ui.common.setUp
import com.prash.petcare.ui.common.showPetCount
import com.prash.petcare.ui.pet.AddEditPetFragment
import com.prash.petcare.ui.pet.PetDetailFragment
import com.prash.petcare.ui.pet.PetViewModel
import com.prash.petcare.ui.session.SessionViewModel
import kotlinx.coroutines.launch
import java.text.NumberFormat

/**
 * The dashboard: the first of the four bottom-navigation tabs, and the screen the app opens on once
 * signed in.
 *
 * It answers four questions in the order they are asked — whose app is this and what mode am I in,
 * how is today going, who are my animals, and what might I want to do next.
 *
 * In MVVM this is the **View**. It renders whatever [HomeViewModel] publishes and forwards taps
 * back — it performs no queries and calculates nothing itself, which is what allows it to be
 * destroyed and rebuilt on rotation without losing or duplicating work. Even the day's arithmetic
 * — which repeating tasks land on today, and which of them is next — happens in the ViewModel, where
 * it can be tested without an emulator.
 *
 * The pets and the care come from Room, exactly as they did before the app gained a Firestore sync
 * layer. This screen shows how that sync is getting on but never reads anything from it: the
 * database remains the only thing the UI reads, and a pet pulled down from Firestore reaches these
 * cards by being written into Room like any other row.
 *
 * **The one piece of state that is not the ViewModel's.** The header's light/dark toggle is handled
 * in the View layer rather than through [HomeViewModel], because the display mode is neither this
 * account's data nor this screen's — it belongs to the installation, is read before any ViewModel
 * exists, and is applied by AppCompat recreating the Activity. The header itself is shared with the
 * Pets tab; see [com.prash.petcare.ui.common.setUp] in TabHeader.kt.
 */
class HomeFragment : Fragment() {

    /**
     * ViewBinding for fragment_home.xml.
     *
     * Nullable because a fragment's view can be destroyed while the fragment itself lives on;
     * holding the binding past that point would leak the whole view hierarchy. Cleared in
     * [onDestroyView].
     */
    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    /**
     * Which account is signed in.
     *
     * Read from the Activity-wide session rather than from a navigation argument: a bottom
     * navigation bar moves between tabs by destination id and has nowhere to attach one. See
     * [SessionViewModel].
     */
    private val sessionViewModel: SessionViewModel by activityViewModels()

    private val viewModel: HomeViewModel by viewModels {
        HomeViewModel.factory(sessionViewModel.userId.value)
    }

    /**
     * The pet-card adapter, rebuilt with each view rather than held for the fragment's whole life.
     *
     * It captures `viewLifecycleOwner.lifecycleScope` so its photo loads are cancelled when the
     * screen goes away — and a fragment outlives its view, so that scope is a *different object*
     * each time the view is recreated. Navigating to a pet and back destroys and rebuilds this
     * fragment's view; an adapter created once would still be holding the scope from the first
     * view, which is already cancelled, and every photo would silently fail to load.
     *
     * Nullable for the same reason [_binding] is: it only exists between [onViewCreated] and
     * [onDestroyView].
     */
    private var petAdapter: PetSummaryAdapter? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpPetList()
        setUpClickListeners()

        // The shared header: title, theme toggle and badge. See ui/common/TabHeader.kt.
        binding.header.setUp(title = R.string.nav_home, onBadgeClick = ::openProfile)

        observeViewModel()
    }

    /**
     * Prepares the pet list.
     *
     * It does not call `setHasFixedSize(true)`, and should not: the list sits inside the dashboard's
     * NestedScrollView with wrap_content height, so it must be allowed to re-measure as its contents
     * change. Telling a list its size is fixed when the list is what decides that size leaves cards
     * clipped or floating in blank space.
     */
    private fun setUpPetList() {
        petAdapter = PetSummaryAdapter(
            photoScope = viewLifecycleOwner.lifecycleScope,
            onPetClick = { petId -> openPetDetail(petId) }
        )

        binding.petsRecyclerView.apply {
            // The layout manager decides how cards are positioned; a vertical linear list here. The
            // two card sizes are the adapter's business, not the layout manager's — see
            // PetSummaryAdapter.getItemViewType.
            layoutManager = LinearLayoutManager(requireContext())
            adapter = petAdapter
        }
    }

    private fun setUpClickListeners() {
        binding.addFirstPetButton.setOnClickListener { openNewPetForm() }
        binding.actionAddPet.setOnClickListener { openNewPetForm() }

        // The two tabs the remaining quick actions lead to. See openTab for why they navigate by
        // destination id rather than through an action in the graph.
        binding.actionAddTask.setOnClickListener { openTab(R.id.checklistFragment) }
        binding.actionLogExpense.setOnClickListener { openTab(R.id.expensesFragment) }
    }

    /** Opens an empty pet form. [PetViewModel.NO_PET_ID] is what tells it to create rather than edit. */
    private fun openNewPetForm() {
        findNavController().navigate(
            R.id.action_home_to_add_edit_pet,
            bundleOf(
                AddEditPetFragment.ARG_PET_ID to PetViewModel.NO_PET_ID,
                // The form needs the owner so a newly created pet can be attached to this account.
                AddEditPetFragment.ARG_OWNER_ID to sessionViewModel.userId.value
            )
        )
    }

    /**
     * Opens a pet's detail screen.
     *
     * Tapping a card shows the pet rather than opening the edit form directly: the detail screen is
     * also the way in to that pet's care routines, and editing is one option from there rather than
     * the only thing a tap can mean.
     */
    private fun openPetDetail(petId: Long) {
        findNavController().navigate(
            R.id.action_home_to_pet_detail,
            bundleOf(PetDetailFragment.ARG_PET_ID to petId)
        )
    }

    /**
     * Opens the account screen.
     *
     * Reached from the pet-count badge in the header, because the dashboard hides the shared toolbar
     * that carries the account actions everywhere else in the app — see
     * [com.prash.petcare.MainActivity.updateChromeFor]. Signing out lives on the profile screen
     * itself, so nothing is unreachable from here.
     */
    private fun openProfile() {
        findNavController().navigate(R.id.profileFragment)
    }

    /**
     * Switches to one of the other bottom-navigation tabs.
     *
     * **Why the quick actions go to a tab rather than straight to a form.** Neither a task nor an
     * expense can be created from nothing: a care task belongs to a routine, and an expense belongs
     * to a pet, so both forms require an argument the dashboard has no way to choose on the user's
     * behalf. Sending them to the tab that owns each — where a pet is picked and the form is one tap
     * further on — is the honest version of the shortcut. Guessing a pet here would be the kind of
     * convenience that files things under the wrong animal.
     *
     * **Why it navigates by destination id and not through an action.** The four tabs are peers
     * rather than steps in a journey, so the navigation graph deliberately declares no actions
     * between them — see the note at the top of nav_graph.xml. These options are the same ones
     * NavigationUI applies when a tab is tapped in the bar, which is what makes arriving this way
     * indistinguishable from arriving that way: the back stack does not grow a rung per switch, the
     * tab that was left keeps its scroll position, and the bottom bar moves its highlight by itself
     * because it is wired to the same NavController.
     */
    private fun openTab(destinationId: Int) {
        findNavController().navigate(
            destinationId,
            null,
            navOptions {
                // Clear back to the graph's start, saving the state of what is popped, so switching
                // tabs never stacks screens on top of one another.
                popUpTo(findNavController().graph.startDestinationId) { saveState = true }
                // Do not add a second copy of a destination already on top.
                launchSingleTop = true
                // Restore that tab as the user left it, if they have been there before.
                restoreState = true
            }
        )
    }

    /**
     * Subscribes to the ViewModel's state.
     *
     * `repeatOnLifecycle(STARTED)` begins collecting when the screen becomes visible and cancels
     * when it stops, so the fragment never does work in the background or touches views after
     * they have been destroyed. `viewLifecycleOwner` is used because the view has a shorter life
     * than the fragment, and these are the view's updates.
     */
    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Two collectors in their own coroutines: collect() never returns, so calling the
                // second after the first would mean it never ran. They are separate rather than one
                // combined state because the screen's data and the sync's progress change on
                // completely different rhythms — see HomeViewModel.syncStatus.
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
    private fun render(state: HomeUiState) {
        // A neutral greeting until the name arrives, rather than a blank line where the screen's
        // largest piece of text is about to appear.
        binding.welcomeText.text = if (state.userName.isNotEmpty()) {
            getString(R.string.home_welcome, state.userName)
        } else {
            getString(R.string.home_welcome_generic)
        }

        renderPetCount(state)
        renderTodayCard(state)

        // submitList hands each whole new list to DiffUtil, which works out what changed and
        // animates only those cards.
        petAdapter?.submitList(state.pets)

        // The whole dashboard and the empty state swap as a unit. Both stay hidden during the
        // initial load, which stops "No pets yet" flashing up before the first query returns.
        binding.dashboardContent.isVisible = state.pets.isNotEmpty()
        binding.emptyStateContainer.isVisible = state.hasNoPets
    }

    /**
     * Fills the header badge and the count beside the "Your pets" heading.
     *
     * The badge shows the bare number because a circle that size has room for nothing else; the
     * heading's count spells out what it is counting, since a lone "2" on the right of a heading
     * could be a count of anything on the screen. The badge's content description carries the same
     * words as the heading, so a screen reader is not left announcing a digit.
     */
    private fun renderPetCount(state: HomeUiState) {
        val count = state.pets.size

        binding.header.showPetCount(count)

        binding.petsInCareText.text =
            resources.getQuantityString(R.plurals.home_pets_in_care, count, count)
    }

    /**
     * Draws the "today's care" card: the ring, the count inside it and the two lines beside it.
     *
     * The ring's maximum is set from the day's total rather than being left at the default hundred,
     * so the progress value can be the completed count itself. Doing the percentage arithmetic here
     * instead would introduce rounding that makes four of five tasks draw at 80% of a circle whose
     * own track is divided into fifths — a mismatch of a degree or two that is visible at this size.
     *
     * A day with nothing scheduled would give the indicator a maximum of zero, which it cannot
     * divide by, so the maximum is floored at one. The progress is zero in that case either way, so
     * the ring simply shows an empty track.
     */
    private fun renderTodayCard(state: HomeUiState) {
        binding.todayProgressRing.max = state.tasksDueToday.coerceAtLeast(1)
        // setProgressCompat rather than setProgress: it animates from the previous value, so ticking
        // a task off on the Tasks tab and coming back shows the ring moving rather than jumping.
        binding.todayProgressRing.setProgressCompat(state.tasksDoneToday, true)

        binding.todayDoneCount.text = formatCount(state.tasksDoneToday)
        binding.todayTotalCount.text =
            getString(R.string.home_ring_of_total, state.tasksDueToday)

        // The ring and its two numbers are announced as one sentence. Without this, TalkBack would
        // read "1" and "of 6" as two unrelated fragments with the progress bar's own percentage
        // between them. A plural on the total, so a single task does not read as "1 of 1 tasks".
        binding.todayProgressLabels.contentDescription = resources.getQuantityString(
            R.plurals.cd_today_progress,
            state.tasksDueToday,
            state.tasksDoneToday,
            state.tasksDueToday
        )

        binding.todayHeadlineText.text = headlineText(state)
        binding.todayNextUpText.text = nextUpText(state)
    }

    /**
     * Renders a bare number for display.
     *
     * The middle of the progress ring shows a digit on its own, with no sentence around it to carry
     * a placeholder, so it cannot use a string resource. (The header badge has the same problem and
     * solves it the same way in TabHeader.kt.) `toString()` would nonetheless be wrong: it always produces Western Arabic
     * digits, where a device set to, say, Bengali or Persian expects the number in its own numerals.
     * [NumberFormat] asks the current locale, which is the same thing `getString` does for every
     * other number in the app.
     */
    private fun formatCount(value: Int): String = NumberFormat.getInstance().format(value)

    /**
     * The card's headline.
     *
     * Three different sentences for what is arithmetically the same figure, because a count of zero
     * arrives two ways and they mean opposite things to the reader: a day whose care is finished is
     * an achievement, and a day with no care planned is simply quiet. "0 things left for today"
     * would be a true statement that flattens both into an absence.
     */
    private fun headlineText(state: HomeUiState): String = when {
        state.tasksDueToday == 0 -> getString(R.string.home_nothing_scheduled)

        state.tasksLeftToday == 0 -> getString(R.string.home_all_caught_up)

        // getQuantityString picks the right plural form for the device's language. The first
        // argument selects the form; the second fills the placeholder.
        else -> resources.getQuantityString(
            R.plurals.home_things_left,
            state.tasksLeftToday,
            state.tasksLeftToday
        )
    }

    /**
     * The supporting line under the headline, naming what is next.
     *
     * Takes the same three cases as the headline above and says something different in each, rather
     * than disappearing when there is no task to name — the line holds the card's height steady, and
     * a card that changes shape as the last task is ticked off draws the eye to the wrong thing.
     */
    private fun nextUpText(state: HomeUiState): String {
        val next = state.nextUpTask

        return when {
            next != null -> getString(R.string.home_next_up, next.task.title, next.petName)

            state.tasksDueToday > 0 -> getString(R.string.home_next_up_done)

            else -> getString(R.string.home_next_up_none)
        }
    }

    /**
     * Shows how the backup is getting on, or nothing at all when there is nothing to say.
     *
     * The line is hidden rather than emptied in the idle state, so it takes up no space before the
     * first sync has had a chance to run — a blank line under the greeting would read as something
     * that had failed to load.
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

            is SyncStatus.Synced ->
                getString(R.string.sync_status_synced, relativeTime(status.atMillis))

            is SyncStatus.Offline -> getString(R.string.sync_status_offline)
        }

        binding.syncStatusText.text = text.orEmpty()
        binding.syncStatusText.isVisible = text != null

        // Anything still waiting is worth noticing; everything else is background information the
        // user should be able to glance past. The colour is the only difference — a warning icon or
        // a bright red would overstate it, since nothing has gone wrong and nothing is lost.
        //
        // colorAccentText rather than colorPrimary: the accent yellow is a fill colour and is far
        // too light to read as text on the light page. See attrs.xml.
        val colourAttr = if (status is SyncStatus.Pending) {
            R.attr.colorAccentText
        } else {
            com.google.android.material.R.attr.colorOnSurfaceVariant
        }
        binding.syncStatusText.setTextColor(resolveThemeColour(colourAttr))
    }

    /**
     * Reads a colour from the current Material theme.
     *
     * Resolved from the theme rather than taken from colors.xml so the indicator keeps step with
     * light and dark mode, which swap what these roles point at. Looking up a fixed colour would
     * leave the text correct in one mode and barely readable in the other.
     */
    private fun resolveThemeColour(@AttrRes attribute: Int): Int {
        val typedValue = TypedValue()
        requireContext().theme.resolveAttribute(attribute, typedValue, true)
        return ContextCompat.getColor(requireContext(), typedValue.resourceId)
    }

    /**
     * Turns a moment into wording like "2 minutes ago".
     *
     * [DateUtils] is used rather than a formatted timestamp because a bare clock time invites the
     * user to work out the difference themselves, and because it produces the phrase already
     * translated into the device's language — which a hand-built string could not.
     *
     * The minimum resolution means anything under a minute reads as "0 minutes ago" rather than
     * counting seconds, which would have the line rewriting itself while being looked at.
     */
    private fun relativeTime(millis: Long): CharSequence = DateUtils.getRelativeTimeSpanString(
        millis,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS
    )

    override fun onDestroyView() {
        super.onDestroyView()
        // The adapter outlives the view it was drawing into, so it is detached here. Without this a
        // RecyclerView keeps a reference to its adapter and the adapter to its holders, and the
        // whole destroyed hierarchy is held alive behind them.
        binding.petsRecyclerView.adapter = null

        // Released rather than kept, because it holds this view's lifecycle scope — see the note on
        // the property.
        petAdapter = null

        // Release the reference to the destroyed view hierarchy — see the note on [_binding].
        _binding = null
    }
}
