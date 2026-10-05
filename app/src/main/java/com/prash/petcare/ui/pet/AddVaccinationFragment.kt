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

/** "Record a vaccination": the form reached from "+ RECORD" on a pet's vaccination card. */
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

        // The same header the Pets tab and the pet detail screen draw, with this screen's name.
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

    // Wiring.

    /** Attaches the two text boxes and the two date boxes. */
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

        // Each segment reports the interval it stands for. Written out rather than looped over RepeatInterval.entries.
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

    /** Saves, passing in the two strings the checklist task is built from. */
    private fun save() {
        viewModel.save(
            taskTitleFormat = getString(R.string.record_task_title),
            taskSupplies = getString(R.string.record_task_supplies)
        )
    }

    // Date pickers.

    /** Opens the picker for the date given, starting from the date already chosen. */
    private fun showDateGivenPicker() {
        showDatePicker(
            titleRes = R.string.record_date_given_picker_title,
            selection = viewModel.formState.value.dateGiven,
            tag = DATE_GIVEN_PICKER_TAG,
            onPicked = viewModel::onDateGivenSelected
        )
    }

    /** Opens the picker for the next due date. */
    private fun showDueDatePicker() {
        showDatePicker(
            titleRes = R.string.record_next_due_picker_title,
            selection = viewModel.formState.value.dateDue
                ?: MaterialDatePicker.todayInUtcMilliseconds(),
            tag = DUE_DATE_PICKER_TAG,
            onPicked = viewModel::onDueDateSelected
        )
    }

    /** The shared picker call. */
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

    // State.

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

        // The two dates. Written every time rather than only on a change, because they are not typed.
        binding.dateGivenInput.setText(formatDate(state.dateGiven))
        binding.dateDueInput.setText(
            state.dateDue?.let { formatDate(it) } ?: getString(R.string.record_next_due_none)
        )

        bindRepeatSegments(state.interval)

        binding.checklistSwitch.isChecked = state.isChecklistOn
        // The checked state goes on the row, since that is the control TalkBack announces.
        binding.checklistToggleRow.isSelected = state.isChecklistOn

        binding.summaryText.text = summaryFor(state)

        binding.saveRecordButton.isEnabled = state.canSave
    }

    /** Fills the preset row, and marks the chip matching what is currently in the vaccine field. */
    private fun bindPresetChips(state: VaccinationFormState) {
        binding.presetChipScroll.isVisible = state.presets.isNotEmpty()
        if (state.presets.isEmpty()) return

        val options = state.presets.map { labelRes ->
            ChipOption(key = labelRes.toString(), label = getString(labelRes))
        }

        // The chosen chip is whichever one's label equals what is in the field.
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

    /** Puts a preset's name into the vaccine field. */
    private fun setVaccineName(name: String) {
        binding.vaccineInput.setText(name)
        binding.vaccineInput.setSelection(name.length)
        viewModel.onVaccineNameChanged(name)
    }

    /** Lights the chosen repeat and leaves the other three plain. */
    private fun bindRepeatSegments(interval: RepeatInterval) {
        binding.repeatSixMonths.isSelected = interval == RepeatInterval.SIX_MONTHS
        binding.repeatOneYear.isSelected = interval == RepeatInterval.ONE_YEAR
        binding.repeatThreeYears.isSelected = interval == RepeatInterval.THREE_YEARS
        binding.repeatOnce.isSelected = interval == RepeatInterval.ONCE
    }

    /** Builds the readback above the save button. */
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

    /** The record is in the database. Hands its details back to the pet detail screen and closes. */
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

        /** The keys the saved record travels back to the pet detail screen under. */
        const val RESULT_RECORD_ID = "savedVaccinationId"
        const val RESULT_VACCINE_NAME = "savedVaccinationName"
        const val RESULT_PET_NAME = "savedVaccinationPetName"
        const val RESULT_TASK_ID = "savedVaccinationTaskId"

        /** Tags the FragmentManager restores each picker under after a rotation. */
        private const val DATE_GIVEN_PICKER_TAG = "vaccination_date_given_picker"
        private const val DUE_DATE_PICKER_TAG = "vaccination_due_date_picker"
    }
}
