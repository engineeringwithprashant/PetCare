package com.prash.petcare.ui.place

import androidx.annotation.StringRes
import com.prash.petcare.R
import com.prash.petcare.data.location.GeocodingRepository
import com.prash.petcare.data.location.LocationRepository
import com.prash.petcare.data.location.PlaceSuggestion
import com.prash.petcare.util.Coordinates
import com.prash.petcare.util.GeoDistance
import com.prash.petcare.util.HomeReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** Where a selected location originally came from. */
enum class LocationSource {

    /** The device reported its own position — "I'm here", or the recentre button. */
    CURRENT_LOCATION,

    /** The user moved the map until the place sat under the centre pin. */
    MAP_SELECTION,

    /** The user searched for the place by name and picked it out of the results. */
    SEARCH_RESULT
}

/** The search field above the map, and whatever it is currently offering. */
data class PlaceSearchState(
    val query: String = "",
    val results: List<PlaceSuggestion> = emptyList(),
    val isSearching: Boolean = false,
    val isExpanded: Boolean = false
) {

    /** True when a search has finished and found nothing. */
    val hasNoResults: Boolean
        get() = isExpanded &&
            !isSearching &&
            results.isEmpty() &&
            query.trim().length >= GeocodingRepository.MIN_QUERY_LENGTH
}

/** The one place a Places form keeps "where is this?". */
data class SelectedLocation(
    val coordinates: Coordinates,
    val placeName: String? = null,
    val addressLine: String? = null,
    val isPoiName: Boolean = false,
    val source: LocationSource
) {

    /** The name to offer the user as the place's own, or null when there is nothing worth offering. */
    val suggestedName: String? get() = placeName?.takeIf { isPoiName && it.isNotBlank() }

    /** What this point's "area or address" box should say. */
    val areaText: String? get() = if (isPoiName) addressLine else (placeName ?: addressLine)
}

/** Everything the shared location picker shows. */
data class LocationPickerState(
    val selected: SelectedLocation? = null,
    val search: PlaceSearchState = PlaceSearchState(),
    val mapType: MapType = MapType.NORMAL,
    val isLocating: Boolean = false,
    val isResolvingAddress: Boolean = false,
    val isApproximateFix: Boolean = false,
    val isMapAvailable: Boolean = true
)

/** Something the picker needs a **View** to do, because a ViewModel cannot. */
sealed interface LocationPickerEvent {

    /** Take the map to this point. Sent rather than kept in the state. */
    data class MoveCamera(val at: Coordinates, val zoom: Int, val animate: Boolean) :
        LocationPickerEvent

    /** Ask for location access. Only a fragment can show the system prompt. */
    data object RequestPermission : LocationPickerEvent

    /** Something worth a Snackbar — why the current position could not be used. */
    data class Message(@param:StringRes val messageRes: Int) : LocationPickerEvent
}

/** The location half of both Places forms: the map's camera, the chosen point and its name. */
class PlaceLocationPicker(
    private val locationRepository: LocationRepository,
    private val geocodingRepository: GeocodingRepository,
    private val scope: CoroutineScope
) {

    private val _state = MutableStateFlow(LocationPickerState())
    val state: StateFlow<LocationPickerState> = _state.asStateFlow()

    private val _events = Channel<LocationPickerEvent>(Channel.BUFFERED)
    val events: Flow<LocationPickerEvent> = _events.receiveAsFlow()

    /** The address lookup in flight, so the next camera idle can cancel it. */
    private var addressJob: Job? = null

    /** The point the current [addressJob] was started for, so a tiny move can skip the lookup. */
    private var lastGeocodedAt: Coordinates? = null

    /** The debounced search in flight, so the next keystroke can cancel it. */
    private var searchJob: Job? = null

    // Opening the screen.

    /** Puts the map somewhere sensible when the screen opens. */
    fun start(savedAt: Coordinates?) {
        if (_state.value.selected != null) return

        if (savedAt != null) {
            select(savedAt, LocationSource.MAP_SELECTION)
            sendEvent(LocationPickerEvent.MoveCamera(savedAt, SELECTED_ZOOM, animate = false))
            resolveAddress(savedAt, immediately = true)
            return
        }

        if (locationRepository.hasLocationPermission() && locationRepository.isLocationEnabled()) {
            useCurrentLocation(animate = false)
            return
        }

        sendEvent(
            LocationPickerEvent.MoveCamera(HomeReference.COORDINATES, STARTING_ZOOM, animate = false)
        )
    }

    // Choosing by searching for it.

    /** The user typed in the search field. */
    fun onSearchQueryChanged(query: String) {
        val search = _state.value.search
        if (search.query == query) return

        searchJob?.cancel()

        val trimmed = query.trim()
        if (trimmed.length < GeocodingRepository.MIN_QUERY_LENGTH) {
            _state.value = _state.value.copy(
                search = PlaceSearchState(query = query)
            )
            return
        }

        _state.value = _state.value.copy(
            search = search.copy(query = query, isExpanded = true, isSearching = true)
        )

        searchJob = scope.launch {
            delay(SEARCH_DEBOUNCE_MILLIS)

            val results = geocodingRepository.searchPlaces(trimmed, near = searchProximity())

            // The query is checked again on the way back for the same reason the address lookup checks its coordinate.
            val current = _state.value.search
            if (current.query.trim() != trimmed) return@launch

            _state.value = _state.value.copy(
                search = current.copy(results = results, isSearching = false, isExpanded = true)
            )
        }
    }

    /** A result was tapped: this is now the chosen location. */
    fun onSearchResultSelected(suggestion: PlaceSuggestion) {
        searchJob?.cancel()
        addressJob?.cancel()
        lastGeocodedAt = suggestion.coordinates

        _state.value = _state.value.copy(
            selected = SelectedLocation(
                coordinates = suggestion.coordinates,
                placeName = suggestion.name,
                addressLine = suggestion.addressLine,
                isPoiName = suggestion.isPointOfInterest,
                source = LocationSource.SEARCH_RESULT
            ),
            // A searched place is a point on a map, not a reading off this device.
            isApproximateFix = false,
            search = _state.value.search.copy(
                query = suggestion.name,
                isExpanded = false,
                isSearching = false
            )
        )

        // Animated, unlike the opening position: the user is being taken somewhere.
        sendEvent(
            LocationPickerEvent.MoveCamera(suggestion.coordinates, SELECTED_ZOOM, animate = true)
        )
    }

    /** The results list should close without anything being chosen — the field lost focus. */
    fun onSearchDismissed() {
        if (!_state.value.search.isExpanded) return
        _state.value = _state.value.copy(search = _state.value.search.copy(isExpanded = false))
    }

    /** The field was focused again: show what it already found, if anything. */
    fun onSearchFocused() {
        val search = _state.value.search
        if (search.isExpanded || search.results.isEmpty()) return
        _state.value = _state.value.copy(search = search.copy(isExpanded = true))
    }

    /** Where to bias search results towards. */
    private fun searchProximity(): Coordinates =
        _state.value.selected?.coordinates ?: HomeReference.COORDINATES

    // Choosing by moving the map.

    /** The map has come to rest under the user's finger, and this is the point beneath the centre pin. */
    fun onMapCentreChanged(at: Coordinates) {
        val current = _state.value.selected
        if (current?.coordinates == at) return

        if (current != null && isEffectivelySamePoint(current.coordinates, at)) {
            _state.value = _state.value.copy(selected = current.copy(coordinates = at))
            return
        }

        select(at, LocationSource.MAP_SELECTION)
        resolveAddress(at, immediately = false)
    }

    // Choosing by standing there.

    /** "I'm here", and the small recentre control in the map's corner — both, deliberately. */
    fun useCurrentLocation(animate: Boolean = true) {
        if (_state.value.isLocating) return

        if (!locationRepository.hasLocationCapability()) {
            sendEvent(LocationPickerEvent.Message(R.string.location_error_unsupported))
            return
        }

        if (!locationRepository.hasLocationPermission()) {
            sendEvent(LocationPickerEvent.RequestPermission)
            return
        }

        if (!locationRepository.isLocationEnabled()) {
            sendEvent(LocationPickerEvent.Message(R.string.location_error_services_off))
            return
        }

        _state.value = _state.value.copy(isLocating = true)
        val isPrecise = locationRepository.hasPreciseLocationPermission()

        scope.launch {
            // High accuracy because this coordinate is about to become a place's permanent geotag.
            val fix = locationRepository.getCurrentFix(
                highAccuracy = isPrecise,
                allowCached = true,
                timeoutMillis = FIX_TIMEOUT_MILLIS,
                maxCacheAgeMillis = MAX_CACHE_AGE_MILLIS
            )

            _state.value = _state.value.copy(isLocating = false)

            if (fix == null) {
                sendEvent(LocationPickerEvent.Message(R.string.location_error_no_fix))
                return@launch
            }

            // The selection is made here rather than waiting for the camera to arrive.
            select(fix.coordinates, LocationSource.CURRENT_LOCATION, isApproximate = !isPrecise)
            sendEvent(LocationPickerEvent.MoveCamera(fix.coordinates, SELECTED_ZOOM, animate))
            resolveAddress(fix.coordinates, immediately = true)
        }
    }

    /** The user refused the system prompt. */
    fun onPermissionDenied() {
        sendEvent(LocationPickerEvent.Message(R.string.location_error_permission))
    }

    // The map itself.

    /** Switches the tile layer. */
    fun onMapTypeSelected(type: MapType) {
        if (_state.value.mapType == type) return
        _state.value = _state.value.copy(mapType = type)
    }

    /** The map reported whether it managed to load — see [LocationPickerState.isMapAvailable]. */
    fun onMapAvailabilityChanged(isAvailable: Boolean) {
        if (_state.value.isMapAvailable == isAvailable) return
        _state.value = _state.value.copy(isMapAvailable = isAvailable)
    }

    // Internals.

    /** Replaces the selection whole — see [SelectedLocation] for why it is never edited in pieces. */
    private fun select(at: Coordinates, source: LocationSource, isApproximate: Boolean = false) {
        _state.value = _state.value.copy(
            selected = SelectedLocation(coordinates = at, source = source),
            isApproximateFix = isApproximate
        )
    }

    /** Looks up the name of a point and writes it onto the selection, if that is still the selection. */
    private fun resolveAddress(at: Coordinates, immediately: Boolean) {
        addressJob?.cancel()

        val previous = lastGeocodedAt
        if (!immediately && previous != null && isEffectivelySamePoint(previous, at)) return

        if (!geocodingRepository.isAvailable()) return

        lastGeocodedAt = at
        _state.value = _state.value.copy(isResolvingAddress = true)

        addressJob = scope.launch {
            if (!immediately) delay(SETTLE_DELAY_MILLIS)

            val resolved = geocodingRepository.reverseGeocode(at)
            val current = _state.value.selected

            // Compared with a tolerance rather than exactly, for the reason given on [onMapCentreChanged].
            _state.value = if (current != null && isEffectivelySamePoint(current.coordinates, at)) {
                _state.value.copy(
                    isResolvingAddress = false,
                    selected = current.copy(
                        placeName = resolved?.placeName,
                        addressLine = resolved?.addressLine,
                        // Carried across rather than recomputed, because only the lookup can know.
                        isPoiName = resolved?.isPointOfInterest == true
                    )
                )
            } else {
                _state.value.copy(isResolvingAddress = false)
            }
        }
    }

    /** Whether two coordinates are close enough to be the same place for this screen's purposes. */
    private fun isEffectivelySamePoint(a: Coordinates, b: Coordinates): Boolean =
        GeoDistance.distanceKm(a, b) * METRES_PER_KM < MIN_MOVE_METRES

    /** Queues an event. */
    private fun sendEvent(event: LocationPickerEvent) {
        _events.trySend(event)
    }

    private companion object {

        /** Street level. This map answers "is this the right doorway?" rather than "where is this in the city?". */
        const val SELECTED_ZOOM = 17

        /** City level, used only for the opening view when there is no position at all. */
        const val STARTING_ZOOM = 13

        /** How long to wait for a fix, and how old a cached one may be to stand in for it. */
        const val FIX_TIMEOUT_MILLIS = 10_000L
        const val MAX_CACHE_AGE_MILLIS = 30_000L

        /** How long the map must stay still before its new centre is worth a network lookup. */
        const val SETTLE_DELAY_MILLIS = 450L

        /** How long the typing must pause before the search field asks the service anything: 350ms. */
        const val SEARCH_DEBOUNCE_MILLIS = 350L

        /** Below this, the pin has not moved far enough to have changed its answer. */
        const val MIN_MOVE_METRES = 25.0
        const val METRES_PER_KM = 1_000.0
    }
}
