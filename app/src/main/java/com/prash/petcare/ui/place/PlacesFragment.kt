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

/**
 * The Places tab: the user's saved vets, groomers, parks and shops, on a map.
 *
 * Top to bottom: a **map card** centred on the selected place, with its name and distance on a pill
 * over the top and "Directions" / "Google maps" over the foot; a row of **category chips**; a large
 * **place card** (hours, phone, notes, booking and pinning); and the **saved places** list, where
 * tapping a row selects it — which re-centres the map and re-points both links.
 *
 * In MVVM this is the **View**. [PlacesViewModel] measures, filters, sorts and decides which place is
 * selected; this class draws that state and forwards taps. It formats text (which needs a Context)
 * and it owns the things that genuinely need an Activity: the location permission prompt, the
 * hand-off to Google Maps, the phone dialler, and navigation.
 *
 * **A real map without an API key.** The card is a `WebView` over Google's keyless embed URL, and
 * the two links below it are the documented Maps URL scheme, which Android delivers to the Maps app
 * when it is installed and to the browser when it is not. So the tab shows real tiles and offers real
 * turn-by-turn directions with no Maps SDK, no key and no billing account — see MapEmbedView and
 * [com.prash.petcare.util.MapLinks], which also carry the note on what to change for production.
 */
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

    /**
     * Location access for measuring distances. Asked for only when the user taps the GPS chip on the
     * place card — never on arrival, since a permission prompt the moment a tab opens, before the
     * user has seen what it is for, is the prompt most likely to be refused.
     */
    private val locationPermission = LocationPermissionRequester(
        fragment = this,
        rationaleMessage = R.string.permission_location_distance_message,
        onGranted = {
            viewModel.refreshOrigin()
            // Redraw straight away: the chip's wording depends on whether permission is held, and
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

        // The badge opens the notifications screen, as on every other screen that draws this header.
        binding.header.setUp(
            title = R.string.nav_places,
            onBadgeClick = { findNavController().navigate(R.id.notificationsFragment) },
            // The monogram opens the profile screen, as on every other screen that draws it.
            onMonogramClick = ::openProfile
        )

        // Keeps the header's mark showing the user's profile photo, falling back to the app's
        // monogram when there is none. See TabHeader.bindHeaderFromSession.
        bindHeaderFromSession(binding.header, sessionViewModel)

        binding.placesRecyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = placeAdapter
        }

        setUpClickListeners()
        observeViewModel()
        observeSavedPlaceResult()
    }

    /**
     * Watches for a place coming back from the "Add a place" screen, and confirms it.
     *
     * **How the result arrives.** That screen writes the new place's id and name into *this*
     * destination's [androidx.lifecycle.SavedStateHandle] before closing — the Navigation component's
     * way of returning a value. The handle belongs to this back stack entry, so it survives this
     * screen being recreated while the other one was open, and it needs neither a shared ViewModel
     * nor a fragment result listener.
     *
     * **Why the id is cleared as it is read.** A [androidx.lifecycle.LiveData] replays its last value
     * to a new observer, so without removing it the confirmation would appear again every time the
     * user came back to this tab — announcing a save that happened an hour ago.
     *
     * The row itself needs no help to appear: the list observes Room, so it, the distance ruler and
     * the category filters have already redrawn by the time this runs. All this does is *feature* the
     * new place and offer the way back out of the decision.
     */
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

    /**
     * "'Patan Animal Hospital' saved to places", with an undo.
     *
     * Undo rather than a confirmation dialog before saving: the save is one tap and almost always
     * right, so asking first would tax every correct save to catch the rare wrong one. Taking it back
     * afterwards costs nothing and only appears when it is wanted. A second, briefer Snackbar
     * confirms the undo itself, so pressing it is not silence.
     */
    private fun showSavedConfirmation(placeId: Long, name: String) {
        val root = _binding?.root ?: return

        Snackbar.make(root, getString(R.string.places_saved_snackbar, name), Snackbar.LENGTH_LONG)
            .setAction(R.string.places_saved_undo) {
                viewModel.undoSave(placeId)
                showMessage(getString(R.string.places_saved_undone, name))
            }
            // Without this the message would appear *behind* the floating navigation bar, which
            // would leave "Undo" unreadable and untappable — see anchorAboveNavBar.
            .anchorAboveNavBar(this)
            .show()
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
        // Both entry points lead to the same screen: the pill in the saved-places heading, and the
        // full-width row at the foot of the page, which is the only one showing in the empty state.
        binding.addPlacePill.setOnClickListener { openAddPlace() }
        binding.addPlaceButton.setOnClickListener { openAddPlace() }
        binding.pinButton.setOnClickListener { viewModel.togglePinned() }

        // These act on whichever place is selected *at the moment of the tap*, read from state then,
        // rather than capturing a place when the listener is attached — the selected place changes
        // without the listeners being set again.
        binding.directionsButton.setOnClickListener {
            withSelectedCoordinates { openLink(MapLinks.directionsUrl(it)) }
        }
        binding.googleMapsButton.setOnClickListener {
            withSelectedCoordinates { openLink(MapLinks.searchUrl(it)) }
        }
        // The same destination as "Google maps" above, because they are the same request made from
        // two places on the page: show me this place in Google Maps.
        binding.openInMapsButton.setOnClickListener {
            withSelectedCoordinates { openLink(MapLinks.searchUrl(it)) }
        }
        binding.phoneBlock.setOnClickListener {
            viewModel.uiState.value.featured?.place?.phone?.let { dial(it) }
        }
        binding.bookVisitButton.setOnClickListener {
            viewModel.uiState.value.featured?.place?.let { bookVisit(it) }
        }

        // A tap on a Google link inside the map (its logo, "Terms") leaves the app rather than
        // navigating the small card to a full Google page the user could not get back from.
        binding.placesMap.onExternalLink = { url -> openLink(url) }

        binding.mapLayerButton.setOnClickListener { viewModel.toggleMapLayer() }
    }

    /**
     * Applies the chosen map layer to the card and to the button that chooses it.
     *
     * The button's label names where a press *leads* rather than where the map already is, so the two
     * are deliberately crossed: imagery showing means the button offers the map. The content
     * description states both halves, because a screen reader user cannot see the tiles under the
     * button to infer the other half from. The same rendering as AddPlaceFragment's, because it is the
     * same control.
     */
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

    /**
     * Runs [action] with the selected place's coordinates, or does nothing when no place is selected.
     *
     * The three map links all need exactly this, and all three are attached to buttons that are only
     * on screen when there *is* a selected place — so the null case is a guard against a race (the
     * filter changing under a tap) rather than something the screen expects.
     */
    private inline fun withSelectedCoordinates(action: (Coordinates) -> Unit) {
        val place = viewModel.uiState.value.featured?.place ?: return
        action(Coordinates(place.latitude, place.longitude))
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Two collectors rather than one combined flow: the map layer changes for reasons the
                // screen state knows nothing about, and folding it in would redraw the whole tab — see
                // PlacesViewModel.isSatellite.
                launch { viewModel.uiState.collect { state -> render(state) } }
                launch { viewModel.isSatellite.collect { renderMapLayer(it) } }
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Rendering
    // ---------------------------------------------------------------------------------------

    private fun render(state: PlacesUiState) {
        if (state.isLoading) return

        // With nothing saved, a map, filters and a card for no places would be empty furniture — and
        // a map has nowhere to centre itself; the empty state and the add button say what to do
        // instead.
        val hasPlaces = state.hasPlaces
        // The map goes with the place it shows. It is hidden when a category filter matches nothing
        // as well as when nothing is saved: with no selected place there is nothing to centre on,
        // and leaving the last one up would name a place the chips have just filtered away — with
        // "Directions" still pointing at it.
        binding.mapCard.isVisible = hasPlaces && state.featured != null
        binding.categoryChipScroll.isVisible = hasPlaces
        // The heading row goes with the list it heads. Its "+ ADD PLACE" pill goes with it, which is
        // why the empty state keeps the full-width add button below — see the layout.
        binding.savedPlacesHeader.isVisible = hasPlaces
        binding.emptyPlacesContainer.isVisible = !hasPlaces

        renderMap(state.featured)
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
     * Centres the map on the selected place and writes the pill over it.
     *
     * The map is only reloaded when the coordinates actually change — [MapEmbedView.showLocation]
     * guards that — which matters because this runs on every state emission, including each tick of
     * the "open now" clock. Without the guard the map would reload once a minute and never finish.
     *
     * **The pill shows a distance only when one is known.** It is measured from the phone, so it is
     * missing until location access is granted and a fix has arrived; the name alone is shown in the
     * meantime rather than a figure measured from somewhere the user did not choose. The GPS chip on
     * the card below is where that access can be granted — see [renderFeatured].
     */
    private fun renderMap(featured: PlaceRow?) {
        val place = featured?.place ?: return

        binding.placesMap.showLocation(
            Coordinates(place.latitude, place.longitude),
            zoom = PLACES_MAP_ZOOM
        )

        binding.mapPlacePill.text = featured.distanceKm
            ?.let { getString(R.string.places_map_pill, place.name, formatDistanceKm(requireContext(), it)) }
            ?: place.name

        // The WebView is opaque to TalkBack, so the card names what it is showing.
        binding.placesMap.contentDescription = getString(R.string.cd_places_map_at, place.name)
    }

    /** Builds the chip row — "All" then each category — and marks the chosen one. */
    private fun renderCategoryChips(state: PlacesUiState) {
        // Each category chip leads with its emoji — the same symbol the rows below carry in their
        // badges — so the filter and the list it filters are read with the same eye. "All" has no
        // emoji because it is not a category: it is the absence of one.
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
        //
        // Distances used to be introduced by the distance ruler, which carried its own "Allow
        // location" button; the map card that replaced it has no room for one. Without this, a user
        // who never opens "Add a place" — the screen that does ask — would have no way to turn
        // distances on at all. The chip already says what is missing, so it is the natural thing to
        // make tappable: "GPS tag saved · tap for distance".
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
     * A saved-place row was tapped: select it, and bring the map into view.
     *
     * Scrolling to the *map* rather than to the card below it, because selecting a place now moves
     * the map — and a change the user cannot see reads as the tap having failed. On a long list both
     * are off the top of the screen, so without this the tap would appear to do nothing.
     */
    private fun onPlaceTapped(placeId: Long) {
        viewModel.selectPlace(placeId)
        binding.placesScroll.smoothScrollTo(0, 0)
    }

    /**
     * "+ ADD PLACE": opens the screen where the place you are standing in is saved.
     *
     * A separate destination from the edit form below, because the two answer different questions.
     * Adding is "save where I am, under this name", which is a GPS fix and two fields. Editing is
     * "what has changed about this one?", which is a full form over a place that already exists.
     */
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

    /**
     * Hands a Google Maps link to the Maps app, or to the browser when it is not installed.
     *
     * Used by all three ways out of this screen — "DIRECTIONS ↗" and "GOOGLE MAPS" over the map,
     * "Open in maps ↗" on the card — and by any Google link the user taps *inside* the embedded map,
     * which the WebView refuses to navigate to itself. See [openExternalLink] for why an `https` URL
     * reaches the Maps app, and why the launch is tried rather than checked first.
     */
    private fun openLink(url: String) {
        if (!openExternalLink(url)) showMessage(getString(R.string.places_no_maps_app))
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
     * Location access was refused. Distances simply stay hidden — the map, the links, the card and
     * the list all work without them — and a permanent refusal gets a route to the settings screen.
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
        Snackbar.make(root, text, Snackbar.LENGTH_LONG).anchorAboveNavBar(this).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        routineDialog?.dismiss()
        routineDialog = null
        // The adapter outlives the view as a fragment property; detaching it stops the destroyed
        // RecyclerView being kept alive through it.
        binding.placesRecyclerView.adapter = null
        // The WebView keeps a rendering thread and a native page alive until told otherwise, so it is
        // torn down with the view rather than left to the garbage collector — see MapEmbedView.destroy.
        binding.placesMap.destroy()
        _binding = null
    }

    private companion object {
        /** The chip key for "All" — never a [PlaceCategory] name. */
        const val KEY_ALL = "all"

        /**
         * Neighbourhood level: close enough to see which street a clinic is on, wide enough to show
         * what is around it. The Add a place screen uses one step closer, because there the question
         * is "is this the right doorway?" rather than "where in the city is this?".
         */
        const val PLACES_MAP_ZOOM = 16
    }
}
