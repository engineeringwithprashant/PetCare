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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * One saved place as the Places tab draws it: the record plus the facts worked out about it.
 *
 * @property distanceKm Straight-line distance from the phone, or null when its position is unknown.
 * @property isFeatured True for the place shown in the large card, which the list and the distance
 *   axis also highlight.
 * @property isOpenNow True when the current time falls inside the place's opening hours.
 */
data class PlaceRow(
    val place: Place,
    val distanceKm: Double?,
    val isFeatured: Boolean,
    val isOpenNow: Boolean
)

/**
 * One chip on the distance axis.
 *
 * Carries the place's name and distance as well as its position, even though the chip only shows the
 * category: the axis sits above the filter chips and shows *every* place, so the fragment cannot look
 * these up in the (filtered) list when it writes the axis's spoken description.
 *
 * @property fraction How far along the axis the place sits, from 0 (home) to 1 (the axis end).
 *   Worked out here rather than in the view so the view only draws — it never needs to know what a
 *   kilometre is.
 */
data class AxisMarker(
    val placeId: Long,
    val name: String,
    val category: PlaceCategory,
    val distanceKm: Double,
    val fraction: Float,
    val isFeatured: Boolean
)

/**
 * Everything the distance strip needs.
 *
 * @property maxKm Where the axis ends — the "within 2 km" figure.
 * @property stepKm The spacing of its labelled ticks.
 * @property markers One per saved place, in distance order.
 */
data class DistanceAxis(
    val maxKm: Double,
    val stepKm: Double,
    val markers: List<AxisMarker>
)

/**
 * Everything the Places tab displays.
 *
 * @property isLoading True until the first results arrive from Room.
 * @property petCount For the header badge.
 * @property hasPlaces Whether the account has saved any place at all — separate from [rows] being
 *   empty, which also happens when a category filter matches nothing.
 * @property selectedCategory The chosen filter chip, or null for "All".
 * @property axis The distance strip's contents, or null when the phone's position is unknown (no
 *   permission, or no fix yet) — there is nothing to measure from.
 * @property featured The place in the large card, or null when the filter matches nothing.
 * @property rows The saved-places list, already filtered and sorted.
 * @property routineOptions The account's active care routines, for "Book a visit" — a care task
 *   always belongs to a routine, so booking has to put the visit into one.
 */
data class PlacesUiState(
    val isLoading: Boolean = true,
    val petCount: Int = 0,
    val hasPlaces: Boolean = false,
    val selectedCategory: PlaceCategory? = null,
    val axis: DistanceAxis? = null,
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
 * is featured depends on the filter and on distance; the axis highlight depends on which place is
 * featured; "open now" depends on the clock. Computing all of it in one pass from the same inputs is
 * what guarantees the card, the list and the axis can never disagree — a separate flow per section
 * could briefly show one place featured in the card and another highlighted on the axis.
 *
 * **What the distances are measured from.** The design labels the axis origin "home". The app has no
 * stored home address, so distances are measured from the phone's current position, which for the
 * place-planning this screen is for is almost always where the user is setting out from. When that
 * position is unknown — location access not granted, or no fix — the distances are simply left out
 * rather than guessed.
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

    /** The account's active routines, each labelled with its pet's name, for "Book a visit". */
    private val routineOptions: Flow<Pair<Int, List<RoutineOption>>> = combine(
        petRepository.observePetsForOwner(ownerId),
        careRoutineRepository.observeActiveRoutinesForOwner(ownerId)
    ) { pets, routines ->
        val petNames = pets.associate { it.id to it.name }
        val options = routines.mapNotNull { routine ->
            petNames[routine.petId]?.let { RoutineOption(routine, it) }
        }
        // The pet count rides along, because the header badge wants it and it comes from the same
        // query — observing pets twice would be two identical queries for one number.
        pets.size to options
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
    ) { placeState, (petCount, options) ->
        placeState.copy(petCount = petCount, routineOptions = options)
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
            axis = buildAxis(places, distances, featuredId),
            featured = rows.firstOrNull { it.isFeatured },
            rows = rows
        )
    }

    /**
     * Lays out the distance strip: every saved place, whatever the filter says.
     *
     * The strip sits *above* the category chips on screen, so it describes all saved places rather
     * than the filtered selection — the chips filter what comes after them.
     */
    private fun buildAxis(
        places: List<Place>,
        distances: Map<Long, Double?>,
        featuredId: Long?
    ): DistanceAxis? {
        val measured = places.mapNotNull { place -> distances[place.id]?.let { place to it } }
        if (measured.isEmpty()) return null

        val step = GeoDistance.tickStepKm(measured.maxOf { it.second })
        val max = GeoDistance.axisMaxKm(measured.maxOf { it.second }, step)

        return DistanceAxis(
            maxKm = max,
            stepKm = step,
            markers = measured
                .sortedBy { it.second }
                .map { (place, km) ->
                    AxisMarker(
                        placeId = place.id,
                        name = place.name,
                        category = place.category,
                        distanceKm = km,
                        fraction = (km / max).toFloat().coerceIn(0f, 1f),
                        isFeatured = place.id == featuredId
                    )
                }
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
