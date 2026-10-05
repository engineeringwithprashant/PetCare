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

/** One saved place as the Places tab draws it: the record plus the facts worked out about it. */
data class PlaceRow(
    val place: Place,
    val distanceKm: Double?,
    val isFeatured: Boolean,
    val isOpenNow: Boolean
)

/** Everything the Places tab displays. */
data class PlacesUiState(
    val isLoading: Boolean = true,
    val hasPlaces: Boolean = false,
    val selectedCategory: PlaceCategory? = null,
    val featured: PlaceRow? = null,
    val rows: List<PlaceRow> = emptyList(),
    val routineOptions: List<RoutineOption> = emptyList()
)

/** Backs the Places tab. */
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

    /** The place the user tapped to feature, or null to let the screen choose. */
    private val selectedPlaceId = MutableStateFlow<Long?>(null)

    /** Whether the map card shows Google's aerial imagery rather than its drawn map. */
    private val _isSatellite = MutableStateFlow(false)
    val isSatellite: StateFlow<Boolean> = _isSatellite.asStateFlow()

    /** Flips between the drawn map and the imagery. */
    fun toggleMapLayer() {
        _isSatellite.value = !_isSatellite.value
    }

    /** The current minute of the day, re-emitted every minute. */
    private val minuteOfDay: Flow<Int> = flow {
        while (true) {
            val now = Calendar.getInstance()
            emit(now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE))
            // Sleep until the start of the next minute rather than a flat 60 s.
            delay((60 - now.get(Calendar.SECOND)) * 1_000L)
        }
    }

    /** The account's active routines, each labelled with its pet's name, for "Book a visit". */
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
        // Five seconds of grace keeps the Room queries alive across a rotation.
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = PlacesUiState()
    )

    /** Works out the whole screen from its inputs. A pure function of its arguments — nothing is read from fields. */
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

        // Pinned places first, since pinning is the user saying "this one matters"; then nearest first.
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

    // User actions, forwarded by the fragment.

    /** A category chip was chosen; null means "All". */
    fun selectCategory(category: PlaceCategory?) {
        selectedCategory.value = category
    }

    /** A saved-place row was tapped: feature it. */
    fun selectPlace(placeId: Long) {
        selectedPlaceId.value = placeId
    }

    /** "Undo" was pressed on the confirmation after a place was saved: remove it again. */
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

    /** Refreshes the position distances are measured from. */
    fun refreshOrigin() {
        viewModelScope.launch {
            locationRepository.getCurrentLocation(highAccuracy = false, allowCached = true)
                ?.let { origin.value = it }
        }
    }

    companion object {

        /** Builds the ViewModel. A function because the account id is only known at runtime, from the session. */
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
