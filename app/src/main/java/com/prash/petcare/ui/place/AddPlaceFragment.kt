package com.prash.petcare.ui.place

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
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.prash.petcare.R
import com.prash.petcare.data.local.entity.PlaceCategory
import com.prash.petcare.databinding.FragmentAddPlaceBinding
import com.prash.petcare.ui.common.ChipOption
import com.prash.petcare.ui.common.anchorAboveNavBar
import com.prash.petcare.ui.common.bindHeaderProfilePhoto
import com.prash.petcare.ui.common.bindOptions
import com.prash.petcare.ui.common.formatDistanceKm
import com.prash.petcare.ui.common.labelRes
import com.prash.petcare.ui.common.openProfile
import com.prash.petcare.ui.common.setUp
import com.prash.petcare.ui.common.showPetCount
import com.prash.petcare.ui.session.SessionViewModel
import com.prash.petcare.util.Coordinates
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

/**
 * "Add a place": save a vet, groomer, park or shop **from anywhere**.
 *
 * In MVVM this is the **View**. [AddPlaceViewModel] owns the three methods' state, runs the search,
 * takes the GPS fix and writes the place; this class draws that state, formats its text (which needs
 * a Context) and owns the two things that genuinely need an Activity — the location permission prompt
 * and navigation.
 *
 * **The feature's rule, and where it lives in this file.** A place can be saved without being stood
 * in. Three methods fix its position, they are peers, and none of them is ever gated behind a
 * permission:
 *
 *  - **Search** — coordinates come back with the chosen result from the places lookup.
 *  - **Drop pin** — coordinates are projected from the marker's position on the map.
 *  - **I'm here** — coordinates come from the device's GPS. The only method that reads it.
 *
 * [onMethodSelected] is the only place that so much as mentions the permission, and refusing it
 * changes nothing but the contents of one card.
 *
 * **Why this screen draws the shared tab header rather than taking the app bar's title.** It is a
 * step *deeper* into the Places tab rather than a step sideways out of it: the bottom bar keeps
 * Places lit the whole time (see NavTab.PLACES), and the header carrying "Add place" is what makes
 * that read as one journey rather than as two screens. MainActivity hides the shared app bar here for
 * the same reason it does on the tabs — see its updateChromeFor.
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

    private val suggestionAdapter = PlaceSuggestionAdapter(
        onSuggestionClick = { suggestion -> viewModel.onSuggestionTapped(suggestion) }
    )

    /**
     * Location access for the "I'm here" method, and for nothing else on this screen.
     *
     * Asked for only when that method is chosen — never on arrival, and never for the other two. A
     * property so it registers its launcher while the fragment is being created, which is the only
     * time Android allows it.
     *
     * Both answers are ordinary: granting one starts the fix, refusing puts a sentence on the card.
     * Neither disables anything, which is what keeps all three methods reachable at all times.
     */
    private val locationPermission = LocationPermissionRequester(
        fragment = this,
        rationaleMessage = R.string.permission_location_capture_message,
        onGranted = { viewModel.requestCurrentLocation() },
        onDenied = { viewModel.onLocationDenied() }
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
        bindHeaderProfilePhoto(binding.header, sessionViewModel)

        binding.suggestionsRecyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = suggestionAdapter
        }

        setUpMethodPicker()
        setUpFields()
        setUpPinMap()

        binding.backButton.setOnClickListener { findNavController().navigateUp() }
        binding.savePlaceButton.setOnClickListener { save() }

        observeViewModel()
    }

    // ---------------------------------------------------------------------------------------
    // Wiring
    // ---------------------------------------------------------------------------------------

    private fun setUpMethodPicker() {
        binding.methodSearch.setOnClickListener { onMethodSelected(AddPlaceMethod.SEARCH) }
        binding.methodPin.setOnClickListener { onMethodSelected(AddPlaceMethod.PIN) }
        binding.methodHere.setOnClickListener { onMethodSelected(AddPlaceMethod.HERE) }
    }

    /**
     * A method was chosen.
     *
     * The only branch in the screen: choosing "I'm here" with no fix in hand is what asks for the
     * location permission, because that method is the one — the only one — that reads the device.
     * Choosing it again once a fix has arrived does not ask again; the position on the card is the
     * one that will be saved, and silently replacing it under the user would be worse than keeping
     * what they have already seen.
     */
    private fun onMethodSelected(method: AddPlaceMethod) {
        viewModel.selectMethod(method)

        if (method == AddPlaceMethod.HERE && viewModel.uiState.value.fix == null) {
            locationPermission.request()
        }
    }

    /**
     * Attaches the three text boxes.
     *
     * Each reports what was typed to the ViewModel, which is where the value lives — the name drives
     * the summary card and the save button, and the query drives the results, so the box cannot be
     * the only one who knows. See the note on [AddPlaceUiState] for why this screen departs from the
     * app's other forms in that respect.
     *
     * The category pills are not here: they are rebuilt from state in [renderSaveFields], because
     * unlike a text box a chip row's selection is not something the user's tap alone decides — tapping
     * a search result changes it too.
     */
    private fun setUpFields() {
        binding.searchInput.doOnTextChanged { text, _, _, _ ->
            viewModel.onQueryChanged(text?.toString().orEmpty())
        }
        binding.nameInput.doOnTextChanged { text, _, _, _ ->
            viewModel.onNameChanged(text?.toString().orEmpty())
        }
        binding.noteInput.doOnTextChanged { text, _, _, _ ->
            viewModel.onNoteChanged(text?.toString().orEmpty())
        }
    }

    /**
     * The map reports where the marker was dragged to, as percentages; the ViewModel turns those into
     * degrees and hands back the clamped position, which [renderPin] draws.
     *
     * The round trip matters: the view never decides where the marker may go, so the coordinates
     * being saved can never disagree with the marker being shown.
     */
    private fun setUpPinMap() {
        binding.pinMap.onPinMoved = { x, y -> viewModel.onPinMoved(x, y) }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.uiState.collect { render(it) } }
                launch { viewModel.petCount.collect { binding.header.showPetCount(it) } }
                launch { viewModel.events.collect { handleEvent(it) } }
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Rendering
    // ---------------------------------------------------------------------------------------

    private fun render(state: AddPlaceUiState) {
        renderMethodPicker(state)
        renderSearch(state)
        renderPin(state)
        renderHere(state)
        renderSaveFields(state)
    }

    /**
     * Lights the chosen segment and shows its panel.
     *
     * One boolean per segment and one per panel; the fill and the label colour follow from
     * `isSelected` through bg_profile_segment.xml and res/color/profile_segment_text.xml, so nothing
     * about how the control looks is decided here.
     */
    private fun renderMethodPicker(state: AddPlaceUiState) {
        binding.methodSearch.isSelected = state.method == AddPlaceMethod.SEARCH
        binding.methodPin.isSelected = state.method == AddPlaceMethod.PIN
        binding.methodHere.isSelected = state.method == AddPlaceMethod.HERE

        binding.searchPanel.isVisible = state.method == AddPlaceMethod.SEARCH
        binding.pinPanel.isVisible = state.method == AddPlaceMethod.PIN
        binding.herePanel.isVisible = state.method == AddPlaceMethod.HERE
    }

    /** The results, or the dashed card that names the way out when a query matches nothing. */
    private fun renderSearch(state: AddPlaceUiState) {
        suggestionAdapter.submitList(
            state.results.map { PlaceSuggestionRow(it, it.id == state.selectedSuggestion?.id) }
        )

        val hasResults = state.results.isNotEmpty()
        binding.suggestionsRecyclerView.isVisible = hasResults
        binding.noResultsText.isVisible = !hasResults
    }

    /**
     * The marker, its label and the live readout under the map.
     *
     * The coordinates are printed to four decimal places — about ten metres, which is as fine as a
     * point picked with a fingertip on a three-kilometre map can honestly claim — and with
     * [Locale.US] so they always use a decimal point, the way coordinates are written in every maps
     * app the user might compare them with.
     */
    private fun renderPin(state: AddPlaceUiState) {
        binding.pinMap.setPin(state.pinXPercent, state.pinYPercent)

        // The marker wears the name being typed, so the map shows what is being placed rather than a
        // generic pin; "New place" until there is one.
        binding.pinLabel.text = state.name.trim().ifEmpty { getString(R.string.add_place_pin_new) }

        binding.pinCoordinatesText.text = formatCoordinates(state.pinCoordinates)
        binding.pinDistanceText.text = getString(
            R.string.add_place_pin_distance,
            formatDistanceKm(requireContext(), state.pinDistanceKm)
        )
    }

    /**
     * The current-location card, in one of its three conditions: looking, found, or unavailable.
     *
     * "Unavailable" is drawn as a full answer rather than as an error — the card says what happened
     * and the screen carries on, because the other two methods save a place just as well. That is the
     * whole reason this method is a convenience rather than a requirement.
     */
    private fun renderHere(state: AddPlaceUiState) {
        val fix = state.fix

        when {
            state.isLocating -> {
                binding.hereAreaText.setText(R.string.add_place_here_locating)
                binding.hereCoordinatesText.isVisible = false
            }

            fix != null -> {
                binding.hereAreaText.text =
                    state.areaName ?: getString(R.string.add_place_here_area_unknown)
                binding.hereCoordinatesText.isVisible = true
                binding.hereCoordinatesText.text = formatFix(fix.accuracyMetres, fix.coordinates)
            }

            else -> {
                binding.hereAreaText.setText(R.string.add_place_here_unknown)
                binding.hereCoordinatesText.isVisible = true
                // Falls back to the refusal wording when the user dismissed the rationale dialog
                // without answering it, which leaves no message of its own.
                binding.hereCoordinatesText.setText(
                    state.locationMessage ?: R.string.add_place_here_denied
                )
            }
        }
    }

    /**
     * The name, category, note, summary and save button.
     *
     * The two text boxes are written back to only when they actually differ from the state. Setting
     * an EditText's text to what it already contains would move the cursor to the end mid-word, and
     * this state is re-emitted on every keystroke — so the guard is what makes the name field usable
     * at all, as well as what lets tapping a search result fill it.
     */
    private fun renderSaveFields(state: AddPlaceUiState) {
        if (binding.nameInput.text?.toString() != state.name) {
            binding.nameInput.setText(state.name)
            binding.nameInput.setSelection(state.name.length)
        }
        if (binding.noteInput.text?.toString() != state.note) {
            binding.noteInput.setText(state.note)
        }

        // Vet · Grooming · Park · Store, built from the enum so adding a category needs no layout
        // change. The chips themselves are only rebuilt when the options differ, so calling this on
        // every emission costs nothing — see ui/common/FilterChips.kt.
        binding.categoryChipGroup.bindOptions(
            options = PlaceCategory.entries.map { ChipOption(it.name, getString(it.labelRes())) },
            selectedKey = state.category.name,
            chipLayout = R.layout.item_category_chip,
            onSelected = { key ->
                PlaceCategory.entries.firstOrNull { it.name == key }
                    ?.let { viewModel.onCategorySelected(it) }
            }
        )

        binding.summaryText.text = buildSummary(state)
        binding.savePlaceButton.isEnabled = state.canSave
    }

    /**
     * The two-line readback above the save button: what will be saved, then where its position came
     * from.
     *
     * The provenance line is the screen being honest about itself. A place saved from a search
     * carries the lookup's coordinates, one from the map carries the pin's, and one from "I'm here"
     * carries the device's — and the user gets to see which before they commit.
     */
    private fun buildSummary(state: AddPlaceUiState): String {
        val name = state.name.trim().ifEmpty { getString(R.string.add_place_summary_untitled) }

        val headline = getString(
            R.string.add_place_summary_line,
            name,
            getString(state.category.labelRes())
        )

        val provenance = when (state.method) {
            AddPlaceMethod.SEARCH -> getString(
                if (state.selectedSuggestion != null || state.name.isNotBlank()) {
                    R.string.add_place_summary_search
                } else {
                    R.string.add_place_summary_search_pending
                }
            )

            AddPlaceMethod.PIN ->
                getString(R.string.add_place_summary_pin, formatCoordinates(state.pinCoordinates))

            AddPlaceMethod.HERE -> getString(
                R.string.add_place_summary_here,
                state.areaName
                    ?: state.fix?.coordinates?.let { formatCoordinates(it) }
                    ?: getString(R.string.add_place_here_unknown)
            )
        }

        return "$headline\n$provenance"
    }

    /** "27.6714° N, 85.3181° E" — see [renderPin] for why four decimals and [Locale.US]. */
    private fun formatCoordinates(coordinates: Coordinates): String = getString(
        R.string.add_place_coordinates,
        String.format(Locale.US, "%.4f", coordinates.latitude),
        String.format(Locale.US, "%.4f", coordinates.longitude)
    )

    /**
     * The same, with the device's accuracy after it: "27.6743° N, 85.3094° E · ±8 m accuracy".
     *
     * Rounded to whole metres, because a tenth of a metre is a precision no phone has, and dropped
     * altogether when the platform reported none rather than printing "±0 m" — which would claim
     * perfect accuracy for a fix that simply did not say.
     */
    private fun formatFix(accuracyMetres: Float?, coordinates: Coordinates): String {
        val position = formatCoordinates(coordinates)
        if (accuracyMetres == null) return position

        return getString(
            R.string.add_place_here_accuracy,
            position,
            accuracyMetres.roundToInt().toString()
        )
    }

    // ---------------------------------------------------------------------------------------
    // Saving
    // ---------------------------------------------------------------------------------------

    /**
     * Hands the two pieces of wording the record needs to the ViewModel.
     *
     * They are passed in rather than looked up there because they are *strings*, and a ViewModel
     * holds no Context to resolve one with — the same arrangement the Places tab uses when it
     * prefills a "Book a visit" task.
     */
    private fun save() {
        viewModel.save(
            savedByMeta = getString(R.string.add_place_saved_meta),
            defaultNote = getString(R.string.add_place_default_note)
        )
    }

    /**
     * The place is in the database. Hands its id and name back to the Places tab and closes.
     *
     * The values travel through the *previous* back stack entry's [androidx.lifecycle.SavedStateHandle],
     * which is the Navigation component's way of returning a result: it belongs to the destination
     * being returned to, survives that screen being recreated, and needs neither a shared ViewModel
     * nor a fragment result listener. The tab uses them to feature the new place and to offer the
     * undo — see PlacesFragment.observeSavedPlaceResult.
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

    private fun handleEvent(event: AddPlaceEvent) {
        when (event) {
            is AddPlaceEvent.Saved -> onSaved(event)
            is AddPlaceEvent.Message -> showMessage(getString(event.messageRes))
        }
    }

    /** Shows a brief message. Safe after the view is gone, when it does nothing. */
    private fun showMessage(text: String) {
        val root = _binding?.root ?: return
        Snackbar.make(root, text, Snackbar.LENGTH_LONG).anchorAboveNavBar(this).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // The adapter and the map's callback outlive the view as fragment properties; clearing them
        // stops the destroyed hierarchy being kept alive through either.
        binding.suggestionsRecyclerView.adapter = null
        binding.pinMap.onPinMoved = null
        _binding = null
    }

    companion object {

        /**
         * The keys the saved place travels back to the Places tab under. Named here, beside the code
         * that writes them, and read by PlacesFragment — one definition, so the two cannot drift.
         */
        const val RESULT_SAVED_PLACE_ID = "savedPlaceId"
        const val RESULT_SAVED_PLACE_NAME = "savedPlaceName"
    }
}
