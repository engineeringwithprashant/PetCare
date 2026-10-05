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
import com.prash.petcare.ui.common.anchorAboveNavBar
import com.prash.petcare.ui.common.bindHeaderFromSession
import com.prash.petcare.ui.common.bindOptions
import com.prash.petcare.ui.common.emojiLabel
import com.prash.petcare.ui.common.formatDistanceKm
import com.prash.petcare.ui.common.formatTimeOfDay
import com.prash.petcare.ui.common.labelRes
import com.prash.petcare.ui.common.openExternalLink
import com.prash.petcare.ui.common.openProfile
import com.prash.petcare.ui.common.placeSubtitle
import com.prash.petcare.ui.common.setUp
import com.prash.petcare.ui.session.SessionViewModel
import com.prash.petcare.ui.task.AddEditTaskFragment
import com.prash.petcare.ui.task.CareTaskViewModel
import com.prash.petcare.util.Coordinates
import com.prash.petcare.util.MapLinks
import kotlinx.coroutines.launch

/** The Places tab: the user's saved vets, groomers, parks and shops, on a map. */
class PlacesFragment : Fragment() {

    /** Nulled in [onDestroyView] to avoid leaking the destroyed view hierarchy. */
    private var _binding: FragmentPlacesBinding? = null
    private val binding get() = _binding!!

    /** Which account is signed in — shared by every tab, see [SessionViewModel]. */
    private val sessionViewModel: SessionViewModel by activityViewModels { SessionViewModel.Factory }

    private val viewModel: PlacesViewModel by viewModels {
        PlacesViewModel.factory(ownerId = sessionViewModel.userId.value)
    }

    private val placeAdapter = SavedPlaceAdapter(
        onPlaceClick = { placeId -> onPlaceTapped(placeId) },
        onPlaceLongClick = { placeId -> openPlaceForm(placeId) }
    )

    /** Location access for measuring distances. Asked for only when the user taps the GPS chip on the place card. */
    private val locationPermission = LocationPermissionRequester(
        fragment = this,
        rationaleMessage = R.string.permission_location_distance_message,
        onGranted = {
            viewModel.refreshOrigin()
            // Redraw straight away: the chip's wording depends on whether permission is held.
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

        // The badge opens the notifications screen, as on every other screen that draws this header.
        binding.header.setUp(
            title = R.string.nav_places,
            onBadgeClick = { findNavController().navigate(R.id.notificationsFragment) },
            // The monogram opens the profile screen, as on every other screen that draws it.
            onMonogramClick = ::openProfile
        )

        // Keeps the header's mark showing the user's profile photo, falling back to the app's monogram.
        bindHeaderFromSession(binding.header, sessionViewModel)

        binding.placesRecyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = placeAdapter
        }

        setUpClickListeners()
        observeViewModel()
        observeSavedPlaceResult()
    }

    /** Watches for a place coming back from the "Add a place" screen, and confirms it. */
    private fun observeSavedPlaceResult() {
        val handle = findNavController().currentBackStackEntry?.savedStateHandle ?: return

        handle.getLiveData<Long>(AddPlaceFragment.RESULT_SAVED_PLACE_ID)
            .observe(viewLifecycleOwner) { placeId ->
                val name = handle.get<String>(AddPlaceFragment.RESULT_SAVED_PLACE_NAME).orEmpty()
                handle.remove<Long>(AddPlaceFragment.RESULT_SAVED_PLACE_ID)
                handle.remove<String>(AddPlaceFragment.RESULT_SAVED_PLACE_NAME)

                viewModel.selectPlace(placeId)
                showSavedConfirmation(placeId, name)
            }
    }

    /** "'Patan Animal Hospital' saved to places", with an undo. */
    private fun showSavedConfirmation(placeId: Long, name: String) {
        val root = _binding?.root ?: return

        Snackbar.make(root, getString(R.string.places_saved_snackbar, name), Snackbar.LENGTH_LONG)
            .setAction(R.string.places_saved_undo) {
                viewModel.undoSave(placeId)
                showMessage(getString(R.string.places_saved_undone, name))
            }
            // Without this the message would appear behind the floating navigation bar.
            .anchorAboveNavBar(this)
            .show()
    }

    /** Re-measures from wherever the phone is now, each time the tab comes into view. */
    override fun onStart() {
        super.onStart()
        if (locationPermission.hasPermission()) viewModel.refreshOrigin()
    }

    private fun setUpClickListeners() {
        // Both entry points lead to the same screen: the pill in the saved-places heading.
        binding.addPlacePill.setOnClickListener { openAddPlace() }
        binding.addPlaceButton.setOnClickListener { openAddPlace() }
        binding.pinButton.setOnClickListener { viewModel.togglePinned() }

        // These act on whichever place is selected at the moment of the tap, read from state then.
        binding.directionsButton.setOnClickListener {
            withSelectedCoordinates { openLink(MapLinks.directionsUrl(it)) }
        }
        binding.googleMapsButton.setOnClickListener {
            withSelectedCoordinates { openLink(MapLinks.searchUrl(it)) }
        }
        // The same destination as "Google maps" above.
        binding.openInMapsButton.setOnClickListener {
            withSelectedCoordinates { openLink(MapLinks.searchUrl(it)) }
        }
        binding.phoneBlock.setOnClickListener {
            viewModel.uiState.value.featured?.place?.phone?.let { dial(it) }
        }
        binding.bookVisitButton.setOnClickListener {
            viewModel.uiState.value.featured?.place?.let { bookVisit(it) }
        }

        // A tap on a Google link inside the map (its logo, "Terms") leaves the app.
        binding.placesMap.onExternalLink = { url -> openLink(url) }

        binding.mapLayerButton.setOnClickListener { viewModel.toggleMapLayer() }
    }

    /** Applies the chosen map layer to the card and to the button that chooses it. */
    private fun renderMapLayer(isSatellite: Boolean) {
        binding.placesMap.isSatellite = isSatellite

        binding.mapLayerButton.setText(
            if (isSatellite) {
                R.string.map_layer_switch_to_map
            } else {
                R.string.map_layer_switch_to_satellite
            }
        )
        binding.mapLayerButton.contentDescription = getString(
            if (isSatellite) {
                R.string.cd_map_layer_showing_satellite
            } else {
                R.string.cd_map_layer_showing_map
            }
        )
    }

    /** Runs [action] with the selected place's coordinates, or does nothing when no place is selected. */
    private inline fun withSelectedCoordinates(action: (Coordinates) -> Unit) {
        val place = viewModel.uiState.value.featured?.place ?: return
        action(Coordinates(place.latitude, place.longitude))
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Two collectors rather than one combined flow.
                launch { viewModel.uiState.collect { state -> render(state) } }
                launch { viewModel.isSatellite.collect { renderMapLayer(it) } }
            }
        }
    }

    // Rendering.

    private fun render(state: PlacesUiState) {
        if (state.isLoading) return

        // With nothing saved, a map, filters and a card for no places would be empty furniture.
        val hasPlaces = state.hasPlaces
        // The map goes with the place it shows. It is hidden when a category filter matches nothing as well.
        binding.mapCard.isVisible = hasPlaces && state.featured != null
        binding.categoryChipScroll.isVisible = hasPlaces
        // The heading row goes with the list it heads. Its "+ ADD PLACE" pill goes.
        binding.savedPlacesHeader.isVisible = hasPlaces
        binding.emptyPlacesContainer.isVisible = !hasPlaces

        renderMap(state.featured)
        renderCategoryChips(state)
        renderFeatured(state.featured)

        placeAdapter.submitList(state.rows)
        binding.placesRecyclerView.isVisible = state.rows.isNotEmpty()

        // Places exist but the chosen chip matches none of them — say.
        binding.noMatchesText.isVisible = hasPlaces && state.rows.isEmpty()
        state.selectedCategory?.let {
            binding.noMatchesText.text =
                getString(R.string.places_no_matches, getString(it.labelRes()).lowercase())
        }
    }

    /** Centres the map on the selected place and writes the pill over it. */
    private fun renderMap(featured: PlaceRow?) {
        val place = featured?.place ?: return

        val at = Coordinates(place.latitude, place.longitude)
        binding.placesMap.showLocation(at, zoom = PLACES_MAP_ZOOM)
        // The dot marking the place itself. It stays on the place when the map is panned.
        binding.placesMap.markerAt = at

        binding.mapPlacePill.text = featured.distanceKm
            ?.let { getString(R.string.places_map_pill, place.name, formatDistanceKm(requireContext(), it)) }
            ?: place.name

        // The map surface is opaque to TalkBack, so the card names what it is showing.
        binding.placesMap.contentDescription = getString(R.string.cd_places_map_at, place.name)
    }

    /** Builds the chip row — "All" then each category — and marks the chosen one. */
    private fun renderCategoryChips(state: PlacesUiState) {
        // Each category chip leads with its emoji — the same symbol the rows below carry in their badges.
        val options = listOf(ChipOption(KEY_ALL, getString(R.string.places_filter_all))) +
            PlaceCategory.entries.map { ChipOption(it.name, it.emojiLabel(requireContext())) }

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

        // The GPS chip, and the one place on this tab that can still ask for location access.
        val hasLocation = locationPermission.hasPermission()
        binding.gpsTagChip.text = when {
            featured.distanceKm != null ->
                getString(R.string.place_gps_tag_with_distance, formatDistanceKm(context, featured.distanceKm))

            hasLocation -> getString(R.string.place_gps_tag)
            else -> getString(R.string.place_gps_tag_allow_location)
        }
        binding.gpsTagChip.isClickable = !hasLocation
        binding.gpsTagChip.isFocusable = !hasLocation
        binding.gpsTagChip.setOnClickListener(
            if (hasLocation) null else View.OnClickListener { locationPermission.request() }
        )

        // The toggle's label says what is, like the theme toggle in the header.
        binding.pinButton.isChecked = place.isPinned
        binding.pinButton.setText(if (place.isPinned) R.string.place_pinned else R.string.place_pin)
        binding.pinButton.contentDescription = getString(
            if (place.isPinned) R.string.cd_place_unpin else R.string.cd_place_pin,
            place.name
        )
    }

    // Actions.

    /** A saved-place row was tapped: select it, and bring the map into view. */
    private fun onPlaceTapped(placeId: Long) {
        viewModel.selectPlace(placeId)
        binding.placesScroll.smoothScrollTo(0, 0)
    }

    /** "+ ADD PLACE": opens the screen where the place you are standing in is saved. */
    private fun openAddPlace() {
        findNavController().navigate(R.id.action_places_to_add_place)
    }

    /** Opens the place form to change or delete a saved place, reached by long-pressing its row. */
    private fun openPlaceForm(placeId: Long) {
        findNavController().navigate(
            R.id.action_places_to_add_edit_place,
            bundleOf(AddEditPlaceFragment.ARG_PLACE_ID to placeId)
        )
    }

    /** Hands a Google Maps link to the Maps app, or to the browser when it is not installed. */
    private fun openLink(url: String) {
        if (!openExternalLink(url)) showMessage(getString(R.string.places_no_maps_app))
    }

    /** Opens the dialler with the place's number filled in. */
    private fun dial(phone: String) {
        try {
            startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", phone, null)))
        } catch (e: ActivityNotFoundException) {
            showMessage(getString(R.string.places_no_dialer))
        }
    }

    /** "BOOK A VISIT": opens the care task form as a vet-visit task, prefilled with this place. */
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

    /** Navigates to the task form with the visit's wording already in it. */
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

    /** Location access was refused. Distances simply stay hidden — the map, the links. */
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
        Snackbar.make(root, text, Snackbar.LENGTH_LONG).anchorAboveNavBar(this).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        routineDialog?.dismiss()
        routineDialog = null
        // The adapter outlives the view as a fragment property.
        binding.placesRecyclerView.adapter = null
        // The map holds a GPU surface and a native renderer until told otherwise, so it is torn down with the view.
        binding.placesMap.destroy()
        _binding = null
    }

    private companion object {
        /** The chip key for "All" — never a [PlaceCategory] name. */
        const val KEY_ALL = "all"

        /** Neighbourhood level: close enough to see which street a clinic. */
        const val PLACES_MAP_ZOOM = 16
    }
}
