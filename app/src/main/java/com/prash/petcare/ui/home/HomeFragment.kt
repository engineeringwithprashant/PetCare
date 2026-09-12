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
import androidx.fragment.app.Fragment
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
import kotlinx.coroutines.launch

/**
 * The screen shown once a user is signed in: a greeting, the backup status, today's care summary,
 * and their pets.
 *
 * In MVVM this is the **View**. It renders whatever [HomeViewModel] publishes and forwards taps
 * back — it performs no queries and calculates nothing itself, which is what allows it to be
 * destroyed and rebuilt on rotation without losing or duplicating work.
 *
 * The pets and the summary come from Room, exactly as they did before the app gained a Firestore
 * sync layer. This screen shows how that sync is getting on but never reads anything from it: the
 * database remains the only thing the UI reads, and a pet pulled down from Firestore reaches this
 * list by being written into Room like any other row.
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
     * Which account is signed in, supplied as the navigation argument declared on this
     * destination in nav_graph.xml.
     *
     * `requireArguments` rather than the nullable `arguments`: arriving here without a user id
     * means the navigation graph is wrong, and failing loudly is easier to diagnose than a
     * silently empty screen.
     */
    private val userId: Long by lazy { requireArguments().getLong(ARG_USER_ID) }

    private val viewModel: HomeViewModel by viewModels { HomeViewModel.factory(userId) }

    /**
     * Created once and kept for the life of the view, rather than being rebuilt on each update.
     * A new adapter would discard the previous list, so DiffUtil would have nothing to compare
     * against and every row would be redrawn.
     */
    private val petAdapter = PetAdapter(onPetClick = { pet -> openPetDetail(pet.id) })

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
        setUpRecyclerView()
        setUpClickListeners()
        observeViewModel()
    }

    /** Prepares the pet list. */
    private fun setUpRecyclerView() {
        binding.petsRecyclerView.apply {
            // The layout manager decides how rows are positioned; a vertical linear list here.
            layoutManager = LinearLayoutManager(requireContext())
            adapter = petAdapter

            // Every row has identical dimensions, so the RecyclerView can skip re-measuring
            // itself each time the contents change — a small scrolling win.
            setHasFixedSize(true)
        }
    }

    private fun setUpClickListeners() {
        binding.addPetFab.setOnClickListener { openNewPetForm() }
    }

    /** Opens an empty pet form. [PetViewModel.NO_PET_ID] is what tells it to create rather than edit. */
    private fun openNewPetForm() {
        findNavController().navigate(
            R.id.action_home_to_add_edit_pet,
            bundleOf(
                AddEditPetFragment.ARG_PET_ID to PetViewModel.NO_PET_ID,
                // The form needs the owner so a newly created pet can be attached to this account.
                AddEditPetFragment.ARG_OWNER_ID to userId
            )
        )
    }

    /**
     * Opens a pet's detail screen.
     *
     * Tapping a pet shows it rather than opening the edit form directly: the detail screen is also
     * the way in to that pet's care routines and today's checklist, and editing is one option from
     * there rather than the only thing a tap can mean.
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

    /**
     * Shows how the backup is getting on, or nothing at all when there is nothing to say.
     *
     * The line is hidden rather than emptied in the idle state, so it takes up no space before the
     * first sync has had a chance to run — a blank line above the care summary would read as
     * something that had failed to load.
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
        binding.syncStatusText.visibility = if (text != null) View.VISIBLE else View.GONE

        // Anything still waiting is worth noticing; everything else is background information the
        // user should be able to glance past. The colour is the only difference — a warning icon or
        // a bright red would overstate it, since nothing has gone wrong and nothing is lost.
        val colourAttr = if (status is SyncStatus.Pending) {
            com.google.android.material.R.attr.colorPrimary
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

    /** Applies the current state to the views. */
    private fun render(state: HomeUiState) {
        // Blank rather than a half-finished greeting while the name is still being read.
        binding.welcomeText.text = if (state.userName.isNotEmpty()) {
            getString(R.string.home_welcome, state.userName)
        } else {
            ""
        }

        binding.todaySummaryText.text = summaryText(state)

        // submitList hands the whole new list to DiffUtil, which works out what changed and
        // animates only those rows.
        petAdapter.submitList(state.pets)

        // The list and the empty state occupy the same space, so exactly one is ever visible.
        // Both stay hidden during the initial load, which stops "No pets yet" flashing up before
        // the first query returns.
        binding.petsRecyclerView.visibility = if (state.pets.isNotEmpty()) View.VISIBLE else View.GONE
        binding.emptyStateContainer.visibility = if (state.hasNoPets) View.VISIBLE else View.GONE
    }

    /** Builds today's care summary line. */
    private fun summaryText(state: HomeUiState): String =
        if (state.tasksDueToday == 0) {
            getString(R.string.home_tasks_none)
        } else {
            // getQuantityString picks the right plural form for the device's language. The first
            // argument selects the form; the two after it fill the placeholders.
            resources.getQuantityString(
                R.plurals.home_tasks_summary,
                state.tasksDueToday,
                state.tasksCompletedToday,
                state.tasksDueToday
            )
        }

    override fun onDestroyView() {
        super.onDestroyView()
        // Release the reference to the destroyed view hierarchy — see the note on [_binding].
        _binding = null
    }

    companion object {
        /**
         * Key for the user id argument. Must match the `userId` argument declared for this
         * destination in nav_graph.xml, and the key the auth fragments put in the Bundle.
         */
        const val ARG_USER_ID = "userId"
    }
}
