package com.prash.petcare.ui.pet

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
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import com.prash.petcare.R
import com.prash.petcare.databinding.FragmentPetsBinding
import com.prash.petcare.ui.common.setUp
import com.prash.petcare.ui.common.showPetCount
import com.prash.petcare.ui.session.SessionViewModel
import kotlinx.coroutines.launch
import java.text.NumberFormat

/**
 * The Pets tab: a large card for every animal the signed-in user has, and the way in to adding
 * another.
 *
 * The screen has the same header as the dashboard (monogram, title, theme toggle, pet count), then a
 * headline that adapts to the count — "Two pets, one household" — then one card per pet showing its
 * photo, species, name, carer, age, weight and tasks due today, and finally an outlined "Add a pet"
 * row.
 *
 * In MVVM this is the **View**: it renders whatever [PetsViewModel] publishes and forwards taps
 * back, performing no queries itself. The one thing it works out is the headline's wording, because
 * that needs string resources and a ViewModel must never hold a Context to read them.
 */
class PetsFragment : Fragment() {

    /**
     * ViewBinding for fragment_pets.xml.
     *
     * Nullable because a fragment's view can be destroyed while the fragment itself lives on;
     * holding the binding past that point would leak the whole view hierarchy. Cleared in
     * [onDestroyView].
     */
    private var _binding: FragmentPetsBinding? = null
    private val binding get() = _binding!!

    /**
     * Which account is signed in.
     *
     * Read from the Activity-wide session rather than from a navigation argument: a bottom
     * navigation bar moves between tabs by destination id and has nowhere to attach one. See
     * [SessionViewModel].
     */
    private val sessionViewModel: SessionViewModel by activityViewModels()

    private val viewModel: PetsViewModel by viewModels {
        PetsViewModel.factory(sessionViewModel.userId.value)
    }

    /**
     * The three adapters that make up the list, top to bottom.
     *
     * Rebuilt with each view rather than held for the fragment's whole life. [petAdapter] captures
     * `viewLifecycleOwner.lifecycleScope` for its photo loads, and a fragment outlives its view, so
     * that scope is a different object each time the view is recreated — an adapter created once
     * would hold the first view's cancelled scope and every photo would silently fail to load after
     * the first visit. The other two are rebuilt alongside it so all three always belong to the same
     * view.
     *
     * Nullable for the same reason [_binding] is: they only exist between [onViewCreated] and
     * [onDestroyView].
     */
    private var headlineAdapter: PetsHeadlineAdapter? = null
    private var petAdapter: PetAdapter? = null
    private var addPetRowAdapter: AddPetRowAdapter? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPetsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // The header shared with the dashboard. The badge opens the profile screen, because this
        // tab — like the dashboard — hides the shared toolbar whose overflow menu held the account
        // actions. See ui/common/TabHeader.kt and MainActivity.updateChromeFor.
        binding.header.setUp(title = R.string.nav_pets, onBadgeClick = ::openProfile)

        setUpRecyclerView()
        binding.emptyAddPetButton.setOnClickListener { openNewPetForm() }
        observeViewModel()
    }

    /**
     * Prepares the list: headline, pet cards and add row, joined into one.
     *
     * [ConcatAdapter] presents several adapters to a single RecyclerView as if they were one list,
     * in the order given. That lets the headline and the add row scroll with the cards — as the
     * design requires — while the pet cards keep full recycling, and without any adapter having to
     * shift its positions by one to make room for a header.
     *
     * Deliberately not `setHasFixedSize(true)`: a card's height varies with the length of the pet's
     * name, and the headline and add row come and go.
     */
    private fun setUpRecyclerView() {
        val headline = PetsHeadlineAdapter()
        val pets = PetAdapter(
            photoScope = viewLifecycleOwner.lifecycleScope,
            onPetClick = { petId -> openPetDetail(petId) }
        )
        val addRow = AddPetRowAdapter(onAddPetClick = { openNewPetForm() })

        headlineAdapter = headline
        petAdapter = pets
        addPetRowAdapter = addRow

        binding.petsRecyclerView.apply {
            // The layout manager decides how items are positioned; a vertical linear list here.
            layoutManager = LinearLayoutManager(requireContext())
            adapter = ConcatAdapter(headline, pets, addRow)
        }
    }

    /** Opens an empty pet form. [PetViewModel.NO_PET_ID] is what tells it to create rather than edit. */
    private fun openNewPetForm() {
        findNavController().navigate(
            R.id.action_pets_to_add_edit_pet,
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
     * Tapping a pet shows it rather than opening the edit form directly: the detail screen is also
     * the way in to that pet's care routines and today's checklist, and editing is one option from
     * there rather than the only thing a tap can mean.
     */
    private fun openPetDetail(petId: Long) {
        findNavController().navigate(
            R.id.action_pets_to_pet_detail,
            bundleOf(PetDetailFragment.ARG_PET_ID to petId)
        )
    }

    /**
     * Opens the account screen, from the header's pet-count badge.
     *
     * By destination id, the same way the dashboard's badge and the toolbar's overflow menu do, so
     * all three routes to the profile screen arrive identically.
     */
    private fun openProfile() {
        findNavController().navigate(R.id.profileFragment)
    }

    /**
     * Subscribes to the ViewModel's state.
     *
     * `repeatOnLifecycle(STARTED)` begins collecting when the screen becomes visible and cancels
     * when it stops, so the fragment never does work in the background or touches views after they
     * have been destroyed. `viewLifecycleOwner` is used because the view has a shorter life than
     * the fragment, and these are the view's updates.
     */
    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state -> render(state) }
            }
        }
    }

    /** Applies the current state to the views. */
    private fun render(state: PetsUiState) {
        val count = state.pets.size
        val hasPets = count > 0

        binding.header.showPetCount(count)

        // The headline and add row only join the list once there are cards for them to frame. While
        // loading they would sit alone on an otherwise blank screen; with no pets, the empty state
        // below says the same things in a form suited to an empty screen.
        headlineAdapter?.headline = if (hasPets) headlineFor(count) else null
        addPetRowAdapter?.isShown = hasPets

        // submitList hands the whole new list to DiffUtil, which works out what changed and
        // animates only those cards.
        petAdapter?.submitList(state.pets)

        // The list and the empty state occupy the same space, so exactly one is ever visible. Both
        // stay hidden during the initial load, which stops "No pets yet" flashing up before the
        // first query returns.
        binding.petsRecyclerView.isVisible = hasPets
        binding.emptyStateContainer.isVisible = state.hasNoPets
    }

    /**
     * The headline sentence for [count] pets: "One pet,\none household", "Two pets,\none household".
     *
     * The number is spelled out from a string array while there is a word for it, and falls back to
     * locale-formatted digits beyond that — see the note on the resources in strings.xml. The
     * plurals resource chooses "pet" or "pets" from the count itself, so the grammar is right in
     * either case and in any language that supplies its own plural forms.
     */
    private fun headlineFor(count: Int): String {
        val words = resources.getStringArray(R.array.pets_headline_count_words)
        val number = words.getOrNull(count - 1) ?: NumberFormat.getInstance().format(count)
        return resources.getQuantityString(R.plurals.pets_headline, count, number)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // The adapters outlive the view they were drawing into, so they are detached here. Without
        // this a RecyclerView keeps a reference to its adapter and the adapter to its holders, and
        // the whole destroyed hierarchy is held alive behind them.
        binding.petsRecyclerView.adapter = null
        headlineAdapter = null
        petAdapter = null
        addPetRowAdapter = null

        // Release the reference to the destroyed view hierarchy — see the note on [_binding].
        _binding = null
    }
}
