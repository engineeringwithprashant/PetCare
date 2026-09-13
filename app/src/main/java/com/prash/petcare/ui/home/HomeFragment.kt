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
import androidx.recyclerview.widget.LinearLayoutManager
import com.prash.petcare.R
import com.prash.petcare.data.sync.SyncStatus
import com.prash.petcare.databinding.FragmentHomeBinding
import com.prash.petcare.ui.pet.AddEditPetFragment
import com.prash.petcare.ui.pet.PetDetailFragment
import com.prash.petcare.ui.pet.PetViewModel
import com.prash.petcare.ui.session.SessionViewModel
import kotlinx.coroutines.launch

/**
 * The dashboard: the first of the four bottom-navigation tabs, and the screen the app opens on once
 * signed in.
 *
 * It answers four questions in the order they are asked — who am I and is my data safe, who are my
 * animals and how are they today, what do I have to do now, and what is coming.
 *
 * In MVVM this is the **View**. It renders whatever [HomeViewModel] publishes and forwards taps
 * back — it performs no queries and calculates nothing itself, which is what allows it to be
 * destroyed and rebuilt on rotation without losing or duplicating work. Even the day's arithmetic
 * — which repeating tasks land on today — happens in the ViewModel, where it can be tested without
 * an emulator.
 *
 * The pets and the care come from Room, exactly as they did before the app gained a Firestore sync
 * layer. This screen shows how that sync is getting on but never reads anything from it: the
 * database remains the only thing the UI reads, and a pet pulled down from Firestore reaches these
 * cards by being written into Room like any other row.
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
     * The adapters for today's care and the appointments card.
     *
     * Created once and kept, rather than being rebuilt on each update: a new adapter would discard
     * the previous list, so DiffUtil would have nothing to compare against and every row would be
     * redrawn instead of only the ones that changed.
     *
     * Neither depends on the view, so both can be fields on the fragment. The pet adapter below
     * cannot, and the note there explains why.
     */
    private val todayAdapter = TodayTaskAdapter(
        onTaskChecked = { taskId, isDone -> viewModel.setTaskDone(taskId, isDone) }
    )

    private val appointmentAdapter = AppointmentAdapter()

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
        setUpRecyclerViews()
        setUpClickListeners()
        observeViewModel()
    }

    /**
     * Prepares the three lists.
     *
     * None of them calls `setHasFixedSize(true)`, and none of them should: each sits inside the
     * dashboard's NestedScrollView with wrap_content height, so every one must be allowed to
     * re-measure as its contents change. Telling a list its size is fixed when the list is what
     * decides that size leaves rows clipped or floating in blank space.
     */
    private fun setUpRecyclerViews() {
        petAdapter = PetSummaryAdapter(
            photoScope = viewLifecycleOwner.lifecycleScope,
            onPetClick = { petId -> openPetDetail(petId) }
        )

        binding.petsRecyclerView.apply {
            // The layout manager decides how rows are positioned; a vertical linear list here.
            layoutManager = LinearLayoutManager(requireContext())
            adapter = petAdapter
        }

        binding.todayRecyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = todayAdapter
        }

        binding.appointmentsRecyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = appointmentAdapter
        }
    }

    private fun setUpClickListeners() {
        binding.addFirstPetButton.setOnClickListener { openNewPetForm() }
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

        // submitList hands each whole new list to DiffUtil, which works out what changed and
        // animates only those rows.
        petAdapter?.submitList(state.pets)
        todayAdapter.submitList(state.todayTasks)
        appointmentAdapter.submitList(state.appointments)

        binding.todayProgressText.text = progressText(state)

        // Within each card, the list and its "nothing here" line occupy the same slot, so exactly
        // one of the two is ever shown. Both are set explicitly rather than only the one that
        // changed, because this runs on every emission and the previous state may have been either.
        binding.todayRecyclerView.isVisible = state.todayTasks.isNotEmpty()
        binding.todayEmptyText.isVisible = state.todayTasks.isEmpty()

        binding.appointmentsRecyclerView.isVisible = state.appointments.isNotEmpty()
        binding.appointmentsEmptyText.isVisible = state.appointments.isEmpty()

        // The whole dashboard and the empty state swap as a unit. Both stay hidden during the
        // initial load, which stops "No pets yet" flashing up before the first query returns.
        binding.dashboardContent.isVisible = state.pets.isNotEmpty()
        binding.emptyStateContainer.isVisible = state.hasNoPets
    }

    /** Builds the "2 of 5 done" line beside today's heading, or nothing when no care is due. */
    private fun progressText(state: HomeUiState): String =
        if (state.tasksDueToday == 0) {
            // Empty rather than "0 of 0 done": the card's own empty line already says there is
            // nothing due, and a count of zero beside it would say it twice.
            ""
        } else {
            // getQuantityString picks the right plural form for the device's language. The first
            // argument selects the form; the two after it fill the placeholders.
            resources.getQuantityString(
                R.plurals.home_tasks_summary,
                state.tasksDueToday,
                state.tasksDoneToday,
                state.tasksDueToday
            )
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
        // too light to read as text on the cream page. See attrs.xml.
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
        // The adapters outlive the view they were drawing into, so they are detached here. Without
        // this a RecyclerView keeps a reference to its adapter and the adapter to its holders, and
        // the whole destroyed hierarchy is held alive behind them.
        binding.petsRecyclerView.adapter = null
        binding.todayRecyclerView.adapter = null
        binding.appointmentsRecyclerView.adapter = null

        // Released rather than kept, because it holds this view's lifecycle scope — see the note on
        // the property.
        petAdapter = null

        // Release the reference to the destroyed view hierarchy — see the note on [_binding].
        _binding = null
    }
}
