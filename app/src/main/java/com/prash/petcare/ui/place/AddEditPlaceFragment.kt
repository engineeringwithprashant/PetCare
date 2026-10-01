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

/**
 * Form for saving a new place, or changing or deleting a saved one.
 *
 * In MVVM this is the **View**: [AddEditPlaceViewModel] validates and saves, and the
 * [PlaceLocationPicker] it owns holds where the place is. This class owns the three things that need
 * an Activity — the location permission prompt, the two time pickers and the delete confirmation — and
 * forwards their answers.
 *
 * **The map is the same one the Add a place screen uses**, which is the point: a place created there
 * and corrected here is worked with in exactly the same way. Opening an existing place centres it on
 * that place's own saved coordinates with the pin on the point, so the three things a person wants to
 * do to a saved position are all one gesture — keep it, drag the map to move it, or press "I'm here"
 * to replace it with where they are standing now.
 *
 * **The location permission flow**, which only runs when one of the two current-location controls is
 * pressed:
 *  1. The picker finds that access is not held and asks this class for it.
 *  2. A dialog explains why the app wants it — to geotag this place so distances and directions
 *     work — and only then does the system prompt appear.
 *  3. **Refused:** nothing breaks. A line says what was lost, and if the refusal is permanent the
 *     Snackbar offers the settings screen. Everything typed stays in the form, and the map is still
 *     there to be moved by hand — a refused permission costs the shortcut, not the screen.
 *  4. **Approximate only:** accepted, with a note under the coordinates that the position may be off.
 */
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

    /**
     * The country selector and number box, driven as one control.
     *
     * Created in [onViewCreated] and dropped in [onDestroyView] with the binding, since it holds
     * views from this hierarchy.
     */
    private var phoneInput: PhoneNumberInput? = null

    /**
     * The text this screen last wrote into the name and area boxes on the map's behalf.
     *
     * The record that keeps a suggestion from overwriting something a person typed — see
     * [applySuggestion] for how it is read. Dropped in [onDestroyView] along with the boxes they
     * describe, so a recreated view starts with both null and therefore treats whatever text the
     * fields restored as the user's — the safe end of the trade, since a filled box is then left
     * alone rather than overwritten.
     */
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

        // The two phone boxes behave as one control. Editing either clears the error left by a
        // previous save attempt, exactly as the name field does below.
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

    /**
     * Hands every gesture on the map straight to the picker that owns the decision.
     *
     * Line for line the same wiring as AddPlaceFragment, because it is the same control doing the same
     * job — the behaviour behind it lives in [PlaceLocationPicker] rather than being written twice.
     * Moving the map also clears a "location required" complaint left by an earlier save attempt,
     * which is what every other field on this form does when it is edited.
     */
    private fun setUpLocationPicker() {
        val picker = viewModel.picker

        binding.locationPicker.onUseCurrentLocation = { picker.useCurrentLocation() }
        binding.locationPicker.onCentreChangedByUser = {
            picker.onMapCentreChanged(it)
            viewModel.clearLocationError()
        }
        binding.locationPicker.onSearchQueryChanged = { picker.onSearchQueryChanged(it) }
        // Choosing a searched place is choosing a location, so it clears a "location required"
        // complaint from an earlier save attempt exactly as moving the map does.
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

    /**
     * Fills the category dropdown. Labels and [PlaceCategory.entries] come from the same list in the
     * same order, so a tapped position maps straight back to a constant — see AddEditTaskFragment.
     */
    private fun setUpCategoryDropdown() {
        // "🏥  Vet" — the emoji the place's row badge will carry, shown while the choice is being
        // made rather than only afterwards in the list.
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
                // The country and the digits go over separately: whether the two make a real number,
                // and what its international form is, are the ViewModel's decisions.
                phoneRegion = phone.regionCode(),
                phone = phone.nationalNumber(),
                notes = binding.notesInput.text?.toString().orEmpty()
            )
        }
        binding.deletePlaceButton.setOnClickListener { confirmDelete() }

        binding.nameInput.doOnTextChanged { _, _, _, _ -> viewModel.clearNameError() }
    }

    /**
     * Opens a Material time picker in the device's own 12- or 24-hour style, starting from the time
     * already chosen or a sensible default.
     */
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
        // Under the number rather than the country, because the number is usually the part to change
        // — and the message itself names the country as the other possibility.
        binding.phoneInputLayout.error = state.phoneError?.let { getString(it) }
        // The same emoji-and-name pair the dropdown rows show, so the closed field matches the row
        // that was picked. `false` stops the dropdown filtering its own list as if the label had
        // been typed.
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

    /**
     * The map and the card under it, drawn by the shared picker's own code so that the two Places
     * forms describe a selected location identically — see [SelectedLocationCard].
     */
    private fun renderLocation(state: LocationPickerState) {
        binding.locationPicker.render(state)
        binding.selectedLocation.render(state)
    }

    /**
     * The picker needs something only a fragment can do.
     *
     * The permission prompt goes through [locationPermission], which is where this screen's extra
     * behaviour lives: unlike the Add a place screen, a permanent refusal here offers the settings
     * page — see [onLocationDenied].
     */
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

    /**
     * Offers the map's findings to the two boxes that can take them.
     *
     * The map now recognises the place under its pin where it can — a pin on a hospital resolves to
     * "Patan Hospital" rather than to the city — and putting that in an empty name box saves the user
     * typing what the screen already knows. The address goes to the area box for the same reason.
     *
     * Delegated to [applySuggestion] per box, which is where the rule about what may be overwritten
     * lives, and the two [lastSuggested] fields are what let that rule tell this screen's own writing
     * from the user's.
     */
    private fun applySuggestions(event: PlaceFormEvent.SuggestPlaceDetails) {
        lastSuggestedName = applySuggestion(binding.nameInput, event.name, lastSuggestedName)
        lastSuggestedArea = applySuggestion(binding.areaInput, event.area, lastSuggestedArea)
    }

    /**
     * Writes a suggestion into one box, if that box is this screen's to write in.
     *
     * **Two conditions let it through, and they are the same condition said twice**: the box is empty,
     * or it still contains exactly the text this method last put there. Either way nothing a person
     * typed is at stake — and anything else is theirs, so the suggestion is dropped and every later
     * one with it, because [lastApplied] is returned unchanged and can then never match again.
     *
     * **Why the box's text is the record rather than an "edited" flag.** The typed text on this form
     * lives in its views, which is what lets the fields restore themselves after a rotation — so there
     * is no state here to carry a flag on. Comparing against what was last written answers the same
     * question from the only record that exists, and answers it conservatively: after a rotation
     * [lastApplied] is null again, so a filled box is simply left alone.
     *
     * A null suggestion clears a box this method filled, which is the half worth having: without it a
     * pin dragged off the hospital would leave "Patan Hospital" in the name box above a card reading
     * "Dropped pin", and saving would file a patch of road under the hospital's name.
     *
     * The fields stay ordinary editable inputs throughout — nothing here locks one, and typing over a
     * suggestion is what hands the box to the user for good.
     *
     * @return the suggestion now standing in the box, or [lastApplied] untouched if it was refused.
     */
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
            // Setting the text leaves the cursor at the start, which looks like a half-filled box
            // when the suggestion is longer than the view; the end is where a person would continue.
            input.setSelection(text.length)
        }
        return suggestion
    }

    /** Writes a loaded place into the text fields — once, from an event; see CareTaskEvent.PrefillForm. */
    private fun prefill(place: Place) {
        binding.nameInput.setText(place.name)
        binding.areaInput.setText(place.area.orEmpty())
        // Splits the stored number back into a country and the local digits. A number saved before
        // this form had a selector is bare digits and is read as a Nepali number, so an existing
        // place's number does not disappear the first time it is edited after this change.
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

    /**
     * Location was refused. The form stays exactly as it was — nothing typed is lost — and the user is
     * told why the place cannot be saved yet, with a way to the settings screen if the system will not
     * ask again.
     */
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
