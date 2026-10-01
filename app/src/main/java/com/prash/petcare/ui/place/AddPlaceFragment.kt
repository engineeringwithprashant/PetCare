package com.prash.petcare.ui.place

import android.os.Bundle
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
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import com.prash.petcare.R
import com.prash.petcare.data.local.entity.PlaceCategory
import com.prash.petcare.databinding.FragmentAddPlaceBinding
import com.prash.petcare.ui.common.ChipOption
import com.prash.petcare.ui.common.PhoneNumberInput
import com.prash.petcare.ui.common.anchorAboveNavBar
import com.prash.petcare.ui.common.bindHeaderFromSession
import com.prash.petcare.ui.common.bindOptions
import com.prash.petcare.ui.common.emojiLabel
import com.prash.petcare.ui.common.formatTimeOfDay
import com.prash.petcare.ui.common.labelRes
import com.prash.petcare.ui.common.openExternalLink
import com.prash.petcare.ui.common.openProfile
import com.prash.petcare.ui.common.setUp
import com.prash.petcare.ui.session.SessionViewModel
import kotlinx.coroutines.launch

/**
 * "Add a place": save the vet, groomer, park or shop — the one you are standing in, or any other.
 *
 * In MVVM this is the **View**. [AddPlaceViewModel] holds the form and writes the place, and the
 * [PlaceLocationPicker] it owns holds where that place is; this class draws both, formats their text
 * (which needs a Context) and owns the three things that genuinely need an Activity — the location
 * permission prompt, the time pickers and navigation.
 *
 * **The position has two equal sources, and the screen never prefers one.** "I'M HERE" over the map
 * takes the device's position; moving the map under its fixed centre pin chooses anywhere else. A user
 * who presses "I'm here" and then pans two streets over saves the two streets over, because nothing on
 * this screen re-reads the device unless asked to. That rule lives in [PlaceLocationPicker] rather than
 * here, so the full place form obeys it too.
 *
 * **Everything is on this one screen.** There is no separate location page to travel to and back from;
 * the map, its controls, the address it resolves and the fields describing the place are one scroll.
 *
 * **Why this screen draws the shared tab header rather than taking the app bar's title.** It is a step
 * *deeper* into the Places tab rather than a step sideways out of it: the bottom bar keeps Places lit
 * the whole time (see NavTab.PLACES), and the header carrying "Add place" is what makes that read as
 * one journey rather than as two screens. MainActivity hides the shared app bar here for the same
 * reason it does on the tabs — see its updateChromeFor.
 */
class AddPlaceFragment : Fragment() {

    /** Nulled in [onDestroyView] to avoid leaking the destroyed view hierarchy. */
    private var _binding: FragmentAddPlaceBinding? = null
    private val binding get() = _binding!!

    /** Which account is signed in — shared by every screen, see [SessionViewModel]. */
    private val sessionViewModel: SessionViewModel by activityViewModels { SessionViewModel.Factory }

    private val viewModel: AddPlaceViewModel by viewModels {
        AddPlaceViewModel.factory(ownerId = sessionViewModel.userId.value)
    }

    /**
     * The country selector and number box, driven as one control.
     *
     * Created in [onViewCreated] and dropped in [onDestroyView] with the binding, since it holds views
     * from this hierarchy.
     */
    private var phoneInput: PhoneNumberInput? = null

    /**
     * Location access, asked for only when one of the two current-location controls is pressed.
     *
     * A property so it registers its launcher while the fragment is being created, which is the only
     * time Android allows it. Both answers are ordinary: granting one takes the fix that was asked
     * for, refusing puts a line on screen and leaves the map to be moved by hand.
     */
    private val locationPermission = LocationPermissionRequester(
        fragment = this,
        rationaleMessage = R.string.permission_location_capture_message,
        onGranted = { viewModel.picker.useCurrentLocation() },
        onDenied = { viewModel.picker.onPermissionDenied() }
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAddPlaceBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // The same header the Places tab draws, with this screen's name. The badge and monogram lead
        // where they do everywhere else.
        binding.header.setUp(
            title = R.string.add_place_header_title,
            onBadgeClick = { findNavController().navigate(R.id.notificationsFragment) },
            onMonogramClick = ::openProfile
        )
        bindHeaderFromSession(binding.header, sessionViewModel)

        // The two phone boxes behave as one control. Editing either clears the error left by a
        // previous save attempt.
        phoneInput = PhoneNumberInput(
            countryLayout = binding.phoneCountryLayout,
            countryInput = binding.phoneCountryInput,
            numberLayout = binding.phoneInputLayout,
            numberInput = binding.phoneInput,
            onChanged = { viewModel.clearPhoneError() }
        )

        setUpFields()
        setUpButtons()
        setUpLocationPicker()
        observeViewModel()
    }

    // ---------------------------------------------------------------------------------------
    // Wiring
    // ---------------------------------------------------------------------------------------

    /**
     * Attaches the text boxes.
     *
     * Each reports what was typed to the ViewModel, which is where the value lives — the name drives
     * the summary card and the save button, and the address box is written into by the map's own
     * lookup, so neither box can be the only one who knows its value. See the note on
     * [AddPlaceFormState] for why this screen departs from the app's other forms in that respect.
     *
     * The category pills are not here: they are rebuilt from state in [renderForm], because a chip
     * row's selection is drawn from the state rather than held by the view.
     */
    private fun setUpFields() {
        binding.nameInput.doOnTextChanged { text, _, _, _ ->
            viewModel.onNameChanged(text?.toString().orEmpty())
        }
        binding.areaInput.doOnTextChanged { text, _, _, _ ->
            viewModel.onAreaChanged(text?.toString().orEmpty())
        }
        binding.noteInput.doOnTextChanged { text, _, _, _ ->
            viewModel.onNoteChanged(text?.toString().orEmpty())
        }

        // Picker-backed fields: focusable for keyboard and switch access, but no soft keyboard.
        binding.openingTimeInput.showSoftInputOnFocus = false
        binding.closingTimeInput.showSoftInputOnFocus = false
        binding.openingTimeInput.setOnClickListener {
            showTimePicker(
                titleRes = R.string.place_opening_picker_title,
                current = viewModel.uiState.value.form.openingMinutes,
                default = DEFAULT_OPENING
            ) { hour, minute -> viewModel.onOpeningTimeSelected(hour, minute) }
        }
        binding.closingTimeInput.setOnClickListener {
            showTimePicker(
                titleRes = R.string.place_closing_picker_title,
                current = viewModel.uiState.value.form.closingMinutes,
                default = DEFAULT_CLOSING
            ) { hour, minute -> viewModel.onClosingTimeSelected(hour, minute) }
        }
    }

    private fun setUpButtons() {
        binding.backButton.setOnClickListener { findNavController().navigateUp() }
        binding.clearHoursButton.setOnClickListener { viewModel.clearHours() }
        binding.savePlaceButton.setOnClickListener { save() }
    }

    /**
     * Hands every gesture on the map straight to the picker that owns the decision.
     *
     * Nothing is interpreted here, which is the point: this fragment holds no coordinate of its own, so
     * the map and the card under it cannot come to disagree about where the place is. The one thing
     * this side keeps is the link out of the map's credits, which needs an Intent.
     */
    private fun setUpLocationPicker() {
        val picker = viewModel.picker

        binding.locationPicker.onUseCurrentLocation = { picker.useCurrentLocation() }
        binding.locationPicker.onCentreChangedByUser = { picker.onMapCentreChanged(it) }
        binding.locationPicker.onSearchQueryChanged = { picker.onSearchQueryChanged(it) }
        binding.locationPicker.onSearchResultSelected = { picker.onSearchResultSelected(it) }
        binding.locationPicker.onSearchFocusChanged = { hasFocus ->
            if (hasFocus) picker.onSearchFocused() else picker.onSearchDismissed()
        }
        binding.locationPicker.onMapTypeSelected = { picker.onMapTypeSelected(it) }
        binding.locationPicker.onMapAvailabilityChanged = { picker.onMapAvailabilityChanged(it) }
        binding.locationPicker.onExternalLink = { url -> openLink(url) }
    }

    /**
     * Opens a Material time picker in the device's own 12- or 24-hour style, starting from the time
     * already chosen or a sensible default.
     */
    private fun showTimePicker(
        titleRes: Int,
        current: Int?,
        default: Int,
        onPicked: (Int, Int) -> Unit
    ) {
        val start = current ?: default
        val picker = MaterialTimePicker.Builder()
            .setTitleText(getString(titleRes))
            .setTimeFormat(
                if (DateFormat.is24HourFormat(requireContext())) {
                    TimeFormat.CLOCK_24H
                } else {
                    TimeFormat.CLOCK_12H
                }
            )
            .setHour(start / 60)
            .setMinute(start % 60)
            .build()
        picker.addOnPositiveButtonClickListener { onPicked(picker.hour, picker.minute) }
        picker.show(childFragmentManager, TIME_PICKER_TAG)
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.uiState.collect { render(it) } }
                launch { viewModel.events.collect { handleEvent(it) } }
                launch { viewModel.picker.events.collect { handleLocationEvent(it) } }
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Rendering
    // ---------------------------------------------------------------------------------------

    private fun render(state: AddPlaceUiState) {
        binding.locationPicker.render(state.location)
        binding.selectedLocation.render(state.location)
        renderForm(state)
    }

    /**
     * The fields, the summary and the save button.
     *
     * The text boxes are written back to only when they actually differ from the state. Setting an
     * EditText's text to what it already contains would move the cursor to the end mid-word, and this
     * state is re-emitted on every keystroke — so the guard is what makes the fields usable at all. It
     * is also what lets the address box be filled in by the map's lookup without fighting the user;
     * see [AddPlaceViewModel.onAreaChanged].
     */
    private fun renderForm(state: AddPlaceUiState) {
        val form = state.form

        setTextIfChanged(binding.nameInput, form.name, moveCursorToEnd = true)
        setTextIfChanged(binding.areaInput, form.area, moveCursorToEnd = true)
        setTextIfChanged(binding.noteInput, form.note, moveCursorToEnd = false)

        // 🏥 Vet · ✂️ Grooming · 🌳 Park · 🏪 Shop, built from the enum so adding a category needs no
        // layout change. Each chip leads with the emoji that will end up on the saved place's row, so
        // the symbol is part of the choice rather than a surprise afterwards. The chips are only
        // rebuilt when the options differ, so calling this on every emission costs nothing — see
        // ui/common/FilterChips.kt.
        binding.categoryChipGroup.bindOptions(
            options = PlaceCategory.entries.map {
                ChipOption(it.name, it.emojiLabel(requireContext()))
            },
            selectedKey = form.category.name,
            chipLayout = R.layout.item_category_chip,
            onSelected = { key ->
                PlaceCategory.entries.firstOrNull { it.name == key }
                    ?.let { viewModel.onCategorySelected(it) }
            }
        )

        val context = requireContext()
        binding.openingTimeInput.setText(
            form.openingMinutes?.let { formatTimeOfDay(context, it) }.orEmpty()
        )
        binding.closingTimeInput.setText(
            form.closingMinutes?.let { formatTimeOfDay(context, it) }.orEmpty()
        )
        binding.clearHoursButton.isVisible =
            form.openingMinutes != null || form.closingMinutes != null
        binding.hoursErrorText.isVisible = form.hoursError != null
        binding.hoursErrorText.text = form.hoursError?.let { getString(it) }
        binding.phoneInputLayout.error = form.phoneError?.let { getString(it) }

        binding.summaryText.text = buildSummary(state)

        // The label names the missing condition rather than only greying out, so a user who cannot
        // press the button knows whether to keep typing or to choose a point on the map.
        binding.savePlaceButton.setText(
            when {
                form.name.isBlank() -> R.string.add_place_save_needs_name
                state.location.selected == null -> R.string.add_place_save_needs_location
                else -> R.string.add_place_save
            }
        )
        binding.savePlaceButton.isEnabled = state.canSave
    }

    /**
     * Writes a value into a text box only if it is not already there — see [renderForm].
     *
     * The cursor is moved to the end for the boxes the state can change underneath the user (the
     * address, filled in by the map's lookup) and left alone for the note, where a multi-line box
     * jumping to the end would be the more jarring of the two.
     */
    private fun setTextIfChanged(
        input: com.google.android.material.textfield.TextInputEditText,
        value: String,
        moveCursorToEnd: Boolean
    ) {
        if (input.text?.toString() == value) return
        input.setText(value)
        if (moveCursorToEnd) input.setSelection(value.length)
    }

    /**
     * The two-line readback above the save button: what will be saved, then the position it will
     * carry.
     *
     * The second line is the screen being honest about itself. Until a point has been chosen it says
     * so plainly rather than showing a plausible-looking coordinate, which is the same reason the save
     * button stays disabled: an invented geotag is worse than an unsaved place, because it looks
     * exactly like a real one.
     */
    private fun buildSummary(state: AddPlaceUiState): String {
        val name = state.form.name.trim().ifEmpty { getString(R.string.add_place_summary_untitled) }

        val headline = getString(
            R.string.add_place_summary_line,
            name,
            getString(state.form.category.labelRes())
        )

        val selected = state.location.selected
        val position = if (selected == null) {
            getString(R.string.add_place_summary_waiting)
        } else {
            getString(
                R.string.add_place_summary_geotagged,
                formatCoordinates(requireContext(), selected.coordinates)
            )
        }

        return "$headline\n$position"
    }

    // ---------------------------------------------------------------------------------------
    // Actions
    // ---------------------------------------------------------------------------------------

    /**
     * Hands the ViewModel the two things it cannot produce: the phone number as the country selector
     * and the number box left it, and the note to store when the box was empty.
     *
     * Both are passed in rather than looked up there because they come from views or from string
     * resources, and a ViewModel holds neither.
     */
    private fun save() {
        val phone = phoneInput ?: return

        viewModel.save(
            phoneRegion = phone.regionCode(),
            phone = phone.nationalNumber(),
            defaultNote = getString(R.string.add_place_default_note)
        )
    }

    /**
     * The picker needs something only a fragment can do.
     *
     * The permission prompt is the interesting one: the picker decides *that* access is needed and
     * stops, this shows the dialog, and whichever way the user answers comes back through
     * [locationPermission] into the picker again. Nothing about the permission flow is duplicated
     * between the two Places forms as a result — they both forward the same event.
     */
    private fun handleLocationEvent(event: LocationPickerEvent) {
        when (event) {
            is LocationPickerEvent.MoveCamera ->
                _binding?.locationPicker?.moveCameraTo(event.at, event.zoom, event.animate)

            is LocationPickerEvent.RequestPermission -> locationPermission.request()

            is LocationPickerEvent.Message -> showMessage(getString(event.messageRes))
        }
    }

    private fun handleEvent(event: AddPlaceEvent) {
        when (event) {
            is AddPlaceEvent.Saved -> onSaved(event)
            is AddPlaceEvent.Message -> showMessage(getString(event.messageRes))
        }
    }

    /**
     * The place is in the database. Hands its id and name back to the Places tab and closes.
     *
     * The values travel through the *previous* back stack entry's [androidx.lifecycle.SavedStateHandle],
     * which is the Navigation component's way of returning a result: it belongs to the destination
     * being returned to, survives that screen being recreated, and needs neither a shared ViewModel nor
     * a fragment result listener. The tab uses them to select the new place — which re-centres its map
     * and points Directions at it — and to offer the undo. See PlacesFragment.observeSavedPlaceResult.
     *
     * The name is written first and the id second, because the tab watches the id: writing them the
     * other way round would wake it before the name had arrived.
     */
    private fun onSaved(event: AddPlaceEvent.Saved) {
        val controller = findNavController()
        controller.previousBackStackEntry?.savedStateHandle?.apply {
            set(RESULT_SAVED_PLACE_NAME, event.name)
            set(RESULT_SAVED_PLACE_ID, event.placeId)
        }
        controller.navigateUp()
    }

    /** Opens a link from inside the map in the browser — see PlacesFragment.openLink. */
    private fun openLink(url: String) {
        if (!openExternalLink(url)) showMessage(getString(R.string.places_no_maps_app))
    }

    /** Shows a brief message. Safe after the view is gone, when it does nothing. */
    private fun showMessage(text: String) {
        val root = _binding?.root ?: return
        Snackbar.make(root, text, Snackbar.LENGTH_LONG).anchorAboveNavBar(this).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // The map holds a GPU surface and a native renderer until it is told otherwise, so it is
        // torn down with the view rather than left to the garbage collector.
        binding.locationPicker.destroy()
        // Dropped with the binding: it holds views from this hierarchy.
        phoneInput = null
        _binding = null
    }

    companion object {

        /**
         * The keys the saved place travels back to the Places tab under. Named here, beside the code
         * that writes them, and read by PlacesFragment — one definition, so the two cannot drift.
         */
        const val RESULT_SAVED_PLACE_ID = "savedPlaceId"
        const val RESULT_SAVED_PLACE_NAME = "savedPlaceName"

        private const val TIME_PICKER_TAG = "add_place_time_picker"

        /** 08:00 and 18:00 — where most opening hours start, so the picker needs the fewest turns. */
        private const val DEFAULT_OPENING = 8 * 60
        private const val DEFAULT_CLOSING = 18 * 60
    }
}
