package com.prash.petcare.ui.pet

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.google.android.material.datepicker.MaterialDatePicker
import com.prash.petcare.R
import com.prash.petcare.databinding.FragmentAddVaccinationBinding
import com.prash.petcare.ui.common.ChipOption
import com.prash.petcare.ui.common.bindHeaderFromSession
import com.prash.petcare.ui.common.bindOptions
import com.prash.petcare.ui.common.openProfile
import com.prash.petcare.ui.common.setUp
import com.prash.petcare.ui.session.SessionViewModel
import com.prash.petcare.util.formatDate
import kotlinx.coroutines.launch

/**
 * "Record a vaccination": the form reached from "+ RECORD" on a pet's vaccination card.
 *
 * In MVVM this is the **View**. [AddVaccinationViewModel] holds the form, derives the next due date
 * from the chosen interval and writes both the record and the optional checklist task; this class
 * draws that state, formats its text (which needs a Context) and owns the two things that genuinely
 * need one — the date pickers and navigation.
 *
 * **Why this screen draws the shared tab header rather than taking the app bar's title**, the same
 * reasoning as "Add a place": it is a step *deeper* into the Pets tab rather than a step sideways out
 * of it. The bottom bar keeps Pets lit the whole time (see NavTab.PETS) and the header carrying
 * "Vaccination" is what makes that read as one journey rather than as two screens. MainActivity hides
 * the shared app bar here for the same reason it does on the tabs — see its updateChromeFor.
 *
 * **On save the screen closes and hands its result back** through the previous destination's
 * SavedStateHandle, which is how the pet detail screen knows to show the confirmation and offer the
 * undo. The new row needs no help to appear: that screen observes Room, so the history has already
 * redrawn by the time the Snackbar goes up. See [PetDetailFragment.observeSavedVaccinationResult].
 */
class AddVaccinationFragment : Fragment() {

    /** Nulled in [onDestroyView] to avoid leaking the destroyed view hierarchy. */
    private var _binding: FragmentAddVaccinationBinding? = null
    private val binding get() = _binding!!

    private val petId: Long by lazy { requireArguments().getLong(ARG_PET_ID) }

    private val viewModel: AddVaccinationViewModel by viewModels {
        AddVaccinationViewModel.factory(petId)
    }

    /** Which account is signed in — shared by every screen, for the header's photo and badge. */
    private val sessionViewModel: SessionViewModel by activityViewModels { SessionViewModel.Factory }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAddVaccinationBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // The same header the Pets tab and the pet detail screen draw, with this screen's name. The
        // badge and monogram lead where they do everywhere else.
        binding.header.setUp(
            title = R.string.vaccination_header_title,
            onBadgeClick = { findNavController().navigate(R.id.notificationsFragment) },
            onMonogramClick = ::openProfile
        )
        bindHeaderFromSession(binding.header, sessionViewModel)

        setUpFields()
        setUpControls()
        observeViewModel()
    }

    // ---------------------------------------------------------------------------------------
    // Wiring
    // ---------------------------------------------------------------------------------------

    /**
     * Attaches the two text boxes and the two date boxes.
     *
     * Each text box reports what was typed to the ViewModel, which is where the value lives — the
     * summary and the save button both react to it, and neither could if the only copy were in the
     * field.
     *
     * The date boxes have their soft keyboard suppressed and open a picker instead, the same
     * treatment the pet and expense forms give theirs: they are focusable so they can still be
     * reached by keyboard, D-pad or switch access, but focusing one must not pop up a keyboard for a
     * field nobody types into.
     */
    private fun setUpFields() {
        binding.vaccineInput.doOnTextChanged { text, _, _, _ ->
            viewModel.onVaccineNameChanged(text?.toString().orEmpty())
        }

        binding.clinicInput.doOnTextChanged { text, _, _, _ ->
            viewModel.onClinicChanged(text?.toString().orEmpty())
        }

        binding.dateGivenInput.apply {
            showSoftInputOnFocus = false
            setOnClickListener { showDateGivenPicker() }
        }

        binding.dateDueInput.apply {
            showSoftInputOnFocus = false
            setOnClickListener { showDueDatePicker() }
        }
    }

    /** Attaches the repeat segments, the checklist row and the save button. */
    private fun setUpControls() {
        binding.backButton.setOnClickListener { findNavController().navigateUp() }

        // Each segment reports the interval it stands for. Written out rather than looped over
        // RepeatInterval.entries because the four views are named in the layout, and pairing them
        // here is what keeps "which view is which option" in one readable place.
        binding.repeatSixMonths.setOnClickListener {
            viewModel.onIntervalSelected(RepeatInterval.SIX_MONTHS)
        }
        binding.repeatOneYear.setOnClickListener {
            viewModel.onIntervalSelected(RepeatInterval.ONE_YEAR)
        }
        binding.repeatThreeYears.setOnClickListener {
            viewModel.onIntervalSelected(RepeatInterval.THREE_YEARS)
        }
        binding.repeatOnce.setOnClickListener {
            viewModel.onIntervalSelected(RepeatInterval.ONCE)
        }

        // The whole row, not just the switch — see the note in fragment_add_vaccination.xml.
        binding.checklistToggleRow.setOnClickListener { viewModel.onChecklistToggled() }

        binding.saveRecordButton.setOnClickListener { save() }
    }

    /**
     * Saves, passing in the two strings the checklist task is built from.
     *
     * They are handed over rather than looked up in the ViewModel because they come from string
     * resources, and a ViewModel holds no Context to resolve one with — the same arrangement the
     * "Add a place" screen uses. The title goes over as an *unfilled* format string, so the ViewModel
     * fills it with the name it has already trimmed rather than this class trimming it a second time
     * and the two having to agree.
     */
    private fun save() {
        viewModel.save(
            taskTitleFormat = getString(R.string.record_task_title),
            taskSupplies = getString(R.string.record_task_supplies)
        )
    }

    // ---------------------------------------------------------------------------------------
    // Date pickers
    // ---------------------------------------------------------------------------------------

    /** Opens the picker for the date given, starting from the date already chosen. */
    private fun showDateGivenPicker() {
        showDatePicker(
            titleRes = R.string.record_date_given_picker_title,
            selection = viewModel.formState.value.dateGiven,
            tag = DATE_GIVEN_PICKER_TAG,
            onPicked = viewModel::onDateGivenSelected
        )
    }

    /**
     * Opens the picker for the next due date.
     *
     * It starts from today rather than from the date given when there is no due date yet — which is
     * the "Once" case. Opening a year in the past would be a strange place to begin choosing a
     * *future* appointment from.
     */
    private fun showDueDatePicker() {
        showDatePicker(
            titleRes = R.string.record_next_due_picker_title,
            selection = viewModel.formState.value.dateDue
                ?: MaterialDatePicker.todayInUtcMilliseconds(),
            tag = DUE_DATE_PICKER_TAG,
            onPicked = viewModel::onDueDateSelected
        )
    }

    /**
     * The shared picker call.
     *
     * The tag matters: the FragmentManager uses it to restore an open picker after a rotation, and
     * two pickers sharing one tag would restore as the same dialog reporting to the wrong field.
     *
     * The selection Material hands back is midnight **UTC** on the chosen day, which is exactly the
     * form every date in this app is stored in — see [com.prash.petcare.util.VaccinationDates].
     */
    private fun showDatePicker(
        titleRes: Int,
        selection: Long,
        tag: String,
        onPicked: (Long) -> Unit
    ) {
        val picker = MaterialDatePicker.Builder.datePicker()
            .setTitleText(titleRes)
            .setSelection(selection)
            .build()

        picker.addOnPositiveButtonClickListener(onPicked)
        picker.show(childFragmentManager, tag)
    }

    // ---------------------------------------------------------------------------------------
    // State
    // ---------------------------------------------------------------------------------------

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.formState.collect { state -> render(state) }
                }
                launch {
                    viewModel.events.collect { event -> handleEvent(event) }
                }
            }
        }
        // The header's pet-count badge, from the session rather than from this form's own state.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            }
        }
    }

    /** Applies the current state to the views. */
    private fun render(state: VaccinationFormState) {
        binding.petNameText.text = getString(R.string.record_vaccination_for_pet, state.pet?.name.orEmpty())

        bindPresetChips(state)

        // The two dates. Written every time rather than only on a change, because they are not typed
        // into — there is no cursor to disturb and no keystroke to race.
        binding.dateGivenInput.setText(formatDate(state.dateGiven))
        binding.dateDueInput.setText(
            state.dateDue?.let { formatDate(it) } ?: getString(R.string.record_next_due_none)
        )

        bindRepeatSegments(state.interval)

        binding.checklistSwitch.isChecked = state.isChecklistOn
        // The checked state goes on the *row*, since that is the control TalkBack announces — see the
        // accessibility note in the layout.
        binding.checklistToggleRow.isSelected = state.isChecklistOn

        binding.summaryText.text = summaryFor(state)

        binding.saveRecordButton.isEnabled = state.canSave
    }

    /**
     * Fills the preset row, and marks the chip matching what is currently in the vaccine field.
     *
     * The chips are keyed on the string resource id rather than on the label, so the tapped chip maps
     * back to a resource rather than to displayed text — which would break the moment the app was
     * translated. That is the same reasoning as the expense form's category dropdown.
     *
     * The row hides itself entirely for a species the app has no presets for; see
     * [VaccinationFormState.presets] for why that is better than offering a generic list.
     */
    private fun bindPresetChips(state: VaccinationFormState) {
        binding.presetChipScroll.isVisible = state.presets.isNotEmpty()
        if (state.presets.isEmpty()) return

        val options = state.presets.map { labelRes ->
            ChipOption(key = labelRes.toString(), label = getString(labelRes))
        }

        // The chosen chip is whichever one's label equals what is in the field — so typing "Rabies"
        // by hand lights the Rabies chip, and editing one character un-lights it. The chip row
        // reports the *state* of the field rather than a separate choice of its own, which is what
        // stops the two disagreeing.
        val selectedKey = state.presets
            .firstOrNull { getString(it).equals(state.vaccineName.trim(), ignoreCase = true) }
            ?.toString()
            .orEmpty()

        binding.presetChipGroup.bindOptions(
            options = options,
            selectedKey = selectedKey,
            chipLayout = R.layout.item_vaccine_preset_chip
        ) { key ->
            key.toIntOrNull()?.let { labelRes -> setVaccineName(getString(labelRes)) }
        }
    }

    /**
     * Puts a preset's name into the vaccine field.
     *
     * The field is written directly as well as the ViewModel being told, because this is the one
     * route by which the name changes *without* the user typing — and [render] deliberately does not
     * write into the field on every state emission, which would move the cursor of someone mid-word.
     * [setSelection] leaves the caret at the end, where it would be if they had typed it.
     */
    private fun setVaccineName(name: String) {
        binding.vaccineInput.setText(name)
        binding.vaccineInput.setSelection(name.length)
        viewModel.onVaccineNameChanged(name)
    }

    /**
     * Lights the chosen repeat and leaves the other three plain.
     *
     * One boolean per segment, and nothing else: the fill (bg_profile_segment.xml) and the text
     * colour (res/color/profile_segment_text.xml) are both state lists keyed on `state_selected`, so
     * no colour is set in code here.
     */
    private fun bindRepeatSegments(interval: RepeatInterval) {
        binding.repeatSixMonths.isSelected = interval == RepeatInterval.SIX_MONTHS
        binding.repeatOneYear.isSelected = interval == RepeatInterval.ONE_YEAR
        binding.repeatThreeYears.isSelected = interval == RepeatInterval.THREE_YEARS
        binding.repeatOnce.isSelected = interval == RepeatInterval.ONCE
    }

    /**
     * Builds the readback above the save button.
     *
     * Two lines, or three when a clinic has been typed. The clinic line is added rather than left
     * blank, so an empty clinic does not leave a gap in the panel that reads as text failing to load.
     *
     * "Unnamed vaccine" stands in before anything is typed, which is more use than an empty first
     * line: it shows the shape of what will be saved while saying plainly what is still missing.
     */
    private fun summaryFor(state: VaccinationFormState): String {
        val name = state.vaccineName.trim().takeIf { it.isNotBlank() }
            ?: getString(R.string.record_summary_untitled)

        val dates = state.dateDue?.let { dueOn ->
            getString(R.string.record_summary_dates, formatDate(state.dateGiven), formatDate(dueOn))
        } ?: getString(R.string.record_summary_dates_no_repeat, formatDate(state.dateGiven))

        val lines = mutableListOf(
            getString(R.string.record_summary_name, name, state.petFirstName),
            dates
        )
        state.clinic.trim().takeIf { it.isNotBlank() }?.let(lines::add)

        return lines.joinToString(separator = "\n")
    }

    /**
     * The record is in the database. Hands its details back to the pet detail screen and closes.
     *
     * The values travel through the *previous* back stack entry's SavedStateHandle, which is the
     * Navigation component's way of returning a result: it belongs to the destination being returned
     * to, survives that screen being recreated, and needs neither a shared ViewModel nor a fragment
     * result listener. The same route "Add a place" uses to confirm a save on the Places tab.
     *
     * The three supporting values are written *before* the record id, because the pet detail screen
     * watches the id — writing them the other way round would wake it before the rest had arrived.
     */
    private fun handleEvent(event: AddVaccinationEvent) {
        when (event) {
            is AddVaccinationEvent.Saved -> {
                val controller = findNavController()
                controller.previousBackStackEntry?.savedStateHandle?.apply {
                    set(RESULT_VACCINE_NAME, event.vaccineName)
                    set(RESULT_PET_NAME, event.petName)
                    set(RESULT_TASK_ID, event.taskId)
                    set(RESULT_RECORD_ID, event.recordId)
                }
                controller.navigateUp()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        /** Navigation argument name; must match the one declared in nav_graph.xml. */
        const val ARG_PET_ID = "petId"

        /**
         * The keys the saved record travels back to the pet detail screen under. Named here, beside
         * the code that writes them, and read by PetDetailFragment — one definition, so the two
         * cannot drift.
         */
        const val RESULT_RECORD_ID = "savedVaccinationId"
        const val RESULT_VACCINE_NAME = "savedVaccinationName"
        const val RESULT_PET_NAME = "savedVaccinationPetName"
        const val RESULT_TASK_ID = "savedVaccinationTaskId"

        /** Tags the FragmentManager restores each picker under after a rotation. */
        private const val DATE_GIVEN_PICKER_TAG = "vaccination_date_given_picker"
        private const val DUE_DATE_PICKER_TAG = "vaccination_due_date_picker"
    }
}
