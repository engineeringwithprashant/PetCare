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
import com.google.android.material.timepicker.TimeFormat
import com.prash.petcare.R
import com.prash.petcare.data.local.entity.Place
import com.prash.petcare.data.local.entity.PlaceCategory
import com.prash.petcare.databinding.FragmentAddEditPlaceBinding
import com.prash.petcare.ui.common.formatTimeOfDay
import com.prash.petcare.ui.common.labelRes
import com.prash.petcare.ui.common.setToolbarTitle
import com.prash.petcare.ui.session.SessionViewModel
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Form for saving a new place, or changing or deleting a saved one.
 *
 * In MVVM this is the **View**: [AddEditPlaceViewModel] validates, captures the geotag and saves.
 * This class owns the three things that need an Activity — the location permission prompt, the two
 * time pickers and the delete confirmation — and forwards their answers.
 *
 * **The location permission flow**, which is the heart of this screen:
 *  1. The user presses "Capture current location".
 *  2. If location access is already granted, the fix starts immediately.
 *  3. Otherwise a dialog explains why the app wants it — to geotag this place so distances and
 *     directions work — and only then does the system prompt appear.
 *  4. **Refused:** nothing breaks. A Snackbar explains that a place cannot be saved without its
 *     location, and if the refusal is permanent it offers the settings screen. Everything typed so far
 *     stays in the form, so granting access later and coming back loses nothing.
 *  5. **Approximate only:** accepted, with a note under the coordinates that the geotag may be off.
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

    /** See the class comment for the flow this drives. A property so it registers during creation. */
    private val locationPermission = LocationPermissionRequester(
        fragment = this,
        rationaleMessage = R.string.permission_location_capture_message,
        onGranted = { viewModel.captureLocation() },
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
        setUpCategoryDropdown()
        setUpClickListeners()
        observeViewModel()
    }

    /**
     * Fills the category dropdown. Labels and [PlaceCategory.entries] come from the same list in the
     * same order, so a tapped position maps straight back to a constant — see AddEditTaskFragment.
     */
    private fun setUpCategoryDropdown() {
        val labels = PlaceCategory.entries.map { getString(it.labelRes()) }.toTypedArray()
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

        binding.captureLocationButton.setOnClickListener { locationPermission.request() }

        binding.savePlaceButton.setOnClickListener {
            viewModel.save(
                name = binding.nameInput.text?.toString().orEmpty(),
                area = binding.areaInput.text?.toString().orEmpty(),
                phone = binding.phoneInput.text?.toString().orEmpty(),
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
                launch { viewModel.events.collect { handleEvent(it) } }
            }
        }
    }

    private fun render(state: PlaceFormState) {
        setToolbarTitle(getString(if (state.isEditMode) R.string.edit_place_title else R.string.add_place_title))

        val context = requireContext()

        binding.nameInputLayout.error = state.nameError?.let { getString(it) }
        // `false` stops the dropdown filtering its own list as if the label had been typed.
        binding.categoryInput.setText(getString(state.category.labelRes()), false)

        binding.openingTimeInput.setText(state.openingMinutes?.let { formatTimeOfDay(context, it) }.orEmpty())
        binding.closingTimeInput.setText(state.closingMinutes?.let { formatTimeOfDay(context, it) }.orEmpty())
        binding.hoursErrorText.isVisible = state.hoursError != null
        binding.hoursErrorText.text = state.hoursError?.let { getString(it) }
        binding.clearHoursButton.isVisible = state.openingMinutes != null || state.closingMinutes != null

        renderLocation(state)

        binding.deletePlaceButton.isVisible = state.isEditMode
        val isBusy = state.isLoading || state.isSaving
        binding.progressIndicator.isVisible = isBusy
        binding.savePlaceButton.isEnabled = !isBusy
        binding.deletePlaceButton.isEnabled = !isBusy
    }

    /**
     * The geotag card: what has been captured, the button to capture it, and any warning.
     *
     * Coordinates are printed to five decimal places — about a metre, which is already finer than a
     * phone's GPS can honestly claim — and with [Locale.US] so they always use a decimal point, the way
     * coordinates are written in every maps app the user might compare them with.
     */
    private fun renderLocation(state: PlaceFormState) {
        val coordinates = state.coordinates

        binding.locationValueText.text = if (coordinates != null) {
            getString(
                R.string.place_location_coordinates,
                String.format(Locale.US, "%.5f", coordinates.latitude),
                String.format(Locale.US, "%.5f", coordinates.longitude)
            )
        } else {
            getString(R.string.place_location_none)
        }

        binding.captureLocationButton.isEnabled = !state.isLocating
        binding.captureLocationButton.setText(
            when {
                state.isLocating -> R.string.place_location_capturing
                coordinates != null -> R.string.place_location_recapture
                else -> R.string.place_location_capture
            }
        )
        binding.locationProgress.isVisible = state.isLocating

        binding.locationNoteText.isVisible = state.locationError != null || state.isApproximate
        binding.locationNoteText.text = when {
            state.locationError != null -> getString(state.locationError)
            else -> getString(R.string.place_location_approximate)
        }
    }

    private fun handleEvent(event: PlaceFormEvent) {
        when (event) {
            is PlaceFormEvent.PrefillForm -> prefill(event.place)
            // The Places tab observes Room, so it shows the change by itself once this closes.
            is PlaceFormEvent.Saved, is PlaceFormEvent.Deleted -> findNavController().navigateUp()
            is PlaceFormEvent.Message -> showMessage(getString(event.messageRes))
        }
    }

    /** Writes a loaded place into the text fields — once, from an event; see CareTaskEvent.PrefillForm. */
    private fun prefill(place: Place) {
        binding.nameInput.setText(place.name)
        binding.areaInput.setText(place.area.orEmpty())
        binding.phoneInput.setText(place.phone.orEmpty())
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
