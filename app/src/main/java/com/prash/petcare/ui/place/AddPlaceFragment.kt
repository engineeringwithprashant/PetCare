package com.prash.petcare.ui.place

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
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
import com.prash.petcare.R
import com.prash.petcare.data.local.entity.PlaceCategory
import com.prash.petcare.databinding.FragmentAddPlaceBinding
import com.prash.petcare.ui.common.ChipOption
import com.prash.petcare.ui.common.anchorAboveNavBar
import com.prash.petcare.ui.common.bindHeaderProfilePhoto
import com.prash.petcare.ui.common.bindOptions
import com.prash.petcare.ui.common.emojiLabel
import com.prash.petcare.ui.common.formatDistanceKm
import com.prash.petcare.ui.common.labelRes
import com.prash.petcare.ui.common.openExternalLink
import com.prash.petcare.ui.common.openProfile
import com.prash.petcare.ui.common.setUp
import com.prash.petcare.ui.common.showPetCount
import com.prash.petcare.ui.session.SessionViewModel
import com.prash.petcare.util.Coordinates
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

/**
 * "Add a place": save the vet, groomer, park or shop you are **standing in**.
 *
 * In MVVM this is the **View**. [AddPlaceViewModel] takes the GPS fix, holds the form and writes the
 * place; this class draws that state, formats its text (which needs a Context) and owns the two
 * things that genuinely need an Activity — the location permission prompt and navigation.
 *
 * **One source of position, and it is the device.** There is no search box and no pin to drag: the
 * user is at the place, so the phone already knows where it is and the only thing it cannot know is
 * what to call it. The fix is requested the moment the screen opens, so by the time a name has been
 * typed the position is usually already in — which is what makes this a two-tap save.
 *
 * **What happens when there is no fix.** The screen says which of the three reasons applies, offers
 * to try again, and — when trying again cannot help, because access was refused or the device has no
 * location capability — offers a clearly-labelled demo position so the flow is never a dead end. Save
 * stays disabled until there is *some* position, because a place with no coordinates cannot be drawn
 * on the map or navigated to.
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

    /**
     * Location access, which on this screen is the whole point rather than an extra.
     *
     * A property so it registers its launcher while the fragment is being created, which is the only
     * time Android allows it. Both answers are ordinary: granting one starts the fix, refusing puts a
     * reason and a way forward on the card.
     */
    private val locationPermission = LocationPermissionRequester(
        fragment = this,
        rationaleMessage = R.string.permission_location_capture_message,
        onGranted = { viewModel.locate() },
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

        setUpFields()
        setUpButtons()
        observeViewModel()

        // Ask for the position straight away — see the class comment for why this screen breaks the
        // app's usual "never prompt on arrival" rule. Only on a first creation: after a rotation the
        // ViewModel still holds the fix, and asking again would replace a position the user has
        // already read with a fresh one they have not.
        if (savedInstanceState == null) requestLocation()
    }

    // ---------------------------------------------------------------------------------------
    // Wiring
    // ---------------------------------------------------------------------------------------

    /**
     * Attaches the two text boxes.
     *
     * Each reports what was typed to the ViewModel, which is where the value lives — the name drives
     * the summary card and the save button, so the box cannot be the only one who knows it. See the
     * note on [AddPlaceUiState] for why this screen departs from the app's other forms in that
     * respect.
     *
     * The category pills are not here: they are rebuilt from state in [renderForm], because a chip
     * row's selection is drawn from the state rather than held by the view.
     */
    private fun setUpFields() {
        binding.nameInput.doOnTextChanged { text, _, _, _ ->
            viewModel.onNameChanged(text?.toString().orEmpty())
        }
        binding.noteInput.doOnTextChanged { text, _, _, _ ->
            viewModel.onNoteChanged(text?.toString().orEmpty())
        }
    }

    private fun setUpButtons() {
        binding.backButton.setOnClickListener { findNavController().navigateUp() }
        binding.refreshLocationButton.setOnClickListener { requestLocation() }
        binding.useDemoLocationButton.setOnClickListener { viewModel.useDemoLocation() }
        binding.savePlaceButton.setOnClickListener { save() }

        // A tap on a Google link inside the map (its logo, "Terms") leaves the app rather than
        // navigating this small card to a full Google page the user could not get back from.
        binding.gpsMap.onExternalLink = { url -> openLink(url) }
    }

    /**
     * Asks for a fix, going through the permission prompt when access is not yet held.
     *
     * The branch is here rather than in the ViewModel because only a fragment can show a system
     * permission dialog. Once access *is* held the request goes straight through, so "Refresh
     * location" is one tap rather than two.
     */
    private fun requestLocation() {
        if (locationPermission.hasPermission()) {
            viewModel.locate()
        } else {
            locationPermission.request()
        }
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
        renderMap(state.gps)
        renderGpsCard(state.gps)
        renderForm(state)
    }

    /**
     * The live map, or the placeholder that stands in for it, plus the status chip over both.
     *
     * The map is only shown once there is a position to centre it on: a map of nowhere in particular
     * would be worse than a line saying what is being waited for, because it would look like an
     * answer. Zoom 17 rather than the Places tab's 16 — a GPS fix is a point precise enough to show
     * at street level, and the user is checking "is this the right doorway?".
     */
    private fun renderMap(gps: GpsState) {
        binding.gpsMap.isVisible = gps.isFound
        binding.mapPlaceholder.isVisible = !gps.isFound

        gps.coordinates?.let { binding.gpsMap.showLocation(it, zoom = GPS_MAP_ZOOM) }

        binding.mapPlaceholderText.setText(
            if (gps.status == GpsStatus.LOCATING) {
                R.string.add_place_map_locating
            } else {
                R.string.add_place_map_unavailable
            }
        )

        // The chip's dot and label are one statement about the fix, so they are decided together.
        binding.gpsStatusChipLabel.setText(statusChipLabel(gps))
        tintDot(binding.gpsStatusDot, statusColour(gps))
    }

    /**
     * The dark current-location card: the dot, the three lines of text, and the buttons under them.
     *
     * "Unavailable" is drawn as a full answer rather than as an error — the card says what happened
     * and names the two ways on — because the user has done nothing wrong by being indoors or by
     * refusing a permission the first time it was asked for.
     */
    private fun renderGpsCard(gps: GpsState) {
        tintDot(binding.gpsCardDot, statusColour(gps))

        binding.hereTitleText.setText(
            when {
                gps.isDemo -> R.string.add_place_here_demo
                gps.isFound -> R.string.add_place_here_found
                gps.status == GpsStatus.LOCATING -> R.string.add_place_here_locating
                else -> R.string.add_place_here_unknown
            }
        )

        binding.hereMetaText.text = when {
            gps.isFound -> formatFixDetails(gps)
            gps.status == GpsStatus.LOCATING -> getString(R.string.add_place_here_locating_meta)
            // Falls back to the refusal wording when the user dismissed the rationale dialog without
            // answering it, which leaves no message of its own.
            else -> getString(gps.message ?: R.string.add_place_gps_denied)
        }

        // The same button throughout, its label saying what pressing it will do. Never disabled: a
        // second press while a request is in flight is harmless — AddPlaceViewModel.locate ignores
        // it — and graying the button out would say the screen had stopped responding.
        binding.refreshLocationButton.setText(
            when {
                gps.status == GpsStatus.LOCATING -> R.string.add_place_gps_locating_button
                gps.isFound -> R.string.add_place_gps_refresh
                else -> R.string.add_place_gps_retry
            }
        )

        binding.useDemoLocationButton.isVisible = gps.canUseDemo
    }

    /**
     * The name, category, note, summary and save button.
     *
     * The two text boxes are written back to only when they actually differ from the state. Setting
     * an EditText's text to what it already contains would move the cursor to the end mid-word, and
     * this state is re-emitted on every keystroke — so the guard is what makes the name field usable
     * at all.
     */
    private fun renderForm(state: AddPlaceUiState) {
        if (binding.nameInput.text?.toString() != state.name) {
            binding.nameInput.setText(state.name)
            binding.nameInput.setSelection(state.name.length)
        }
        if (binding.noteInput.text?.toString() != state.note) {
            binding.noteInput.setText(state.note)
        }

        // 🏥 Vet · ✂️ Grooming · 🌳 Park · 🏪 Shop, built from the enum so adding a category needs no
        // layout change. Each chip leads with the emoji that will end up on the saved place's row,
        // so the symbol is part of the choice rather than a surprise afterwards. The chips are only
        // rebuilt when the options differ, so calling this on every emission costs nothing — see
        // ui/common/FilterChips.kt.
        binding.categoryChipGroup.bindOptions(
            options = PlaceCategory.entries.map {
                ChipOption(it.name, it.emojiLabel(requireContext()))
            },
            selectedKey = state.category.name,
            chipLayout = R.layout.item_category_chip,
            onSelected = { key ->
                PlaceCategory.entries.firstOrNull { it.name == key }
                    ?.let { viewModel.onCategorySelected(it) }
            }
        )

        binding.summaryText.text = buildSummary(state)

        // The label names the missing condition rather than only greying out, so a user who cannot
        // press the button knows whether to keep typing or to wait for the fix.
        binding.savePlaceButton.setText(
            if (state.gps.isFound) R.string.add_place_save else R.string.add_place_save_waiting
        )
        binding.savePlaceButton.isEnabled = state.canSave
    }

    // ---------------------------------------------------------------------------------------
    // Text and colour for one GPS state
    // ---------------------------------------------------------------------------------------

    /** The status chip's label: what kind of position, in two words. */
    @StringRes
    private fun statusChipLabel(gps: GpsState): Int = when {
        gps.isDemo -> R.string.add_place_gps_chip_demo
        gps.isFound -> R.string.add_place_gps_chip_live
        gps.status == GpsStatus.LOCATING -> R.string.add_place_gps_chip_locating
        else -> R.string.add_place_gps_chip_none
    }

    /**
     * The colour that stands for a GPS state, used by both dots so the chip over the map and the card
     * below it can never disagree.
     *
     * Blue for a position — the universal "you are here" colour, and the same blue whether the fix is
     * live or the demo, because in both cases there *is* one and the chip's label carries the
     * difference. The accent while looking, since waiting is the app's "in progress" everywhere else.
     * Coral when there is none: a condition to resolve, not a failure, which is why it is not red.
     */
    @ColorRes
    private fun statusColour(gps: GpsState): Int = when {
        gps.isFound -> R.color.here_dot
        gps.status == GpsStatus.LOCATING -> R.color.accent_yellow
        else -> R.color.gps_status_unavailable
    }

    /**
     * Recolours one of the two status dots.
     *
     * `backgroundTintList` rather than a differently-coloured drawable per state: both dots are the
     * same shape and only the fill changes, so one drawable tinted three ways is one definition
     * instead of three that could drift apart. The tint replaces the drawable's own solid colour.
     */
    private fun tintDot(dot: View, @ColorRes colour: Int) {
        dot.backgroundTintList =
            ColorStateList.valueOf(ContextCompat.getColor(requireContext(), colour))
    }

    /**
     * "27.6743° N, 85.3094° E · ±8 m · 0.1 km from home" — the card's meta line for a fix.
     *
     * Three facts, in the order a person checks them: where, how sure the device is, and whether that
     * is anywhere near where they expect to be. The accuracy is shown rather than hidden because it is
     * what lets somebody decide to step outside before saving; it is rounded to whole metres, since a
     * tenth of a metre is a precision no phone has.
     *
     * **When the platform reported no accuracy at all** — possible for a fix derived from cell towers
     * alone — that fact is dropped rather than printed as "±0 m", which would claim perfect precision
     * for a fix that simply did not say.
     */
    private fun formatFixDetails(gps: GpsState): String {
        val coordinates = gps.coordinates ?: return ""
        val position = formatCoordinates(coordinates)
        val fromHome = formatDistanceKm(requireContext(), gps.distanceFromHomeKm ?: 0.0)
        val accuracy = gps.accuracyMetres
            ?: return getString(R.string.add_place_here_meta_no_accuracy, position, fromHome)

        return getString(
            R.string.add_place_here_meta,
            position,
            accuracy.roundToInt().toString(),
            fromHome
        )
    }

    /**
     * The two-line readback above the save button: what will be saved, then the position it will
     * carry.
     *
     * The second line is the screen being honest about itself. Until there is a fix it says so
     * plainly rather than showing a plausible-looking coordinate, which is the same reason the save
     * button stays disabled: an invented geotag is worse than an unsaved place, because it looks
     * exactly like a real one.
     */
    private fun buildSummary(state: AddPlaceUiState): String {
        val name = state.name.trim().ifEmpty { getString(R.string.add_place_summary_untitled) }

        val headline = getString(
            R.string.add_place_summary_line,
            name,
            getString(state.category.labelRes())
        )

        val position = state.gps.coordinates
            ?.let { getString(R.string.add_place_summary_geotagged, formatCoordinates(it)) }
            ?: getString(R.string.add_place_summary_waiting)

        return "$headline\n$position"
    }

    /**
     * "27.6743° N, 85.3094° E".
     *
     * Four decimal places — about ten metres, which is as fine as a phone's GPS honestly claims — and
     * [Locale.US] so the numbers always use a decimal point, the way coordinates are written in every
     * maps app the user might compare them with. A locale-aware format would print "27,6743", where
     * the comma is the character that separates latitude from longitude.
     */
    private fun formatCoordinates(coordinates: Coordinates): String = getString(
        R.string.add_place_coordinates,
        String.format(Locale.US, "%.4f", coordinates.latitude),
        String.format(Locale.US, "%.4f", coordinates.longitude)
    )

    // ---------------------------------------------------------------------------------------
    // Actions
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
     * nor a fragment result listener. The tab uses them to select the new place — which re-centres its
     * map and points Directions at it — and to offer the undo. See PlacesFragment.observeSavedPlaceResult.
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

    /** Opens a link from inside the map in the browser or the Maps app — see PlacesFragment.openLink. */
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
        // The WebView keeps a rendering thread and a native page alive until told otherwise, so it is
        // torn down with the view rather than left to the garbage collector — see MapEmbedView.destroy.
        binding.gpsMap.destroy()
        _binding = null
    }

    companion object {

        /**
         * The keys the saved place travels back to the Places tab under. Named here, beside the code
         * that writes them, and read by PlacesFragment — one definition, so the two cannot drift.
         */
        const val RESULT_SAVED_PLACE_ID = "savedPlaceId"
        const val RESULT_SAVED_PLACE_NAME = "savedPlaceName"

        /**
         * Street level. Finer than the Places tab's neighbourhood view, because this map answers "is
         * this the right doorway?" rather than "where is this place in the city?".
         */
        private const val GPS_MAP_ZOOM = 17
    }
}
