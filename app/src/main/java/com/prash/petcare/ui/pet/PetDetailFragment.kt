package com.prash.petcare.ui.pet

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.prash.petcare.R
import com.prash.petcare.data.local.entity.Pet
import com.prash.petcare.databinding.FragmentPetDetailBinding
import com.prash.petcare.ui.checklist.ChecklistFragment
import com.prash.petcare.ui.common.formatAge
import com.prash.petcare.ui.common.formatStoredDate
import com.prash.petcare.ui.common.labelRes
import com.prash.petcare.ui.common.loadPetPhoto
import com.prash.petcare.ui.common.setToolbarTitle
import com.prash.petcare.ui.expense.ExpensesFragment
import com.prash.petcare.ui.routine.AddRoutineFragment
import com.prash.petcare.ui.routine.RoutineAdapter
import com.prash.petcare.ui.routine.RoutineDetailFragment
import kotlinx.coroutines.launch

/**
 * Shows one pet's recorded details and the care routines set up for it.
 *
 * This is the hub for everything to do with a single animal: from here the user can edit the pet,
 * open today's checklist, review what has been spent on it, create a routine, or open a routine to
 * manage its tasks. It is read-only itself — every change happens on the screen it sends the user
 * to.
 */
class PetDetailFragment : Fragment() {

    /** Nulled in [onDestroyView] to avoid leaking the destroyed view hierarchy. */
    private var _binding: FragmentPetDetailBinding? = null
    private val binding get() = _binding!!

    private val petId: Long by lazy { requireArguments().getLong(ARG_PET_ID) }

    private val viewModel: PetDetailViewModel by viewModels {
        PetDetailViewModel.factory(petId)
    }

    /** Guards against re-decoding the same image every time unrelated state changes. */
    private var renderedPhotoPath: String? = null

    private val routineAdapter = RoutineAdapter(
        onRoutineClick = { routine -> openRoutineDetail(routine.id) }
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPetDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpRecyclerView()
        setUpClickListeners()
        observeViewModel()
    }

    private fun setUpRecyclerView() {
        binding.routinesRecyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = routineAdapter
            // Deliberately not setHasFixedSize(true): this list sits inside a NestedScrollView with
            // wrap_content height, so it must be allowed to re-measure as routines are added.
        }
    }

    private fun setUpClickListeners() {
        binding.addRoutineFab.setOnClickListener {
            findNavController().navigate(
                R.id.action_pet_detail_to_add_routine,
                bundleOf(AddRoutineFragment.ARG_PET_ID to petId)
            )
        }

        binding.checklistButton.setOnClickListener {
            findNavController().navigate(
                R.id.action_pet_detail_to_checklist,
                bundleOf(ChecklistFragment.ARG_PET_ID to petId)
            )
        }

        binding.expensesButton.setOnClickListener {
            findNavController().navigate(
                R.id.action_pet_detail_to_expenses,
                bundleOf(ExpensesFragment.ARG_PET_ID to petId)
            )
        }

        binding.editPetButton.setOnClickListener {
            // The owner comes from the loaded pet rather than being passed down from the home
            // screen, so the two can never disagree about who owns this animal. If the pet has not
            // loaded yet there is nothing to edit, so the tap is ignored.
            val pet = viewModel.uiState.value.pet ?: return@setOnClickListener

            findNavController().navigate(
                R.id.action_pet_detail_to_edit_pet,
                bundleOf(
                    AddEditPetFragment.ARG_PET_ID to pet.id,
                    AddEditPetFragment.ARG_OWNER_ID to pet.ownerId
                )
            )
        }
    }

    private fun openRoutineDetail(routineId: Long) {
        findNavController().navigate(
            R.id.action_pet_detail_to_routine_detail,
            bundleOf(RoutineDetailFragment.ARG_ROUTINE_ID to routineId)
        )
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state -> render(state) }
            }
        }
    }

    /** Applies the current state to the views. */
    private fun render(state: PetDetailUiState) {
        state.pet?.let { bindPet(it) }

        routineAdapter.submitList(state.routines)

        // The list and the empty message occupy the same slot, so exactly one shows. Both stay
        // hidden while loading, which stops "No routines yet" flashing up before the first result.
        binding.routinesRecyclerView.isVisible = state.routines.isNotEmpty()
        binding.emptyRoutinesContainer.isVisible = state.hasNoRoutines
    }

    /** Fills in the pet's header, stat pills and detail blocks. */
    private fun bindPet(pet: Pet) {
        // The name appears twice, and deliberately so: in the app bar, which is what identifies the
        // screen once the photo has scrolled away, and as the heading below the photo, which is
        // part of the introduction the photo begins.
        setToolbarTitle(pet.name)
        binding.petNameText.text = pet.name

        // Breed alone here, since the species has a pill of its own. Hidden when none was recorded
        // rather than leaving an empty line between the name and the pills — breed is not
        // meaningful for every species, so this is a common case rather than an edge one.
        val breed = pet.breed?.takeIf { it.isNotBlank() }
        binding.petBreedText.text = breed.orEmpty()
        binding.petBreedText.isVisible = breed != null

        bindPills(pet)
        renderPhoto(pet.photoPath)
        bindDetailCard(pet)
    }

    /**
     * Fills the three pastel pills — age, weight and species.
     *
     * Each hides itself when its fact was never recorded, so the row shows only what is actually
     * known. Species is the one that is always present, since it is a required field on the form,
     * which means the row can never end up empty and does not need hiding as a whole.
     *
     * Age is derived from the date of birth rather than stored — see ui.common.formatAge — while the
     * exact date stays in the card below. The two are complementary: the pill answers "how old is
     * this animal?" at a glance, and the date is what a vet form asks for.
     */
    private fun bindPills(pet: Pet) {
        val age = pet.dateOfBirth?.let { formatAge(requireContext(), it) }
        binding.agePill.text = age.orEmpty()
        binding.agePill.isVisible = age != null

        val weight = pet.weightKg
            ?.let { getString(R.string.pet_detail_weight_format, it.toString()) }
        binding.weightPill.text = weight.orEmpty()
        binding.weightPill.isVisible = weight != null

        // The species enum stores database values (DOG, CAT), so it is translated to a readable
        // label before display.
        binding.speciesPill.text = getString(pet.species.labelRes())
    }

    /**
     * Fills the "About" card — the things a sitter or a vet would need to be told.
     *
     * Each block hides itself when its field was never filled in, so the card shows only what the
     * owner actually recorded rather than a column of empty headings. Weight is absent from this
     * card because it is a pill instead: it is glanced at rather than read, which is what a pill is
     * for.
     */
    private fun bindDetailCard(pet: Pet) {
        val hasDob = bindDetailBlock(
            binding.dobBlock,
            binding.dobValueText,
            pet.dateOfBirth?.let { formatStoredDate(it) }
        )
        val hasDietary =
            bindDetailBlock(binding.dietaryBlock, binding.dietaryValueText, pet.dietaryNotes)
        val hasAllergies =
            bindDetailBlock(binding.allergiesBlock, binding.allergiesValueText, pet.allergies)
        val hasToys =
            bindDetailBlock(binding.toysBlock, binding.toysValueText, pet.favouriteToys)

        // With every block hidden the card would render as a white rectangle holding nothing but
        // its own heading, so it goes too.
        binding.detailsCard.isVisible = hasDob || hasDietary || hasAllergies || hasToys
    }

    /**
     * Shows or hides one label-and-value pair.
     *
     * @return true if the block has content and is now visible, which the caller uses to decide
     *   whether the surrounding card is worth showing at all.
     */
    private fun bindDetailBlock(container: View, valueView: TextView, value: String?): Boolean {
        val hasValue = !value.isNullOrBlank()
        if (hasValue) {
            valueView.text = value
        }
        container.isVisible = hasValue
        return hasValue
    }

    /**
     * Loads and shows the pet's photo, or the placeholder when there is none.
     *
     * Guarded against re-decoding the same image: [render] runs on every state emission — a routine
     * being paused, a task ticked elsewhere — and without this the photo would be read from disk
     * again each time, for a result identical to what is already on screen.
     *
     * The load itself is shared with the list rows, which need the same care about a view being
     * rebound mid-decode; see ui/common/PetPhotoBinder.kt.
     */
    private fun renderPhoto(path: String?) {
        if (path == renderedPhotoPath) return
        renderedPhotoPath = path

        binding.petPhotoImage.loadPetPhoto(path, viewLifecycleOwner.lifecycleScope)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        renderedPhotoPath = null
        _binding = null
    }

    companion object {
        /** Navigation argument name; must match the one declared in nav_graph.xml. */
        const val ARG_PET_ID = "petId"
    }
}
