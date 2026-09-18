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
import com.prash.petcare.R
import com.prash.petcare.data.local.entity.CareRoutine
import com.prash.petcare.data.local.entity.Pet
import com.prash.petcare.data.local.entity.PetGender
import com.prash.petcare.databinding.FragmentPetDetailBinding
import com.prash.petcare.ui.checklist.ChecklistFragment
import com.prash.petcare.ui.common.bindHeaderProfilePhoto
import com.prash.petcare.ui.common.formatAge
import com.prash.petcare.ui.common.formatStoredDate
import com.prash.petcare.ui.common.formatWeightWithUnit
import com.prash.petcare.ui.common.labelRes
import com.prash.petcare.ui.common.loadPetPhoto
import com.prash.petcare.ui.common.openProfile
import com.prash.petcare.ui.common.setUp
import com.prash.petcare.ui.common.showPetCount
import com.prash.petcare.ui.common.showTitle
import com.prash.petcare.ui.expense.ExpensesFragment
import com.prash.petcare.ui.routine.AddRoutineFragment
import com.prash.petcare.ui.routine.RoutineAdapter
import com.prash.petcare.ui.routine.RoutineDetailFragment
import com.prash.petcare.ui.session.SessionViewModel
import kotlinx.coroutines.launch

/**
 * Shows one pet: a large photo card, the facts a sitter would need, and the care routines set up for
 * it — built to match design/pet-detail-mockup.png.
 *
 * This is the hub for everything to do with a single animal. From here the user can open the pet's
 * checklist or spending, edit the pet, create a routine, and open, edit, pause or delete an existing
 * routine.
 *
 * As the **View** in MVVM it holds no business logic. It renders whatever [PetDetailViewModel]
 * publishes, and forwards taps: navigation it performs itself (that needs a NavController, which a
 * ViewModel must not hold), while the two routine actions that change data — pause/resume and delete
 * — are handed to the ViewModel, after a confirmation dialog in the case of delete.
 */
class PetDetailFragment : Fragment() {

    /** Nulled in [onDestroyView] to avoid leaking the destroyed view hierarchy. */
    private var _binding: FragmentPetDetailBinding? = null
    private val binding get() = _binding!!

    private val petId: Long by lazy { requireArguments().getLong(ARG_PET_ID) }

    private val viewModel: PetDetailViewModel by viewModels {
        PetDetailViewModel.factory(petId)
    }

    /**
     * The Activity-wide record of who is signed in, used here only for the profile photo drawn in
     * the shared header — this screen's own data is about the pet, not the account. Scoped to the
     * Activity, so it is the same instance every other screen reads the photo from.
     */
    private val sessionViewModel: SessionViewModel by activityViewModels { SessionViewModel.Factory }

    /**
     * Guards against re-decoding the same photo on every state emission.
     *
     * The state re-emits whenever anything on the screen changes — a task ticked on another tab, a
     * routine paused — and without this check the photo would be read from disk again each time,
     * flashing the placeholder for the result that is already showing. Cleared in [onDestroyView]
     * so a rebuilt view draws the photo again.
     */
    private var renderedPhotoPath: String? = null

    /**
     * The routine cards' adapter. Holds no view or coroutine scope of its own, so — unlike the Pets
     * tab's photo adapter — it can safely live as long as the fragment and be re-attached to each new
     * view.
     */
    private val routineAdapter = RoutineAdapter(
        onRoutineClick = { routine -> openRoutineDetail(routine.id) },
        onEditClick = { routine -> openRoutineForm(routineId = routine.id) },
        onToggleActiveClick = { routine -> viewModel.toggleRoutineActive(routine) },
        onDeleteClick = { routine -> confirmDeleteRoutine(routine) }
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

        // The header shared with the tabs. Its title starts empty and becomes the pet's first name
        // as soon as the pet loads (see render). The badge opens the profile screen, as it does on
        // the tabs — this screen hides the shared toolbar too, and with it the overflow menu that
        // was the other way there.
        binding.header.setUp(
            title = "",
            onBadgeClick = ::openNotifications,
            onMonogramClick = ::openProfile
        )

        // Keeps the header's mark showing the user's profile photo, falling back to the app's
        // monogram when there is none. See TabHeader.bindHeaderProfilePhoto.
        bindHeaderProfilePhoto(binding.header, sessionViewModel)

        setUpRecyclerView()
        setUpClickListeners()
        observeViewModel()
    }

    private fun setUpRecyclerView() {
        binding.routinesRecyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = routineAdapter
            // Deliberately not setHasFixedSize(true): this list sits inside a NestedScrollView with
            // wrap_content height, so it must be allowed to re-measure as routines come and go.
        }
    }

    private fun setUpClickListeners() {
        // The back button on the photo stands in for the toolbar's up arrow, which is hidden here.
        // navigateUp returns to whichever screen opened the pet — the Pets tab or the dashboard.
        binding.backButton.setOnClickListener { findNavController().navigateUp() }

        binding.addRoutineButton.setOnClickListener { openRoutineForm(routineId = null) }

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
            // The owner comes from the loaded pet rather than being passed down from the previous
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

    /**
     * Opens the routine form: empty for the "+" button, filled in for a card's "Edit".
     *
     * One destination serves both, told apart by whether a routine id is passed — the same convention
     * the pet, task and expense forms use. With no id the argument falls back to its nav-graph default
     * of -1, which the form reads as "create".
     */
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

    /**
     * Asks before deleting a routine.
     *
     * Deleting also removes every task inside it and cannot be undone, so the dialog spells out that
     * consequence and points at Pause — in the same menu the user has just come from — as the
     * reversible alternative, since that is often what someone reaching for delete actually wants.
     */
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

    /**
     * Collects state for as long as the screen is visible.
     *
     * `repeatOnLifecycle(STARTED)` starts collecting when the screen appears and cancels when it
     * stops, so no work happens in the background and no view is touched after it is destroyed.
     */
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
        binding.header.showPetCount(state.petCount)

        state.pet?.let { pet ->
            bindHeroCard(pet, state)
            bindAboutCard(pet, state.ownerName)
        }

        routineAdapter.submitList(state.routines)

        // The list and the empty state occupy the same slot, so exactly one shows. Both stay hidden
        // while loading, which stops "No care routines yet" flashing up before the first result.
        binding.routinesRecyclerView.isVisible = state.routines.isNotEmpty()
        binding.emptyRoutinesContainer.isVisible = state.hasNoRoutines
    }

    /** Fills the photo card: the photo, species chip, gender symbol, full name and three pills. */
    private fun bindHeroCard(pet: Pet, state: PetDetailUiState) {
        renderPhoto(pet.photoPath)

        binding.petNameText.text = pet.name

        // The species enum stores database values (DOG, CAT), so it is translated to a readable label
        // first. The chip's style upper-cases it at draw time.
        binding.speciesChip.text = getString(pet.species.labelRes())

        bindGenderBadge(pet.gender)

        // Age is derived from the date of birth rather than stored — see ui.common.formatAge — and
        // uses the long form ("3 years old") the design shows. Each of the two fact pills hides itself
        // when its fact was never recorded; the Flow in the layout closes the gap it leaves.
        val age = pet.dateOfBirth?.let { formatAge(requireContext(), it) }
        binding.agePill.text = age.orEmpty()
        binding.agePill.isVisible = age != null

        // Through the shared formatter, so this pet's weight reads exactly as it does on the Pets and
        // Home cards ("25 kg", "4.5 kg") — see ui/common/WeightFormatting.kt.
        val weight = pet.weightKg?.let {
            formatWeightWithUnit(requireContext(), it, state.weightUnit)
        }
        binding.weightPill.text = weight.orEmpty()
        binding.weightPill.isVisible = weight != null

        binding.statusPill.text = statusText(state)
    }

    /**
     * The yellow pill's wording: "Nothing due today", "2 left today" — or "All done today".
     *
     * The third message is the dashboard's, reused so that a pet whose tasks are all ticked off reads
     * the same here as on the card the user tapped to arrive. Without it that case would read
     * "0 left today", which looks like a bug on a day that was actually completed.
     */
    private fun statusText(state: PetDetailUiState): String = when {
        state.tasksOutstanding > 0 -> resources.getQuantityString(
            R.plurals.home_pet_tasks_left,
            state.tasksOutstanding,
            state.tasksOutstanding
        )

        state.tasksDueToday > 0 -> getString(R.string.home_pet_all_done)

        else -> getString(R.string.home_pet_nothing_due)
    }

    /**
     * Shows the Mars symbol in blue for a male pet, the Venus symbol in pink for a female one, and
     * nothing at all when the gender is unknown.
     *
     * Hidden rather than showing a "?" for unknown: a question mark on the photo would read as
     * something the user needs to fix, when an unrecorded gender is perfectly normal.
     *
     * The tint is applied through [ImageViewCompat] so it works identically back to minSdk 24, and the
     * content description is set with it, since TalkBack cannot describe a symbol from its shape.
     */
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

    /**
     * Fills the "About" card's five rows.
     *
     * Every row is always visible and an empty value becomes "None recorded", so the card keeps the
     * same shape for every pet — see the note on the card in fragment_pet_detail.xml.
     */
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

    /**
     * Loads and shows the pet's photo, or the placeholder when there is none.
     *
     * The load itself is shared with the list cards, which need the same care about a view being
     * rebound mid-decode; see ui/common/PetPhotoBinder.kt.
     */
    private fun renderPhoto(path: String?) {
        if (path == renderedPhotoPath) return
        renderedPhotoPath = path

        binding.petPhotoImage.loadPetPhoto(path, viewLifecycleOwner.lifecycleScope)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Detach the adapter from the dying RecyclerView: the adapter outlives the view, and the
        // RecyclerView would otherwise be kept alive through the adapter's observer list.
        binding.routinesRecyclerView.adapter = null
        renderedPhotoPath = null
        _binding = null
    }

    companion object {
        /** Navigation argument name; must match the one declared in nav_graph.xml. */
        const val ARG_PET_ID = "petId"
    }
}
