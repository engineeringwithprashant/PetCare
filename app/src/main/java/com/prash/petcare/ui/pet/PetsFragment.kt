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
import androidx.recyclerview.widget.LinearLayoutManager
import com.prash.petcare.R
import com.prash.petcare.databinding.FragmentPetsBinding
import com.prash.petcare.ui.session.SessionViewModel
import kotlinx.coroutines.launch

/**
 * The Pets tab: every animal the signed-in user has, and the way in to adding another.
 *
 * This is the app's directory of pets. It was the home screen before the redesign; the dashboard
 * took that slot, and this became a tab of its own — which is a better home for it either way,
 * because a list that grows without limit and a summary that has to fit on one screen want
 * different things.
 *
 * In MVVM this is the **View**: it renders whatever [PetsViewModel] publishes and forwards taps
 * back, performing no queries itself.
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
     * Rebuilt with each view rather than held for the fragment's whole life, because it captures
     * `viewLifecycleOwner.lifecycleScope` for its photo loads — and a fragment outlives its view,
     * so that scope is a different object each time the view is recreated. An adapter created once
     * would still hold the first view's scope, which is already cancelled, and every photo would
     * silently fail to load after the first visit.
     */
    private var petAdapter: PetAdapter? = null

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
        setUpRecyclerView()
        setUpClickListeners()
        observeViewModel()
    }

    /** Prepares the pet list. */
    private fun setUpRecyclerView() {
        petAdapter = PetAdapter(
            photoScope = viewLifecycleOwner.lifecycleScope,
            onPetClick = { pet -> openPetDetail(pet.id) }
        )

        binding.petsRecyclerView.apply {
            // The layout manager decides how rows are positioned; a vertical linear list here.
            layoutManager = LinearLayoutManager(requireContext())
            adapter = petAdapter

            // Deliberately not setHasFixedSize(true): a card's height changes with whether the pet
            // has an age and a weight recorded, since the pill row is hidden when it has neither.
        }
    }

    private fun setUpClickListeners() {
        binding.addPetFab.setOnClickListener { openNewPetForm() }
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
        // submitList hands the whole new list to DiffUtil, which works out what changed and
        // animates only those rows.
        petAdapter?.submitList(state.pets)

        // The list and the empty state occupy the same space, so exactly one is ever visible. Both
        // stay hidden during the initial load, which stops "No pets yet" flashing up before the
        // first query returns.
        binding.petsRecyclerView.isVisible = state.pets.isNotEmpty()
        binding.emptyStateContainer.isVisible = state.hasNoPets
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // The adapter outlives the view it was drawing into, so it is detached here. Without this a
        // RecyclerView keeps a reference to its adapter and the adapter to its holders, and the
        // whole destroyed hierarchy is held alive behind them.
        binding.petsRecyclerView.adapter = null
        petAdapter = null

        // Release the reference to the destroyed view hierarchy — see the note on [_binding].
        _binding = null
    }
}
