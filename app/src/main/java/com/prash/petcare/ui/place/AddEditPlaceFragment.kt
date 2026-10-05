package com.prash.petcare.ui.place

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateFormat
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.timepicker.TimeFormat
import com.prash.petcare.R
import com.prash.petcare.data.local.entity.Place
import com.prash.petcare.data.local.entity.PlaceCategory
import com.prash.petcare.databinding.FragmentAddEditPlaceBinding
import com.prash.petcare.ui.common.PhoneNumberInput
import com.prash.petcare.ui.common.emojiLabel
import com.prash.petcare.ui.common.formatTimeOfDay
import com.prash.petcare.ui.common.openExternalLink
import com.prash.petcare.ui.common.setToolbarTitle
import com.prash.petcare.ui.session.SessionViewModel
import kotlinx.coroutines.launch

/** Form for saving a new place, or changing or deleting a saved one. */
class AddEditPlaceFragment : Fragment() {

    private var _binding: FragmentAddEditPlaceBinding? = null
    private val binding get() = _binding!!

    private val sessionViewModel: SessionViewModel by activityViewModels { SessionViewModel.Factory }

    private val placeId: Long by lazy {
        arguments?.getLong(ARG_PLACE_ID, AddEditPlaceViewModel.NO_PLACE_ID)
            ?: AddEditPlaceViewModel.NO_PLACE_ID
    }

    private val viewModel: AddEditPlaceViewModel by viewModels {
        AddEditPlaceViewModel.factory(ownerId = sessionViewModel.userId.value, placeId = placeId)
    }

    /** The country selector and number box, driven as one control. */
    private var phoneInput: PhoneNumberInput? = null

    /** The text this screen last wrote into the name and area boxes on the map's behalf. */
    private var lastSuggestedName: String? = null
    private var lastSuggestedArea: String? = null

    /** See the class comment for the flow this drives. A property so it registers during creation. */
    private val locationPermission = LocationPermissionRequester(
        fragment = this,
        rationaleMessage = R.string.permission_location_capture_message,
        onGranted = { viewModel.picker.useCurrentLocation() },
        onDenied = { permanently -> onLocationDenied(permanently) }
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAddEditPlaceBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // The two phone boxes behave as one control. Editing either clears the error left by a previous save attempt.
        phoneInput = PhoneNumberInput(
            countryLayout = binding.phoneCountryLayout,
            countryInput = binding.phoneCountryInput,
            numberLayout = binding.phoneInputLayout,
            numberInput = binding.phoneInput,
            onChanged = { viewModel.clearPhoneError() }
        )

        setUpCategoryDropdown()
        setUpClickListeners()
        setUpLocationPicker()
        observeViewModel()
    }

    /** Hands every gesture on the map straight to the picker that owns the decision. */
    private fun setUpLocationPicker() {
        val picker = viewModel.picker

        binding.locationPicker.onUseCurrentLocation = { picker.useCurrentLocation() }
        binding.locationPicker.onCentreChangedByUser = {
            picker.onMapCentreChanged(it)
            viewModel.clearLocationError()
        }
        binding.locationPicker.onSearchQueryChanged = { picker.onSearchQueryChanged(it) }
        // Choosing a searched place is choosing a location.
        binding.locationPicker.onSearchResultSelected = {
            picker.onSearchResultSelected(it)
            viewModel.clearLocationError()
        }
        binding.locationPicker.onSearchFocusChanged = { hasFocus ->
            if (hasFocus) picker.onSearchFocused() else picker.onSearchDismissed()
        }
        binding.locationPicker.onMapTypeSelected = { picker.onMapTypeSelected(it) }
        binding.locationPicker.onMapAvailabilityChanged = { picker.onMapAvailabilityChanged(it) }
        binding.locationPicker.onExternalLink = { url -> openLink(url) }
    }

    /** Fills the category dropdown. Labels and [PlaceCategory.entries] come from the same list in the same order. */
    private fun setUpCategoryDropdown() {
        // "🏥 Vet" — the emoji the place's row badge will carry, shown while the choice is being made.
        val labels = PlaceCategory.entries
            .map { it.emojiLabel(requireContext()) }
            .toTypedArray()
        binding.categoryInput.setSimpleItems(labels)
        binding.categoryInput.setOnItemClickListener { _, _, position, _ ->
            viewModel.onCategorySelected(PlaceCategory.entries[position])
        }
    }

    private fun setUpClickListeners() {
        // Picker-backed fields: focusable for keyboard and switch access, but no soft keyboard.
        binding.openingTimeInput.showSoftInputOnFocus = false
        binding.closingTimeInput.showSoftInputOnFocus = false
        binding.openingTimeInput.setOnClickListener {
            showTimePicker(R.string.place_opening_picker_title, viewModel.formState.value.openingMinutes, DEFAULT_OPENING) { h, m ->
                viewModel.onOpeningTimeSelected(h, m)
            }
        }
        binding.closingTimeInput.setOnClickListener {
            showTimePicker(R.string.place_closing_picker_title, viewModel.formState.value.closingMinutes, DEFAULT_CLOSING) { h, m ->
                viewModel.onClosingTimeSelected(h, m)
            }
        }
        binding.clearHoursButton.setOnClickListener { viewModel.clearHours() }

        binding.savePlaceButton.setOnClickListener {
            val phone = phoneInput ?: return@setOnClickListener

            viewModel.save(
                name = binding.nameInput.text?.toString().orEmpty(),
                area = binding.areaInput.text?.toString().orEmpty(),
                // The country and the digits go over separately: whether the two make a real number.
                phoneRegion = phone.regionCode(),
                phone = phone.nationalNumber(),
                notes = binding.notesInput.text?.toString().orEmpty()
            )
        }
        binding.deletePlaceButton.setOnClickListener { confirmDelete() }

        binding.nameInput.doOnTextChanged { _, _, _, _ -> viewModel.clearNameError() }
    }

    /** Opens a Material time picker in the device's own 12- or 24-hour style. */
    private fun showTimePicker(titleRes: Int, current: Int?, default: Int, onPicked: (Int, Int) -> Unit) {
        val start = current ?: default
        val picker = MaterialTimePicker.Builder()
            .setTitleText(getString(titleRes))
            .setTimeFormat(if (DateFormat.is24HourFormat(requireContext())) TimeFormat.CLOCK_24H else TimeFormat.CLOCK_12H)
            .setHour(start / 60)
            .setMinute(start % 60)
            .build()
        picker.addOnPositiveButtonClickListener { onPicked(picker.hour, picker.minute) }
        picker.show(childFragmentManager, TIME_PICKER_TAG)
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.formState.collect { render(it) } }
                launch { viewModel.picker.state.collect { renderLocation(it) } }
                launch { viewModel.picker.events.collect { handleLocationEvent(it) } }
                launch { viewModel.events.collect { handleEvent(it) } }
            }
        }
    }

    private fun render(state: PlaceFormState) {
        setToolbarTitle(getString(if (state.isEditMode) R.string.edit_place_title else R.string.add_place_title))

        val context = requireContext()

        binding.nameInputLayout.error = state.nameError?.let { getString(it) }
        // Under the number rather than the country, because the number is usually the part to change.
        binding.phoneInputLayout.error = state.phoneError?.let { getString(it) }
        // The same emoji-and-name pair the dropdown rows show, so the closed field matches the row that was picked.
        binding.categoryInput.setText(state.category.emojiLabel(context), false)

        binding.openingTimeInput.setText(state.openingMinutes?.let { formatTimeOfDay(context, it) }.orEmpty())
        binding.closingTimeInput.setText(state.closingMinutes?.let { formatTimeOfDay(context, it) }.orEmpty())
        binding.hoursErrorText.isVisible = state.hoursError != null
        binding.hoursErrorText.text = state.hoursError?.let { getString(it) }
        binding.clearHoursButton.isVisible = state.openingMinutes != null || state.closingMinutes != null

        binding.locationErrorText.isVisible = state.locationError != null
        binding.locationErrorText.text = state.locationError?.let { getString(it) }

        binding.deletePlaceButton.isVisible = state.isEditMode
        val isBusy = state.isLoading || state.isSaving
        binding.progressIndicator.isVisible = isBusy
        binding.savePlaceButton.isEnabled = !isBusy
        binding.deletePlaceButton.isEnabled = !isBusy
    }

    /** The map and the card under it, drawn by the shared picker's own code. */
    private fun renderLocation(state: LocationPickerState) {
        binding.locationPicker.render(state)
        binding.selectedLocation.render(state)
    }

    /** The picker needs something only a fragment can do. */
    private fun handleLocationEvent(event: LocationPickerEvent) {
        when (event) {
            is LocationPickerEvent.MoveCamera ->
                _binding?.locationPicker?.moveCameraTo(event.at, event.zoom, event.animate)

            is LocationPickerEvent.RequestPermission -> locationPermission.request()

            is LocationPickerEvent.Message -> showMessage(getString(event.messageRes))
        }
    }

    /** Opens a credit link from inside the map in the browser — see PlacesFragment.openLink. */
    private fun openLink(url: String) {
        if (!openExternalLink(url)) showMessage(getString(R.string.places_no_maps_app))
    }

    private fun handleEvent(event: PlaceFormEvent) {
        when (event) {
            is PlaceFormEvent.PrefillForm -> prefill(event.place)
            is PlaceFormEvent.SuggestPlaceDetails -> applySuggestions(event)
            // The Places tab observes Room, so it shows the change by itself once this closes.
            is PlaceFormEvent.Saved, is PlaceFormEvent.Deleted -> findNavController().navigateUp()
            is PlaceFormEvent.Message -> showMessage(getString(event.messageRes))
        }
    }

    /** Offers the map's findings to the two boxes that can take them. */
    private fun applySuggestions(event: PlaceFormEvent.SuggestPlaceDetails) {
        lastSuggestedName = applySuggestion(binding.nameInput, event.name, lastSuggestedName)
        lastSuggestedArea = applySuggestion(binding.areaInput, event.area, lastSuggestedArea)
    }

    /** Writes a suggestion into one box, if that box is this screen's to write in. */
    private fun applySuggestion(
        input: TextInputEditText,
        suggestion: String?,
        lastApplied: String?
    ): String? {
        val current = input.text?.toString().orEmpty()
        if (current.isNotBlank() && current != lastApplied) return lastApplied

        val text = suggestion.orEmpty()
        if (current != text) {
            input.setText(text)
            // Setting the text leaves the cursor at the start, which looks like a half-filled box.
            input.setSelection(text.length)
        }
        return suggestion
    }

    /** Writes a loaded place into the text fields — once, from an event; see CareTaskEvent.PrefillForm. */
    private fun prefill(place: Place) {
        binding.nameInput.setText(place.name)
        binding.areaInput.setText(place.area.orEmpty())
        // Splits the stored number back into a country and the local digits.
        phoneInput?.setStoredNumber(place.phone)
        binding.notesInput.setText(place.notes.orEmpty())
    }

    /** Deleting is permanent, so the dialog names the place rather than asking "are you sure?". */
    private fun confirmDelete() {
        val name = binding.nameInput.text?.toString()?.trim().orEmpty()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete_place_dialog_title)
            .setMessage(getString(R.string.delete_place_dialog_message, name))
            .setNegativeButton(R.string.delegate_cancel, null)
            .setPositiveButton(R.string.delete_place_dialog_confirm) { _, _ -> viewModel.delete() }
            .show()
    }

    /** Location was refused. The form stays exactly as it was — nothing typed is lost. */
    private fun onLocationDenied(permanently: Boolean) {
        val root = _binding?.root ?: return
        val snackbar = Snackbar.make(root, R.string.permission_location_capture_denied, Snackbar.LENGTH_LONG)
        if (permanently) {
            snackbar.setAction(R.string.permission_open_settings) {
                startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", requireContext().packageName, null)
                    )
                )
            }
        }
        snackbar.show()
    }

    private fun showMessage(text: String) {
        val root = _binding?.root ?: return
        Snackbar.make(root, text, Snackbar.LENGTH_LONG).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // The map holds a GPU surface and a native renderer until it is told otherwise.
        binding.locationPicker.destroy()
        // Dropped with the binding: it holds views from this hierarchy.
        phoneInput = null
        // And these describe the text in two of those views — see the note on them.
        lastSuggestedName = null
        lastSuggestedArea = null
        _binding = null
    }

    companion object {
        /** Navigation argument name; must match nav_graph.xml. */
        const val ARG_PLACE_ID = "placeId"

        private const val TIME_PICKER_TAG = "place_time_picker"

        /** 08:00 and 18:00 — where most opening hours start, so the picker needs the fewest turns. */
        private const val DEFAULT_OPENING = 8 * 60
        private const val DEFAULT_CLOSING = 18 * 60
    }
}
