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
import com.prash.petcare.ui.common.bindHeaderFromSession
import com.prash.petcare.ui.common.openProfile
import com.prash.petcare.ui.common.setUp
import com.prash.petcare.ui.session.SessionViewModel
import java.text.NumberFormat
import kotlinx.coroutines.launch

/** The Pets tab: a large card for every animal the signed-in user has, and the way in to adding another. */
class PetsFragment : Fragment() {

    /** ViewBinding for fragment_pets.xml. */
    private var _binding: FragmentPetsBinding? = null
    private val binding get() = _binding!!

    /** Which account is signed in. */
    private val sessionViewModel: SessionViewModel by activityViewModels { SessionViewModel.Factory }

    private val viewModel: PetsViewModel by viewModels {
        PetsViewModel.factory(sessionViewModel.userId.value)
    }

    /** The three adapters that make up the list, top to bottom. */
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

        // The header shared with the dashboard. The badge opens the notifications screen.
        binding.header.setUp(
            title = R.string.nav_pets,
            onBadgeClick = ::openNotifications,
            onMonogramClick = ::openProfile
        )

        // Keeps the header's mark showing the user's profile photo, falling back to the app's monogram.
        bindHeaderFromSession(binding.header, sessionViewModel)

        setUpRecyclerView()
        binding.emptyAddPetButton.setOnClickListener { openNewPetForm() }
        observeViewModel()
    }

    /** Prepares the list: headline, pet cards and add row, joined into one. */
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

    /** Opens a pet's detail screen. */
    private fun openPetDetail(petId: Long) {
        findNavController().navigate(
            R.id.action_pets_to_pet_detail,
            bundleOf(PetDetailFragment.ARG_PET_ID to petId)
        )
    }

    /** Opens the notifications screen, from the header's pet-count badge. */
    private fun openNotifications() {
        findNavController().navigate(R.id.notificationsFragment)
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
    private fun render(state: PetsUiState) {
        val count = state.pets.size
        val hasPets = count > 0


        // The headline and add row only join the list once there are cards for them to frame.
        headlineAdapter?.headline = if (hasPets) headlineFor(count) else null
        addPetRowAdapter?.isShown = hasPets

        // submitList hands the whole new list to DiffUtil.
        petAdapter?.submitList(state.pets)

        // The list and the empty state occupy the same space, so exactly one is ever visible.
        binding.petsRecyclerView.isVisible = hasPets
        binding.emptyStateContainer.isVisible = state.hasNoPets
    }

    /** The headline sentence for [count] pets: "One pet,\none household", "Two pets,\none household". */
    private fun headlineFor(count: Int): String {
        val words = resources.getStringArray(R.array.pets_headline_count_words)
        val number = words.getOrNull(count - 1) ?: NumberFormat.getInstance().format(count)
        return resources.getQuantityString(R.plurals.pets_headline, count, number)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // The adapters outlive the view they were drawing into, so they are detached here.
        binding.petsRecyclerView.adapter = null
        headlineAdapter = null
        petAdapter = null
        addPetRowAdapter = null

        // Release the reference to the destroyed view hierarchy — see the note on [_binding].
        _binding = null
    }
}
