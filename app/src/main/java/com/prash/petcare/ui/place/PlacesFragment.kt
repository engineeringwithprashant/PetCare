package com.prash.petcare.ui.place

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.core.net.toUri
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
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
import com.prash.petcare.data.local.entity.Place
import com.prash.petcare.data.local.entity.PlaceCategory
import com.prash.petcare.data.local.entity.TaskCategory
import com.prash.petcare.databinding.FragmentPlacesBinding
import com.prash.petcare.ui.checklist.RoutineOption
import com.prash.petcare.ui.common.ChipOption
import com.prash.petcare.ui.common.badgeRes
import com.prash.petcare.ui.common.bindOptions
import com.prash.petcare.ui.common.formatAxisKm
import com.prash.petcare.ui.common.formatDistanceKm
import com.prash.petcare.ui.common.formatTimeOfDay
import com.prash.petcare.ui.common.labelRes
import com.prash.petcare.ui.common.placeSubtitle
import com.prash.petcare.ui.common.setUp
import com.prash.petcare.ui.common.showPetCount
import com.prash.petcare.ui.session.SessionViewModel
import com.prash.petcare.ui.task.AddEditTaskFragment
import com.prash.petcare.ui.task.CareTaskViewModel
import kotlinx.coroutines.launch

/**
 * The Places tab: the user's saved vets, groomers, parks and shops, laid out by distance.
 *
 * Top to bottom: a dark **distance strip** plotting every saved place along an axis from home, a row
 * of **category chips**, a large **featured place** card (hours, phone, notes, directions, booking
 * and pinning), and the **saved places** list, where tapping a row features it.
 *
 * In MVVM this is the **View**. [PlacesViewModel] measures, filters, sorts and decides which place is
 * featured; this class draws that state and forwards taps. It formats text (which needs a Context)
 * and it owns the things that genuinely need an Activity: the location permission prompt, the
 * `geo:` hand-off to a maps app, the phone dialler, and navigation.
 *
 * **Directions without the Maps SDK.** "Open in maps" builds a `geo:` URI from the saved coordinates
 * and asks Android for any app that can show it — Google Maps, OsmAnd, Organic Maps, whatever the
 * user has. PetCare draws no map itself, so it needs no API key and no map library; the user gets
 * turn-by-turn directions from an app built for exactly that.
 */
class PlacesFragment : Fragment() {

    /** Nulled in [onDestroyView] to avoid leaking the destroyed view hierarchy. */
    private var _binding: FragmentPlacesBinding? = null
    private val binding get() = _binding!!

    /** Which account is signed in — shared by every tab, see [SessionViewModel]. */
    private val sessionViewModel: SessionViewModel by activityViewModels()

    private val viewModel: PlacesViewModel by viewModels {
        PlacesViewModel.factory(ownerId = sessionViewModel.userId.value)
    }

    private val placeAdapter = SavedPlaceAdapter(
        onPlaceClick = { placeId -> onPlaceTapped(placeId) },
        onPlaceLongClick = { placeId -> openPlaceForm(placeId) }
    )

    /**
     * Location access for measuring distances. Asked for only when the user presses "Allow location"
     * in the strip — never on arrival, since a permission prompt the moment a tab opens, before the
     * user has seen what it is for, is the prompt most likely to be refused.
     */
    private val locationPermission = LocationPermissionRequester(
        fragment = this,
        rationaleMessage = R.string.permission_location_distance_message,
        onGranted = {
            viewModel.refreshOrigin()
            // Redraw straight away: the strip's wording depends on whether permission is held, and
            // the state itself will not change until a fix arrives, which may take several seconds.
            if (_binding != null) render(viewModel.uiState.value)
        },
        onDenied = { permanently -> onLocationDenied(permanently) }
    )

    /** The routine chooser for "Book a visit", held so it can be dismissed with the view. */
    private var routineDialog: AlertDialog? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPlacesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // The badge opens the profile screen, as on the other tabs: this one hides the shared
        // toolbar whose overflow menu held the account actions.
        binding.header.setUp(title = R.string.nav_places) {
            findNavController().navigate(R.id.profileFragment)
        }

        binding.placesRecyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = placeAdapter
        }

        setUpClickListeners()
        observeViewModel()
    }

    /**
     * Re-measures from wherever the phone is now, each time the tab comes into view.
     *
     * onStart rather than onViewCreated: coming back to the tab from another app — say, the maps app
     * this screen handed directions to — does not recreate the view, but the user has very likely
     * moved. Only when permission is already held: this is not the moment to ask.
     */
    override fun onStart() {
        super.onStart()
        if (locationPermission.hasPermission()) viewModel.refreshOrigin()
    }

    private fun setUpClickListeners() {
        binding.addPlaceButton.setOnClickListener { openPlaceForm(AddEditPlaceViewModel.NO_PLACE_ID) }
        binding.allowLocationButton.setOnClickListener { locationPermission.request() }
        binding.pinButton.setOnClickListener { viewModel.togglePinned() }

        // These act on whichever place is featured *at the moment of the tap*, read from state then,
        // rather than capturing a place when the listener is attached — the featured place changes
        // without the listeners being set again.
        binding.openInMapsButton.setOnClickListener {
            viewModel.uiState.value.featured?.place?.let { openInMaps(it) }
        }
        binding.phoneBlock.setOnClickListener {
            viewModel.uiState.value.featured?.place?.phone?.let { dial(it) }
        }
        binding.bookVisitButton.setOnClickListener {
            viewModel.uiState.value.featured?.place?.let { bookVisit(it) }
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state -> render(state) }
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Rendering
    // ---------------------------------------------------------------------------------------

    private fun render(state: PlacesUiState) {
        binding.header.showPetCount(state.petCount)
        if (state.isLoading) return

        // With nothing saved, a strip, filters and a featured card for no places would be empty
        // furniture; the empty state and the add button say what to do instead.
        val hasPlaces = state.hasPlaces
        binding.distanceStrip.isVisible = hasPlaces
        binding.categoryChipScroll.isVisible = hasPlaces
        binding.savedPlacesLabel.isVisible = hasPlaces
        binding.emptyPlacesContainer.isVisible = !hasPlaces

        renderDistanceStrip(state)
        renderCategoryChips(state)
        renderFeatured(state.featured)

        placeAdapter.submitList(state.rows)
        binding.placesRecyclerView.isVisible = state.rows.isNotEmpty()

        // Places exist but the chosen chip matches none of them — say so, rather than showing a
        // blank gap under the heading that looks like a loading failure.
        binding.noMatchesText.isVisible = hasPlaces && state.rows.isEmpty()
        state.selectedCategory?.let {
            binding.noMatchesText.text =
                getString(R.string.places_no_matches, getString(it.labelRes()).lowercase())
        }
    }

    /**
     * Fills the dark strip: the "within n km" range, the axis, and the helper text beneath.
     *
     * Without a position to measure from, the axis cannot be drawn, so it is hidden and the helper
     * text explains why — offering the permission when that is what is missing, and pointing at the
     * phone's location setting when permission is held but no fix has arrived.
     */
    private fun renderDistanceStrip(state: PlacesUiState) {
        val axis = state.axis
        val hasPermission = locationPermission.hasPermission()

        binding.distanceAxis.isVisible = axis != null
        binding.stripRangeText.isVisible = axis != null
        binding.allowLocationButton.isVisible = axis == null && !hasPermission

        binding.stripHelpText.setText(
            when {
                axis != null -> R.string.places_strip_help
                hasPermission -> R.string.places_strip_waiting_for_fix
                else -> R.string.places_strip_no_permission
            }
        )

        if (axis == null) return

        binding.stripRangeText.text = getString(R.string.places_strip_within, formatAxisKm(requireContext(), axis.maxKm))

        // One tick per step, starting at the first step rather than at zero — zero is the home dot,
        // which carries its own label.
        val tickCount = Math.round(axis.maxKm / axis.stepKm).toInt()
        val ticks = (1..tickCount).map { index ->
            val km = axis.stepKm * index
            DistanceAxisView.Tick(formatAxisKm(requireContext(), km), (km / axis.maxKm).toFloat())
        }

        val chips = axis.markers.map { marker ->
            DistanceAxisView.Chip(
                label = axisChipLabel(marker.category),
                fraction = marker.fraction,
                isHighlighted = marker.isFeatured
            )
        }

        binding.distanceAxis.setContent(getString(R.string.places_axis_home), ticks, chips)

        // The drawing is invisible to TalkBack, so its content is spelled out: "Distances from home:
        // Jawalakhel Dog Park, 0.5 km, Patan Animal Hospital, 1.2 km".
        binding.distanceAxis.contentDescription = getString(
            R.string.cd_places_axis,
            axis.markers.joinToString(getString(R.string.list_separator)) { marker ->
                getString(R.string.cd_places_axis_entry, marker.name, formatDistanceKm(requireContext(), marker.distanceKm))
            }
        )
    }

    /**
     * The chip text on the axis: "PARK", "GRM", "VET", "SHOP". Upper-cased here with the device
     * locale rather than stored in capitals, so the same strings serve the sentence-case list badges.
     */
    private fun axisChipLabel(category: PlaceCategory): String =
        getString(category.badgeRes()).uppercase(resources.configuration.locales[0])

    /** Builds the chip row — "All" then each category — and marks the chosen one. */
    private fun renderCategoryChips(state: PlacesUiState) {
        val options = listOf(ChipOption(KEY_ALL, getString(R.string.places_filter_all))) +
            PlaceCategory.entries.map { ChipOption(it.name, getString(it.labelRes())) }

        binding.categoryChipGroup.bindOptions(
            options = options,
            selectedKey = state.selectedCategory?.name ?: KEY_ALL,
            chipLayout = R.layout.item_category_chip,
            onSelected = { key ->
                viewModel.selectCategory(PlaceCategory.entries.firstOrNull { it.name == key })
            }
        )
    }

    /** Fills the featured card, or hides it when the filter leaves nothing to feature. */
    private fun renderFeatured(featured: PlaceRow?) {
        binding.featuredCard.isVisible = featured != null
        if (featured == null) return

        val place = featured.place
        val context = requireContext()

        binding.featuredNameText.text = place.name
        binding.openNowPill.isVisible = featured.isOpenNow
        binding.featuredSubtitleText.text = placeSubtitle(context, place)

        val opening = place.openingMinutes
        val closing = place.closingMinutes
        binding.hoursValueText.text = if (opening != null && closing != null) {
            getString(R.string.place_hours_format, formatTimeOfDay(context, opening), formatTimeOfDay(context, closing))
        } else {
            getString(R.string.place_hours_unknown)
        }

        val phone = place.phone?.trim().orEmpty()
        binding.phoneValueText.text = phone.ifEmpty { getString(R.string.place_phone_unknown) }
        // Only a real number can be dialled; without one the block is plain text, not a dead button.
        binding.phoneBlock.isClickable = phone.isNotEmpty()
        binding.phoneBlock.isFocusable = phone.isNotEmpty()

        val notes = place.notes?.trim().orEmpty()
        binding.featuredNotesText.isVisible = notes.isNotEmpty()
        binding.featuredNotesText.text = notes

        binding.gpsTagChip.text = featured.distanceKm?.let {
            getString(R.string.place_gps_tag_with_distance, formatDistanceKm(context, it))
        } ?: getString(R.string.place_gps_tag)

        // The toggle's label says what *is*, like the theme toggle in the header; the checked state
        // also darkens its outline (see place_pin_stroke.xml) so the difference is not in words alone.
        binding.pinButton.isChecked = place.isPinned
        binding.pinButton.setText(if (place.isPinned) R.string.place_pinned else R.string.place_pin)
        binding.pinButton.contentDescription = getString(
            if (place.isPinned) R.string.cd_place_unpin else R.string.cd_place_pin,
            place.name
        )
    }

    // ---------------------------------------------------------------------------------------
    // Actions
    // ---------------------------------------------------------------------------------------

    /**
     * A saved-place row was tapped: feature it, and bring the card into view — on a long list the
     * card is off the top of the screen, and a change the user cannot see reads as the tap failing.
     */
    private fun onPlaceTapped(placeId: Long) {
        viewModel.selectPlace(placeId)
        binding.placesScroll.smoothScrollTo(0, binding.featuredCard.top - binding.categoryChipScroll.height)
    }

    /** Opens the place form: [AddEditPlaceViewModel.NO_PLACE_ID] to add, a real id to edit. */
    private fun openPlaceForm(placeId: Long) {
        findNavController().navigate(
            R.id.action_places_to_add_edit_place,
            bundleOf(AddEditPlaceFragment.ARG_PLACE_ID to placeId)
        )
    }

    /**
     * "OPEN IN MAPS ↗": hands the saved coordinates to whichever maps app the user has.
     *
     * The URI is `geo:lat,lng?q=lat,lng(Name)`. The first pair centres the map; the `q` query drops a
     * labelled pin there, which is what lets the maps app offer "Directions" to it. The name is
     * URI-encoded, since a clinic called "Paws & Claws" would otherwise break the query at the `&`.
     *
     * The coordinates are written with [Double.toString], never a locale-aware format: in much of
     * Europe that would write "27,67", and a comma is the separator *between* latitude and longitude.
     *
     * Started directly and the failure caught, rather than checked first with `resolveActivity`:
     * from Android 11 that check only sees apps declared in a `<queries>` block, and would report "no
     * maps app" on phones that have one.
     */
    private fun openInMaps(place: Place) {
        val lat = place.latitude.toString()
        val lng = place.longitude.toString()
        val uri = "geo:$lat,$lng?q=$lat,$lng(${Uri.encode(place.name)})".toUri()

        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: ActivityNotFoundException) {
            showMessage(getString(R.string.places_no_maps_app))
        }
    }

    /**
     * Opens the dialler with the place's number filled in. `ACTION_DIAL`, not `ACTION_CALL`: the user
     * presses call themselves, so the app needs no phone permission and never places a call unasked.
     */
    private fun dial(phone: String) {
        try {
            startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", phone, null)))
        } catch (e: ActivityNotFoundException) {
            showMessage(getString(R.string.places_no_dialer))
        }
    }

    /**
     * "BOOK A VISIT": opens the care task form as a vet-visit task, prefilled with this place.
     *
     * A care task always belongs to a routine — the routine is what says which days it falls on, see
     * CareTask — so the visit has to be put into one. The same three cases as "+ Add task" on the Tasks
     * tab: none (explain where routines are made), one (go straight there), several (ask which,
     * labelled with the pet's name so "Health checks · Bella" and "Health checks · Max" differ).
     *
     * Nothing is saved here. The form opens filled in, and the user chooses the time and presses
     * save — booking is a plan they confirm, not something a single tap should write behind their back.
     */
    private fun bookVisit(place: Place) {
        val options = viewModel.uiState.value.routineOptions

        when (options.size) {
            0 -> showMessage(getString(R.string.tasks_add_task_no_routine))
            1 -> openVisitTaskForm(options.single(), place)
            else -> {
                val labels = options.map {
                    getString(R.string.tasks_add_task_routine_option, it.routine.title, it.petName)
                }.toTypedArray()

                routineDialog = MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.places_book_pick_routine)
                    .setItems(labels) { _, index -> openVisitTaskForm(options[index], place) }
                    .setNegativeButton(R.string.delegate_cancel, null)
                    .setOnDismissListener { routineDialog = null }
                    .show()
            }
        }
    }

    /**
     * Navigates to the task form with the visit's wording already in it.
     *
     * The title and notes are written here, where strings can be read, and passed as plain text
     * arguments; the category is passed by enum name, the same way the database stores it.
     */
    private fun openVisitTaskForm(option: RoutineOption, place: Place) {
        val context = requireContext()

        val details = buildList {
            add(placeSubtitle(context, place))
            place.phone?.takeIf { it.isNotBlank() }?.let { add(getString(R.string.place_book_notes_phone, it)) }
            val opening = place.openingMinutes
            val closing = place.closingMinutes
            if (opening != null && closing != null) {
                add(
                    getString(
                        R.string.place_book_notes_hours,
                        getString(R.string.place_hours_format, formatTimeOfDay(context, opening), formatTimeOfDay(context, closing))
                    )
                )
            }
        }

        findNavController().navigate(
            R.id.action_places_to_add_edit_task,
            bundleOf(
                AddEditTaskFragment.ARG_ROUTINE_ID to option.routine.id,
                AddEditTaskFragment.ARG_TASK_ID to CareTaskViewModel.NO_TASK_ID,
                AddEditTaskFragment.ARG_PREFILL_TITLE to getString(R.string.place_book_task_title, place.name),
                AddEditTaskFragment.ARG_PREFILL_NOTES to details.joinToString("\n"),
                AddEditTaskFragment.ARG_PREFILL_CATEGORY to TaskCategory.VET_VISIT.name
            )
        )
    }

    /**
     * Location access was refused. Distances simply stay hidden — every other part of the tab works
     * without them — and a permanent refusal gets a route to the settings screen.
     */
    private fun onLocationDenied(permanently: Boolean) {
        val root = _binding?.root ?: return
        val snackbar = Snackbar.make(root, R.string.permission_location_distance_denied, Snackbar.LENGTH_LONG)
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

    /** Shows a brief message. Safe after the view is gone, when it does nothing. */
    private fun showMessage(text: String) {
        val root = _binding?.root ?: return
        Snackbar.make(root, text, Snackbar.LENGTH_LONG).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        routineDialog?.dismiss()
        routineDialog = null
        // The adapter outlives the view as a fragment property; detaching it stops the destroyed
        // RecyclerView being kept alive through it.
        binding.placesRecyclerView.adapter = null
        _binding = null
    }

    private companion object {
        /** The chip key for "All" — never a [PlaceCategory] name. */
        const val KEY_ALL = "all"
    }
}
