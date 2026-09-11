package com.prash.petcare.ui.pet

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.os.bundleOf
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
import com.prash.petcare.ui.common.formatStoredDate
import com.prash.petcare.ui.common.labelRes
import com.prash.petcare.ui.routine.AddRoutineFragment
import com.prash.petcare.ui.routine.RoutineAdapter
import com.prash.petcare.ui.routine.RoutineDetailFragment
import com.prash.petcare.util.PetPhotoStorage
import kotlinx.coroutines.launch

/**
 * Shows one pet's recorded details and the care routines set up for it.
 *
 * This is the hub for everything to do with a single animal: from here the user can edit the pet,
 * open today's checklist, create a routine, or open a routine to manage its tasks. It is read-only
 * itself — every change happens on the screen it sends the user to.
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
        binding.routinesRecyclerView.visibility =
            if (state.routines.isNotEmpty()) View.VISIBLE else View.GONE
        binding.emptyRoutinesContainer.visibility =
            if (state.hasNoRoutines) View.VISIBLE else View.GONE
    }

    /** Fills in the pet's header and detail blocks. */
    private fun bindPet(pet: Pet) {
        binding.petNameText.text = pet.name

        val speciesLabel = getString(pet.species.labelRes())
        val breed = pet.breed?.takeIf { it.isNotBlank() }
        binding.petSubtitleText.text = if (breed != null) {
            getString(R.string.pet_species_and_breed, speciesLabel, breed)
        } else {
            speciesLabel
        }

        renderPhoto(pet.photoPath)

        // Each block hides itself when its field was never filled in, so the card shows only what
        // the owner actually recorded rather than a column of empty headings.
        val hasDob = bindDetailBlock(
            binding.dobBlock,
            binding.dobValueText,
            pet.dateOfBirth?.let { formatStoredDate(it) }
        )
        val hasWeight = bindDetailBlock(
            binding.weightBlock,
            binding.weightValueText,
            pet.weightKg?.let { getString(R.string.pet_detail_weight_format, it.toString()) }
        )
        val hasDietary =
            bindDetailBlock(binding.dietaryBlock, binding.dietaryValueText, pet.dietaryNotes)
        val hasAllergies =
            bindDetailBlock(binding.allergiesBlock, binding.allergiesValueText, pet.allergies)
        val hasToys =
            bindDetailBlock(binding.toysBlock, binding.toysValueText, pet.favouriteToys)

        // With every block hidden the card would render as an empty rectangle, so it goes too.
        val hasAnyDetail = hasDob || hasWeight || hasDietary || hasAllergies || hasToys
        binding.detailsCard.visibility = if (hasAnyDetail) View.VISIBLE else View.GONE
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
        container.visibility = if (hasValue) View.VISIBLE else View.GONE
        return hasValue
    }

    /** Loads and shows the pet's photo, or the placeholder when there is none. */
    private fun renderPhoto(path: String?) {
        if (path == renderedPhotoPath) return
        renderedPhotoPath = path

        if (path == null) {
            binding.petPhotoImage.setImageResource(R.drawable.ic_pet_placeholder)
            binding.petPhotoImage.scaleType = ImageView.ScaleType.CENTER_INSIDE
            return
        }

        // Decoding touches the disk, so it runs in a coroutine tied to the view's lifecycle and is
        // cancelled if the screen closes first.
        viewLifecycleOwner.lifecycleScope.launch {
            val bitmap = PetPhotoStorage.load(path)
            if (bitmap != null) {
                binding.petPhotoImage.setImageBitmap(bitmap)
                binding.petPhotoImage.scaleType = ImageView.ScaleType.CENTER_CROP
                binding.petPhotoImage.setPadding(0, 0, 0, 0)
            }
        }
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
