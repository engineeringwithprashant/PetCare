package com.prash.petcare.ui.pet

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.core.widget.ImageViewCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.prash.petcare.R
import com.prash.petcare.data.local.entity.CareRoutine
import com.prash.petcare.data.local.entity.Pet
import com.prash.petcare.data.local.entity.PetGender
import com.prash.petcare.databinding.FragmentPetDetailBinding
import com.prash.petcare.ui.checklist.ChecklistFragment
import com.prash.petcare.ui.common.anchorAboveNavBar
import com.prash.petcare.ui.common.bindHeaderFromSession
import com.prash.petcare.ui.common.formatAge
import com.prash.petcare.ui.common.formatStoredDate
import com.prash.petcare.ui.common.formatWeightWithUnit
import com.prash.petcare.ui.common.labelRes
import com.prash.petcare.ui.common.loadPetPhoto
import com.prash.petcare.ui.common.openProfile
import com.prash.petcare.ui.common.setUp
import com.prash.petcare.ui.common.showTitle
import com.prash.petcare.ui.expense.ExpensesFragment
import com.prash.petcare.ui.routine.AddRoutineFragment
import com.prash.petcare.ui.routine.RoutineAdapter
import com.prash.petcare.ui.routine.RoutineDetailFragment
import com.prash.petcare.ui.session.SessionViewModel
import kotlinx.coroutines.launch

/** Shows one pet: a large photo card, the facts a sitter would need, and the care routines set up. */
class PetDetailFragment : Fragment() {

    /** Nulled in [onDestroyView] to avoid leaking the destroyed view hierarchy. */
    private var _binding: FragmentPetDetailBinding? = null
    private val binding get() = _binding!!

    private val petId: Long by lazy { requireArguments().getLong(ARG_PET_ID) }

    private val viewModel: PetDetailViewModel by viewModels {
        PetDetailViewModel.factory(petId)
    }

    /** The Activity-wide record of who is signed. */
    private val sessionViewModel: SessionViewModel by activityViewModels { SessionViewModel.Factory }

    /** Guards against re-decoding the same photo on every state emission. */
    private var renderedPhotoPath: String? = null

    /** The routine cards' adapter. Holds no view or coroutine scope of its own. */
    private val routineAdapter = RoutineAdapter(
        onRoutineClick = { routine -> openRoutineDetail(routine.id) },
        onEditClick = { routine -> openRoutineForm(routineId = routine.id) },
        onToggleActiveClick = { routine -> viewModel.toggleRoutineActive(routine) },
        onDeleteClick = { routine -> confirmDeleteRoutine(routine) }
    )

    /** The vaccination card's rows. Takes no callbacks: the history is read-only in this pass. */
    private val vaccinationAdapter = VaccinationAdapter()

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

        // The header shared with the tabs. Its title starts empty and becomes the pet's first name as soon as the pet loads.
        binding.header.setUp(
            title = "",
            onBadgeClick = ::openNotifications,
            onMonogramClick = ::openProfile
        )

        // Keeps the header's mark showing the user's profile photo, falling back to the app's monogram.
        bindHeaderFromSession(binding.header, sessionViewModel)

        setUpRecyclerView()
        setUpClickListeners()
        observeViewModel()
        observeSavedVaccinationResult()
    }

    /** Confirms a vaccination recorded on the screen above this one, and offers the undo. */
    private fun observeSavedVaccinationResult() {
        val handle = findNavController().currentBackStackEntry?.savedStateHandle ?: return

        handle.getLiveData<Long>(AddVaccinationFragment.RESULT_RECORD_ID)
            .observe(viewLifecycleOwner) { recordId ->
                val vaccineName =
                    handle.get<String>(AddVaccinationFragment.RESULT_VACCINE_NAME).orEmpty()
                val petName = handle.get<String>(AddVaccinationFragment.RESULT_PET_NAME).orEmpty()
                val taskId = handle.get<Long>(AddVaccinationFragment.RESULT_TASK_ID)
                    ?: AddVaccinationViewModel.NO_TASK_ID

                handle.remove<Long>(AddVaccinationFragment.RESULT_RECORD_ID)
                handle.remove<String>(AddVaccinationFragment.RESULT_VACCINE_NAME)
                handle.remove<String>(AddVaccinationFragment.RESULT_PET_NAME)
                handle.remove<Long>(AddVaccinationFragment.RESULT_TASK_ID)

                showRecordedConfirmation(recordId, taskId, vaccineName, petName)
            }
    }

    /** "Rabies recorded for Krishala", with an undo. */
    private fun showRecordedConfirmation(
        recordId: Long,
        taskId: Long,
        vaccineName: String,
        petName: String
    ) {
        val root = _binding?.root ?: return

        Snackbar.make(
            root,
            getString(R.string.record_saved_snackbar, vaccineName, petName),
            Snackbar.LENGTH_LONG
        )
            .setAction(R.string.action_undo) {
                viewModel.undoVaccinationRecord(recordId, taskId)
                showMessage(getString(R.string.record_saved_undone, vaccineName))
            }
            // Without this the message would appear behind the floating navigation bar.
            .anchorAboveNavBar(this)
            .show()
    }

    /** Shows a brief message. Safe after the view is gone, when it does nothing. */
    private fun showMessage(text: String) {
        val root = _binding?.root ?: return
        Snackbar.make(root, text, Snackbar.LENGTH_SHORT).anchorAboveNavBar(this).show()
    }

    private fun setUpRecyclerView() {
        binding.routinesRecyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = routineAdapter
            // Deliberately not setHasFixedSize(true).
        }

        // The vaccination card's rows, set up the same way and for the same reasons.
        binding.vaccinationsRecyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = vaccinationAdapter
        }
    }

    private fun setUpClickListeners() {
        // The back button on the photo stands in for the toolbar's up arrow, which is hidden here.
        binding.backButton.setOnClickListener { findNavController().navigateUp() }

        binding.addRoutineButton.setOnClickListener { openRoutineForm(routineId = null) }

        // "+ RECORD" on the vaccination card. Only the pet travels: the record screen always creates a new record.
        binding.addVaccinationButton.setOnClickListener {
            findNavController().navigate(
                R.id.action_pet_detail_to_add_vaccination,
                bundleOf(AddVaccinationFragment.ARG_PET_ID to petId)
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
            // The owner comes from the loaded pet rather than being passed down from the previous screen.
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

    /** Opens the routine form: empty for the "+" button, filled in for a card's "Edit". */
    private fun openRoutineForm(routineId: Long?) {
        val arguments = bundleOf(AddRoutineFragment.ARG_PET_ID to petId)
        if (routineId != null) {
            arguments.putLong(AddRoutineFragment.ARG_ROUTINE_ID, routineId)
        }
        findNavController().navigate(R.id.action_pet_detail_to_add_routine, arguments)
    }

    /** Opens the notifications screen from the header's pet-count badge, as every tab's badge does. */
    private fun openNotifications() {
        findNavController().navigate(R.id.notificationsFragment)
    }

    /** Asks before deleting a routine. */
    private fun confirmDeleteRoutine(routine: CareRoutine) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete_routine_dialog_title)
            .setMessage(getString(R.string.delete_routine_dialog_message_from_menu, routine.title))
            // Cancel does nothing and is the dismissive choice, so the safe option is the easy one.
            .setNegativeButton(R.string.delete_routine_dialog_cancel, null)
            .setPositiveButton(R.string.delete_routine_dialog_confirm) { _, _ ->
                viewModel.deleteRoutine(routine)
            }
            .show()
    }

    /** Collects state for as long as the screen is visible. */
    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state -> render(state) }
            }
        }
    }

    /** Applies the current state to the views. */
    private fun render(state: PetDetailUiState) {
        binding.header.showTitle(state.petFirstName)

        state.pet?.let { pet ->
            bindHeroCard(pet, state)
            bindAboutCard(pet, state.ownerName)
        }

        routineAdapter.submitList(state.routines)

        // The list and the empty state occupy the same slot, so exactly one shows.
        binding.routinesRecyclerView.isVisible = state.routines.isNotEmpty()
        binding.emptyRoutinesContainer.isVisible = state.hasNoRoutines

        // The vaccination card, on exactly the same footing.
        vaccinationAdapter.submitList(state.vaccinations)
        binding.vaccinationsRecyclerView.isVisible = state.vaccinations.isNotEmpty()
        binding.emptyVaccinationsContainer.isVisible = state.hasNoVaccinations
    }

    /** Fills the photo card: the photo, species chip, gender symbol, full name and three pills. */
    private fun bindHeroCard(pet: Pet, state: PetDetailUiState) {
        renderPhoto(pet.photoPath)

        binding.petNameText.text = pet.name

        // The species enum stores database values (DOG, CAT), so it is translated to a readable label first.
        binding.speciesChip.text = getString(pet.species.labelRes())

        bindGenderBadge(pet.gender)

        // Age is derived from the date of birth rather than stored — see ui.common.formatAge — and uses the long form.
        val age = pet.dateOfBirth?.let { formatAge(requireContext(), it) }
        binding.agePill.text = age.orEmpty()
        binding.agePill.isVisible = age != null

        // Through the shared formatter, so this pet's weight reads exactly as it does on the Pets and Home cards.
        val weight = pet.weightKg?.let {
            formatWeightWithUnit(requireContext(), it, state.weightUnit)
        }
        binding.weightPill.text = weight.orEmpty()
        binding.weightPill.isVisible = weight != null

        binding.statusPill.text = statusText(state)
    }

    /** The yellow pill's wording: "Nothing due today", "2 left today" — or "All done today". */
    private fun statusText(state: PetDetailUiState): String = when {
        state.tasksOutstanding > 0 -> resources.getQuantityString(
            R.plurals.home_pet_tasks_left,
            state.tasksOutstanding,
            state.tasksOutstanding
        )

        state.tasksDueToday > 0 -> getString(R.string.home_pet_all_done)

        else -> getString(R.string.home_pet_nothing_due)
    }

    /** Shows the Mars symbol in blue for a male pet, the Venus symbol in pink for a female one, and nothing at all. */
    private fun bindGenderBadge(gender: PetGender) {
        val (iconRes, colorRes, labelRes) = when (gender) {
            PetGender.MALE -> Triple(R.drawable.ic_gender_male, R.color.gender_male, R.string.gender_male)
            PetGender.FEMALE ->
                Triple(R.drawable.ic_gender_female, R.color.gender_female, R.string.gender_female)
            PetGender.UNKNOWN -> {
                binding.genderBadge.isVisible = false
                return
            }
        }

        binding.genderBadge.setImageResource(iconRes)
        ImageViewCompat.setImageTintList(
            binding.genderBadge,
            ColorStateList.valueOf(ContextCompat.getColor(requireContext(), colorRes))
        )
        binding.genderBadge.contentDescription = getString(labelRes)
        binding.genderBadge.isVisible = true
    }

    /** Fills the "About" card's five rows. */
    private fun bindAboutCard(pet: Pet, ownerName: String) {
        binding.dobValueText.showValue(pet.dateOfBirth?.let { formatStoredDate(it) })
        binding.dietaryValueText.showValue(pet.dietaryNotes)
        binding.allergiesValueText.showValue(pet.allergies)
        binding.toysValueText.showValue(pet.favouriteToys)
        binding.caredForByValueText.showValue(ownerName)
    }

    /** Shows [value], or "None recorded" when it is null or blank. */
    private fun TextView.showValue(value: String?) {
        text = value?.takeIf { it.isNotBlank() } ?: getString(R.string.pet_detail_none_recorded)
    }

    /** Loads and shows the pet's photo, or the placeholder when there is none. */
    private fun renderPhoto(path: String?) {
        if (path == renderedPhotoPath) return
        renderedPhotoPath = path

        binding.petPhotoImage.loadPetPhoto(path, viewLifecycleOwner.lifecycleScope)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Detach the adapter from the dying RecyclerView: the adapter outlives the view.
        binding.routinesRecyclerView.adapter = null
        binding.vaccinationsRecyclerView.adapter = null
        renderedPhotoPath = null
        _binding = null
    }

    companion object {
        /** Navigation argument name; must match the one declared in nav_graph.xml. */
        const val ARG_PET_ID = "petId"
    }
}
