package com.prash.petcare.ui.place

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.prash.petcare.PetCareApplication
import com.prash.petcare.data.local.entity.Place
import com.prash.petcare.data.local.entity.PlaceCategory
import com.prash.petcare.data.location.LocationRepository
import com.prash.petcare.data.repository.CareRoutineRepository
import com.prash.petcare.data.repository.PetRepository
import com.prash.petcare.data.repository.PlaceRepository
import com.prash.petcare.ui.checklist.RoutineOption
import com.prash.petcare.util.Coordinates
import com.prash.petcare.util.GeoDistance
import com.prash.petcare.util.OpeningHours
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * One saved place as the Places tab draws it: the record plus the facts worked out about it.
 *
 * @property distanceKm Straight-line distance from the phone, or null when its position is unknown.
 * @property isFeatured True for the **selected** place — the one the map is centred on and the large
 *   card describes, which the list also highlights.
 * @property isOpenNow True when the current time falls inside the place's opening hours.
 */
data class PlaceRow(
    val place: Place,
    val distanceKm: Double?,
    val isFeatured: Boolean,
    val isOpenNow: Boolean
)

/**
 * Everything the Places tab displays.
 *
 * @property isLoading True until the first results arrive from Room.
 * @property hasPlaces Whether the account has saved any place at all — separate from [rows] being
 *   empty, which also happens when a category filter matches nothing.
 * @property selectedCategory The chosen filter chip, or null for "All".
 * @property featured The **selected place**: the one the map is centred on, the one the overlay pill
 *   names, the one Directions navigates to, and the one the large card describes. Null only when the
 *   filter matches nothing. One value for all four, because a map showing one place while the card
 *   below described another would be worse than either alone.
 * @property rows The saved-places list, already filtered and sorted.
 * @property routineOptions The account's active care routines, for "Book a visit" — a care task
 *   always belongs to a routine, so booking has to put the visit into one.
 */
data class PlacesUiState(
    val isLoading: Boolean = true,
    val hasPlaces: Boolean = false,
    val selectedCategory: PlaceCategory? = null,
    val featured: PlaceRow? = null,
    val rows: List<PlaceRow> = emptyList(),
    val routineOptions: List<RoutineOption> = emptyList()
)

/**
 * Backs the Places tab.
 *
 * In MVVM this is the **ViewModel**: it combines the account's saved places (from
 * [PlaceRepository]), where the phone is (from [LocationRepository]), the chosen filter and the time
 * of day into one [PlacesUiState], and the fragment draws that state without deciding anything.
 *
 * **Everything is derived from one `combine`.** Distances depend on the phone's position; which place
 * is selected depends on the filter and on distance; the map, the overlay pill and both map links
 * depend on which place is selected; "open now" depends on the clock. Computing all of it in one pass
 * from the same inputs is what guarantees the map and the card below it can never disagree — a
 * separate flow per section could briefly centre the map on one place while the card described
 * another, and the Directions button would then navigate somewhere the user never chose.
 *
 * **What the distances are measured from.** The phone's current position, which for the place-planning
 * this tab is for is almost always where the user is setting out from. The app has no stored home
 * address to use instead. When that position is unknown — location access not granted, or no fix —
 * the distances are simply left out rather than guessed, and the overlay pill shows the place's name
 * alone.
 *
 * No Android types appear here: coordinates are the app's own [Coordinates], and the location client
 * lives behind [LocationRepository].
 *
 * @param ownerId The signed-in account, from the session.
 */
class PlacesViewModel(
    private val placeRepository: PlaceRepository,
    petRepository: PetRepository,
    careRoutineRepository: CareRoutineRepository,
    private val locationRepository: LocationRepository,
    ownerId: Long
) : ViewModel() {

    /** Where the phone was at the last successful fix, or null before one. */
    private val origin = MutableStateFlow<Coordinates?>(null)

    /** The chosen category chip; null is "All". */
    private val selectedCategory = MutableStateFlow<PlaceCategory?>(null)

    /**
     * The place the user tapped to feature, or null to let the screen choose.
     *
     * Only a *preference*: if the filter hides the tapped place, the card falls back to the best
     * visible one rather than showing something the chips claim is filtered out.
     */
    private val selectedPlaceId = MutableStateFlow<Long?>(null)

    /**
     * Whether the map card shows Google's aerial imagery rather than its drawn map.
     *
     * Exposed as its own flow rather than folded into [PlacesUiState], and deliberately *not* added to
     * the `combine` below. That combine exists to keep the map, the featured card and both Maps links
     * derived from one pass over the same inputs — which is what guarantees they can never describe
     * different places. The layer is not one of those inputs: it changes how the tiles are painted and
     * nothing else, so feeding it in would re-derive every distance, every "open now" pill and the whole
     * row list each time the user flipped it, to redraw one card.
     *
     * In the ViewModel rather than the fragment so the choice survives a rotation.
     */
    private val _isSatellite = MutableStateFlow(false)
    val isSatellite: StateFlow<Boolean> = _isSatellite.asStateFlow()

    /** Flips between the drawn map and the imagery. */
    fun toggleMapLayer() {
        _isSatellite.value = !_isSatellite.value
    }

    /**
     * The current minute of the day, re-emitted every minute.
     *
     * "OPEN NOW" is a statement about the present, so it has to change while the screen is open — a
     * clinic that closes at 20:00 must lose its pill at 20:00, not the next time the user switches
     * tabs. The flow only ticks while the fragment is collecting (see `WhileSubscribed` below), so an
     * app in the background does not wake up once a minute for nothing.
     */
    private val minuteOfDay: Flow<Int> = flow {
        while (true) {
            val now = Calendar.getInstance()
            emit(now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE))
            // Sleep until the start of the next minute rather than a flat 60 s, so the pill flips on
            // the minute the place actually opens or closes.
            delay((60 - now.get(Calendar.SECOND)) * 1_000L)
        }
    }

    /**
     * The account's active routines, each labelled with its pet's name, for "Book a visit".
     *
     * The pets are queried for their *names*. The count used to ride along with them for the header's
     * badge; the badge is a notifications bell now and takes its number from the session, so the
     * second half of that pair has gone — see TabHeader.
     */
    private val routineOptions: Flow<List<RoutineOption>> = combine(
        petRepository.observePetsForOwner(ownerId),
        careRoutineRepository.observeActiveRoutinesForOwner(ownerId)
    ) { pets, routines ->
        val petNames = pets.associate { it.id to it.name }
        routines.mapNotNull { routine ->
            petNames[routine.petId]?.let { RoutineOption(routine, it) }
        }
    }

    val uiState: StateFlow<PlacesUiState> = combine(
        combine(
            placeRepository.observePlacesForOwner(ownerId),
            origin,
            selectedCategory,
            selectedPlaceId,
            minuteOfDay
        ) { places, from, category, preferredId, minute ->
            buildPlaceState(places, from, category, preferredId, minute)
        },
        routineOptions
    ) { placeState, options ->
        placeState.copy(routineOptions = options)
    }.stateIn(
        scope = viewModelScope,
        // Five seconds of grace keeps the Room queries alive across a rotation, when the old view
        // stops collecting a moment before the new one starts.
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = PlacesUiState()
    )

    /**
     * Works out the whole screen from its inputs. A pure function of its arguments — nothing is read
     * from fields — which keeps the ordering rules in one place and easy to follow.
     */
    private fun buildPlaceState(
        places: List<Place>,
        from: Coordinates?,
        category: PlaceCategory?,
        preferredId: Long?,
        minute: Int
    ): PlacesUiState {
        val distances: Map<Long, Double?> = places.associate { place ->
            place.id to from?.let {
                GeoDistance.distanceKm(it, Coordinates(place.latitude, place.longitude))
            }
        }

        // Pinned places first, since pinning is the user saying "this one matters"; then nearest
        // first, which is what a list of places to go to is usually scanned for; then by name, so
        // places with no distance still come out in a stable, predictable order.
        val sorted = places.sortedWith(
            compareByDescending<Place> { it.isPinned }
                .thenBy(nullsLast()) { distances[it.id] }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
        )

        val visible = sorted.filter { category == null || it.category == category }
        val featuredId = (visible.firstOrNull { it.id == preferredId } ?: visible.firstOrNull())?.id

        val rows = visible.map { place ->
            PlaceRow(
                place = place,
                distanceKm = distances[place.id],
                isFeatured = place.id == featuredId,
                isOpenNow = OpeningHours.isOpen(place.openingMinutes, place.closingMinutes, minute)
            )
        }

        return PlacesUiState(
            isLoading = false,
            hasPlaces = places.isNotEmpty(),
            selectedCategory = category,
            featured = rows.firstOrNull { it.isFeatured },
            rows = rows
        )
    }

    // ---------------------------------------------------------------------------------------
    // User actions, forwarded by the fragment
    // ---------------------------------------------------------------------------------------

    /** A category chip was chosen; null means "All". */
    fun selectCategory(category: PlaceCategory?) {
        selectedCategory.value = category
    }

    /** A saved-place row was tapped: feature it. */
    fun selectPlace(placeId: Long) {
        selectedPlaceId.value = placeId
    }

    /**
     * "Undo" was pressed on the confirmation after a place was saved: remove it again.
     *
     * The place is loaded by id rather than taken from the current state, because the undo may be
     * pressed after a category filter has hidden the row it refers to — and a Snackbar that does
     * nothing because the list has moved on would be worse than no undo at all.
     *
     * An ordinary delete, through the same repository call the edit form uses, so the deletion leaves
     * its sync tombstone and never reappears from another device. A place that is already gone — the
     * user deleted it from the form in the meantime — simply does nothing.
     */
    fun undoSave(placeId: Long) {
        viewModelScope.launch {
            placeRepository.getPlace(placeId)?.let { placeRepository.deletePlace(it) }
        }
    }

    /** The PINNED button was pressed on the featured place. */
    fun togglePinned() {
        val featured = uiState.value.featured?.place ?: return
        viewModelScope.launch { placeRepository.setPinned(featured, !featured.isPinned) }
    }

    /**
     * Refreshes the position distances are measured from.
     *
     * Called by the fragment each time the tab comes into view, because the phone moves: distances
     * worked out at breakfast are wrong by lunchtime across town. A balanced-accuracy fix is plenty
     * for "1.2 km" and arrives faster than GPS; a cached fix is accepted as a fallback, since a
     * slightly stale distance is more useful than none. A failed fix keeps the previous origin rather
     * than blanking every distance on screen.
     */
    fun refreshOrigin() {
        viewModelScope.launch {
            locationRepository.getCurrentLocation(highAccuracy = false, allowCached = true)
                ?.let { origin.value = it }
        }
    }

    companion object {

        /**
         * Builds the ViewModel. A function because the account id is only known at runtime, from the
         * session — see [com.prash.petcare.ui.session.SessionViewModel].
         */
        fun factory(ownerId: Long): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application =
                    this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as PetCareApplication
                PlacesViewModel(
                    placeRepository = application.placeRepository,
                    petRepository = application.petRepository,
                    careRoutineRepository = application.careRoutineRepository,
                    locationRepository = application.locationRepository,
                    ownerId = ownerId
                )
            }
        }
    }
}
