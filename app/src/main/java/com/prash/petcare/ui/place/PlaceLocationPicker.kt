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

/**
 * Where a selected location originally came from.
 *
 * Kept as state rather than inferred, because the two are indistinguishable once they are a pair of
 * numbers and the screens genuinely need to tell them apart — see [PlaceLocationPicker.useCurrentLocation].
 * It is an internal fact rather than something the user is shown: what matters to them is the address
 * above the coordinates, not which of two routes produced it.
 */
enum class LocationSource {

    /** The device reported its own position — "I'm here", or the recentre button. */
    CURRENT_LOCATION,

    /** The user moved the map until the place sat under the centre pin. */
    MAP_SELECTION,

    /** The user searched for the place by name and picked it out of the results. */
    SEARCH_RESULT
}

/**
 * The search field above the map, and whatever it is currently offering.
 *
 * Held in the picker's state rather than in the field itself for the usual reason — the ViewModel owns
 * what is true and the view draws it — and for one specific to a search box: the debounce timer, the
 * request in flight and the list of results are three things that have to agree with each other, and
 * a view that kept any of them would be a second place for them to disagree.
 *
 * @property query Exactly what is in the field. Written back to the box when the two differ, which is
 *   what lets choosing a result put that result's name in the field.
 * @property results What to offer, best match first. Empty before anything has been typed, and empty
 *   again when nothing matched — which [hasNoResults] is what tells apart.
 * @property isSearching True while a request is in flight, so the field can show that something is
 *   happening rather than looking inert for the length of a round trip.
 * @property isExpanded Whether the results list is open. Its own flag rather than "are there results?",
 *   because the list has to close on a selection while the results it was showing are still perfectly
 *   good — and reopen on the next keystroke without them having to be fetched again.
 */
data class PlaceSearchState(
    val query: String = "",
    val results: List<PlaceSuggestion> = emptyList(),
    val isSearching: Boolean = false,
    val isExpanded: Boolean = false
) {

    /**
     * True when a search has finished and found nothing.
     *
     * The three conditions are all needed, and each rules out a state that would otherwise show "no
     * places found" wrongly: before the user has typed enough to search at all, while the request is
     * still running, and after a selection has closed the list. Derived here rather than stored so it
     * cannot fall out of step with the three things it is derived from.
     */
    val hasNoResults: Boolean
        get() = isExpanded &&
            !isSearching &&
            results.isEmpty() &&
            query.trim().length >= GeocodingRepository.MIN_QUERY_LENGTH
}

/**
 * The one place a Places form keeps "where is this?".
 *
 * **Why the coordinates and their name live in one value.** A latitude with somebody else's address
 * above it is the worst bug this feature can have, because nothing about it looks wrong — the card
 * reads perfectly and the place is saved two streets away. Replacing the whole object on every change
 * makes that unrepresentable: a new coordinate arrives with its name cleared, and the name is only
 * ever written back onto the coordinate it was looked up for.
 *
 * @property coordinates The point that will be saved. Always the latest one chosen, by either route.
 * @property placeName The headline name for it: the business or POI the pin is standing on when there
 *   is one — "Patan Hospital" — and otherwise the narrowest address-like name available. Null before
 *   the lookup answers, or when it found nothing; a place with no name is still perfectly saveable.
 * @property addressLine The fuller context under the name, or null when there is none.
 * @property isPoiName True when [placeName] is a real POI or business name rather than an area — see
 *   [suggestedName], which is the only thing that reads it.
 * @property source Which route put the pin here — see [LocationSource].
 */
data class SelectedLocation(
    val coordinates: Coordinates,
    val placeName: String? = null,
    val addressLine: String? = null,
    val isPoiName: Boolean = false,
    val source: LocationSource
) {

    /**
     * The name to offer the user as the place's own, or null when there is nothing worth offering.
     *
     * **Only ever a POI name.** "Patan Hospital" is almost certainly what the user was about to type,
     * so filling it in saves them the typing; "Lalitpur" is a city of half a million people and would
     * be a nonsense name for one saved vet. The distinction is made in the data layer, where the
     * lookup knows which kind of answer it found — see
     * [com.prash.petcare.data.location.ResolvedAddress.isPointOfInterest] — rather than guessed at
     * here from the shape of the string.
     *
     * It is a *suggestion* and never more than that: both forms write it into an ordinary, editable
     * name field and stop touching that field the moment the user types in it themselves.
     */
    val suggestedName: String? get() = placeName?.takeIf { isPoiName && it.isNotBlank() }

    /**
     * What this point's "area or address" box should say.
     *
     * The address rather than the headline, which are the same thing only when no POI was matched:
     * with the pin on a hospital the headline is the hospital and the area is "Lagankhel, Lalitpur",
     * and putting the hospital's name in the area box as well would store the same string twice and
     * leave the saved place with no address at all.
     */
    val areaText: String? get() = if (isPoiName) addressLine else (placeName ?: addressLine)
}

/**
 * Everything the shared location picker shows.
 *
 * @property selected The chosen point, or null before anything has been chosen — which is an ordinary
 *   state, not a failure: the map is up, and the user has yet to move it or press "I'm here".
 * @property mapType Which layer the map draws with.
 * @property isLocating True while the device is being asked where it is, so both current-location
 *   controls can show that something is happening and neither can be pressed twice.
 * @property isResolvingAddress True while the name above the coordinates is being looked up.
 * @property isApproximateFix True when the selection came from the device but only *approximate*
 *   location was granted, so the card can warn that the point may be a few hundred metres out. False
 *   for a point placed on the map, which has no accuracy to doubt: it is exactly where it was put.
 * @property isMapAvailable False when the map itself could not be loaded, so the card can say so
 *   instead of sitting empty. Nothing else on the screen depends on it — a place can still be saved
 *   from a position the device reported.
 * @property search The search field above the map and its suggestions — see [PlaceSearchState].
 */
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

    /**
     * Take the map to this point. Sent rather than kept in the state because moving a camera is an
     * action performed once, and a state that said "be at Pulchowk" would drag the map back there
     * every time anything else on the screen changed — which is exactly the behaviour requirement 14
     * forbids.
     */
    data class MoveCamera(val at: Coordinates, val zoom: Int, val animate: Boolean) :
        LocationPickerEvent

    /** Ask for location access. Only a fragment can show the system prompt. */
    data object RequestPermission : LocationPickerEvent

    /** Something worth a Snackbar — why the current position could not be used. */
    data class Message(@param:StringRes val messageRes: Int) : LocationPickerEvent
}

/**
 * The location half of both Places forms: the map's camera, the chosen point and its name.
 *
 * **Why this is a plain class rather than a ViewModel.** Add a place and the full place form are two
 * different screens with two different ViewModels, and they need *identical* location behaviour —
 * the same two ways of choosing a point, the same rules about which one wins, the same address
 * lookup, the same three ways it can fail. Written into either ViewModel it would have to be copied
 * into the other, and the two copies would drift the first time one was fixed. So it is written once
 * and each ViewModel owns an instance, handing it their own [CoroutineScope]; from the outside it
 * behaves exactly like the rest of a ViewModel — a `StateFlow` to render and functions to call.
 *
 * It holds no Context, no View and no DAO, exactly as a ViewModel may not: the device's position and
 * the address come from [LocationRepository] and [GeocodingRepository], and the two things that need
 * an Activity — the permission prompt and the map itself — are asked for through [events].
 *
 * ### The three ways a place gets its position, and which one wins
 *
 * **Searching for it by name** ([onSearchResultSelected]) is the quickest route when the place has a
 * name: type "Patan Hospital", pick it out of the list, and the map flies there with the pin on it.
 *
 * **"I'm here"** ([useCurrentLocation]) is for the common case: the user is standing at the vet. It
 * takes a fix, flies the map there and selects it.
 *
 * **Moving the map** ([onMapCentreChanged]) is for everything else — the doorway across the road, the
 * park they are walking to later, the unnamed spot by the river. The point under the centre pin when
 * the map stops moving becomes the selection.
 *
 * **The latest one always wins, and the map is never dragged back.** The three are peers: none of them
 * is the "real" way and none of them is undone by the others having been used first. A fix is only
 * ever taken when the user asks for one — on arrival (once, and only if access is already held) and on
 * the two buttons — and a search only ever moves the map when a result is tapped. So a user who
 * searches for the hospital and then pans to its back entrance saves the back entrance, and a user who
 * presses "I'm here" and then searches saves what they searched for. Every route ends in the same
 * [select] call, which is what makes that true by construction rather than by three sets of rules
 * agreeing with each other.
 *
 * ### Why the address is looked up when the map stops, and not before
 *
 * The lookup goes over the network. Run on every frame of a drag it would be hundreds of requests for
 * one gesture, all but the last of them already wrong. So it hangs off the map's camera-idle report,
 * behind a short settle delay for the user who pans, pauses and pans again, and it is skipped
 * altogether for a move too small to have changed the answer.
 *
 * **Nothing listens for the map *starting* to move**, and that is deliberate rather than an omission.
 * An earlier version cancelled the running lookup there, on the reasonable-sounding grounds that it was
 * about to be stale — and broke "I'm here", because the camera flight that answers it begins straight
 * after the lookup for the very point it is flying to, and cancelled it every time. There was nothing
 * to gain either: the card only changes when the camera settles, so there is no stale text to hide in
 * the meantime, and a lookup made obsolete by the next one is cancelled by [resolveAddress] anyway.
 *
 * ### What the lookup answers with
 *
 * A name for the point and an address under it, and the first of those two may be a *named place* —
 * "Patan Hospital" rather than "Lalitpur" — when the pin is standing on one. Which it is, and how far
 * away a business has to be before it stops counting as the thing under the pin, is decided entirely
 * in [GeocodingRepository]; this class only carries the answer through to the state. That is what
 * makes both routes to a selection behave identically: "I'm here" and a hand-placed pin run the same
 * [resolveAddress], so a user standing inside the clinic and a user pointing at it from the sofa see
 * the same name.
 */
class PlaceLocationPicker(
    private val locationRepository: LocationRepository,
    private val geocodingRepository: GeocodingRepository,
    private val scope: CoroutineScope
) {

    private val _state = MutableStateFlow(LocationPickerState())
    val state: StateFlow<LocationPickerState> = _state.asStateFlow()

    private val _events = Channel<LocationPickerEvent>(Channel.BUFFERED)
    val events: Flow<LocationPickerEvent> = _events.receiveAsFlow()

    /**
     * The address lookup in flight, so the next camera idle can cancel it.
     *
     * Cancelling rather than letting it finish and ignoring the result: the map has already moved, so
     * the answer on its way is for a place the user has left, and letting it land would write the old
     * neighbourhood's name over the new one.
     */
    private var addressJob: Job? = null

    /** The point the current [addressJob] was started for, so a tiny move can skip the lookup. */
    private var lastGeocodedAt: Coordinates? = null

    /**
     * The debounced search in flight, so the next keystroke can cancel it.
     *
     * The same arrangement as [addressJob] and for the same reason, except that here the request has
     * usually not been made yet: the job spends most of its life asleep in the debounce delay, and
     * cancelling it there is what stops a request being sent for "Pat" on the way to "Patan".
     */
    private var searchJob: Job? = null

    // ---------------------------------------------------------------------------------------
    // Opening the screen
    // ---------------------------------------------------------------------------------------

    /**
     * Puts the map somewhere sensible when the screen opens.
     *
     * **Editing a saved place** passes its stored coordinates: the map opens exactly where the place
     * is, with the pin on it and nothing yet changed, so "keep this location" costs no taps at all.
     * The name is looked up so the card says where that is in words as well as figures.
     *
     * **Adding a new one** passes null, and then one of two things happens. If location access is
     * already held the device is asked where it is, which is the two-tap save the screen exists for.
     * If it is not, the map still opens — over the app's reference point, zoomed out to a city — and
     * **nothing is selected**. That distinction is the honest part: the starting view is somewhere to
     * pan *from*, never something to save, so the save button stays disabled until the user actually
     * chooses a point. Nothing is ever written that they did not pick.
     *
     * No permission is requested here. A prompt the user has not yet seen a reason for is the one most
     * likely to be refused, and the two buttons over the map are where the reason is.
     */
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

    // ---------------------------------------------------------------------------------------
    // Choosing by searching for it
    // ---------------------------------------------------------------------------------------

    /**
     * The user typed in the search field.
     *
     * **The debounce is the whole design of this method.** A phone keyboard produces a keystroke every
     * couple of hundred milliseconds, so searching on each one would send "P", "Pa", "Pat", "Pata",
     * "Patan" — five requests for one word, four of them already obsolete when they return, and five
     * times the quota for nothing. Instead each keystroke cancels the job the last one started and
     * begins a new one that sleeps for [SEARCH_DEBOUNCE_MILLIS] before asking anything. Only a pause
     * in the typing lets a request through.
     *
     * The field's text is updated immediately either way: the delay is on the *request*, never on the
     * characters appearing as they are typed.
     *
     * **A query too short to search collapses the list** rather than leaving the last word's results
     * under a field that no longer says that word. Clearing the box is the case that matters — the
     * results for "Patan Hospital" sitting under an empty field would be offering to select a place
     * the user has just finished un-asking for.
     */
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

            // The query is checked again on the way back for the same reason the address lookup checks
            // its coordinate: cancellation can land a moment late, and results for "Patan" written
            // under a field now reading "Pulchowk" would be offering places nobody asked about.
            val current = _state.value.search
            if (current.query.trim() != trimmed) return@launch

            _state.value = _state.value.copy(
                search = current.copy(results = results, isSearching = false, isExpanded = true)
            )
        }
    }

    /**
     * A result was tapped: this is now the chosen location.
     *
     * Everything the screen needs arrived with the suggestion — the name, the address and the point —
     * so the selection is made from it directly and **no lookup is run**. That is not a shortcut but
     * the correct answer: the user has just told the app which of five places they meant, and asking
     * the service again what is at those coordinates could only produce a *different* name for the
     * place they picked.
     *
     * Which is also why the in-flight address lookup is cancelled here. One may well be running for
     * wherever the pin was a moment ago, and it would otherwise land a second later and overwrite
     * "Patan Hospital" with the name of the street the user was looking at before they searched.
     * [lastGeocodedAt] is moved to the new point in the same breath, so the small camera settle that
     * follows the flight is not mistaken for a move worth re-naming.
     *
     * The field keeps the chosen name rather than being cleared, because it is now a record of what
     * the user asked for; the list closes, because the question has been answered.
     */
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
            // A searched place is a point on a map, not a reading off this device, so it carries none
            // of a GPS fix's doubt — see LocationPickerState.isApproximateFix.
            isApproximateFix = false,
            search = _state.value.search.copy(
                query = suggestion.name,
                isExpanded = false,
                isSearching = false
            )
        )

        // Animated, unlike the opening position: the user is being taken somewhere, and watching the
        // map travel is what tells them it moved rather than reloaded.
        sendEvent(
            LocationPickerEvent.MoveCamera(suggestion.coordinates, SELECTED_ZOOM, animate = true)
        )
    }

    /**
     * The results list should close without anything being chosen — the field lost focus, or the user
     * dismissed it.
     *
     * The results themselves are kept, so returning to the field reopens the list it had rather than
     * re-running a search the user has already paid for.
     */
    fun onSearchDismissed() {
        if (!_state.value.search.isExpanded) return
        _state.value = _state.value.copy(search = _state.value.search.copy(isExpanded = false))
    }

    /**
     * The field was focused again: show what it already found, if anything.
     *
     * Only reopens a list that has something in it. A field tapped with no results behind it stays
     * closed rather than flashing an empty panel over the map.
     */
    fun onSearchFocused() {
        val search = _state.value.search
        if (search.isExpanded || search.results.isEmpty()) return
        _state.value = _state.value.copy(search = search.copy(isExpanded = true))
    }

    /**
     * Where to bias search results towards.
     *
     * The selected point when there is one, which is where the map is looking — and otherwise the
     * app's reference point, which is *also* where the map is looking, since that is the position
     * [start] opens an unselected map over. So this is the map's centre in both cases, without the
     * picker having to track the camera: a user searching "animal hospital" wants the one down the
     * road, and the world has a great many of them.
     */
    private fun searchProximity(): Coordinates =
        _state.value.selected?.coordinates ?: HomeReference.COORDINATES

    // ---------------------------------------------------------------------------------------
    // Choosing by moving the map
    // ---------------------------------------------------------------------------------------

    /**
     * The map has come to rest under the user's finger, and this is the point beneath the centre pin.
     *
     * Only ever called for a move the *user* made — see [PlaceMapView.onCentreChangedByUser] — which
     * is what keeps flying the camera to a fresh GPS fix from being recorded as a point chosen by hand.
     *
     * The selection is replaced whole, so the name from wherever the map used to be is cleared in the
     * same breath as the coordinates change. It is then looked up again.
     *
     * **A move too small to have changed the answer keeps the name it already has**, and only the
     * coordinates are updated. That is not merely an optimisation — it is what stops the card emptying
     * itself for no reason. The map reports its centre by converting the middle of the viewport back
     * from pixels, so a re-measure (the card being laid out, the keyboard opening) can land the camera
     * a fraction of a pixel from where it was put. Treated as a fresh choice, that rounding would clear
     * the resolved address and then decline to look it up again — because the map has not moved far
     * enough to be worth a request — and the card would sit reading "Dropped pin" over a place with a
     * perfectly good name.
     */
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

    // ---------------------------------------------------------------------------------------
    // Choosing by standing there
    // ---------------------------------------------------------------------------------------

    /**
     * "I'm here", and the small recentre control in the map's corner — both, deliberately.
     *
     * They are one function because they are one action: the big button makes the feature findable for
     * somebody who has never seen it, and the corner icon is the shortcut every map app has trained
     * people to reach for. Two implementations of it would be two places for the behaviour to drift.
     *
     * **The three ways it can fail are told apart**, because the user can do something about two of
     * them and nothing about the third:
     *  - no location hardware at all — nothing to suggest, so the message simply says so;
     *  - permission not granted — the fragment is asked to show the system prompt, and if the user
     *    refuses, [onPermissionDenied] says what was lost;
     *  - location switched off in settings — the message names the switch rather than blaming the sky.
     *
     * None of them is a dead end. The map is still there and still draggable, which is the point of
     * requirement 13: a refused permission costs the user the shortcut, not the screen.
     *
     * @param animate False only for the fix taken as the screen opens, where there is nowhere to fly
     *   from and an animated arrival would just be a lurch.
     */
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
            // High accuracy because this coordinate is about to become a place's permanent geotag, and
            // a neighbourhood-level estimate would mark the wrong street. A cached fix is accepted only
            // if it is seconds old — one from a minute ago may be the bus stop the user walked from.
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

            // The selection is made here rather than waiting for the camera to arrive, so the card
            // below the map is already right if the user looks down before the flight finishes. The
            // camera move that follows is reported back as "not by the user", so it cannot overwrite
            // the source this just set.
            select(fix.coordinates, LocationSource.CURRENT_LOCATION, isApproximate = !isPrecise)
            sendEvent(LocationPickerEvent.MoveCamera(fix.coordinates, SELECTED_ZOOM, animate))
            resolveAddress(fix.coordinates, immediately = true)
        }
    }

    /**
     * The user refused the system prompt.
     *
     * Reported by the fragment, which is the only part of the app that sees the dialog's answer. Said
     * plainly and then dropped: the map is still usable, so this is a shortcut declined rather than a
     * screen broken.
     */
    fun onPermissionDenied() {
        sendEvent(LocationPickerEvent.Message(R.string.location_error_permission))
    }

    // ---------------------------------------------------------------------------------------
    // The map itself
    // ---------------------------------------------------------------------------------------

    /**
     * Switches the tile layer.
     *
     * Only the state's [LocationPickerState.mapType] changes — no camera move is sent, the selection
     * is untouched and nothing is reloaded, which is requirement 9's whole point: choosing satellite
     * to look at a rooftop must not throw away the pin that was placed on it.
     */
    fun onMapTypeSelected(type: MapType) {
        if (_state.value.mapType == type) return
        _state.value = _state.value.copy(mapType = type)
    }

    /** The map reported whether it managed to load — see [LocationPickerState.isMapAvailable]. */
    fun onMapAvailabilityChanged(isAvailable: Boolean) {
        if (_state.value.isMapAvailable == isAvailable) return
        _state.value = _state.value.copy(isMapAvailable = isAvailable)
    }

    // ---------------------------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------------------------

    /**
     * Replaces the selection whole — see [SelectedLocation] for why it is never edited in pieces.
     *
     * The approximate-fix warning is part of the same replacement rather than a flag left standing:
     * it describes *this* point's provenance, so a coarse fix followed by a hand-placed pin must not
     * leave the pin carrying the fix's doubt.
     */
    private fun select(at: Coordinates, source: LocationSource, isApproximate: Boolean = false) {
        _state.value = _state.value.copy(
            selected = SelectedLocation(coordinates = at, source = source),
            isApproximateFix = isApproximate
        )
    }

    /**
     * Looks up the name of a point and writes it onto the selection, if that is still the selection.
     *
     * **The settle delay** is for the user who pans in stages: three short drags with a pause between
     * each would otherwise be three lookups, two of which are stale before they return. A fix from the
     * device skips it, because there is nothing more to wait for.
     *
     * **The distance guard** skips the lookup entirely for a nudge — a few metres cannot change which
     * neighbourhood the pin is in, and firing a request for it would be free of any benefit and not
     * free of cost.
     *
     * **The result is checked against the current selection before it is used.** The lookup is
     * cancelled when the map moves, but a cancellation that lands a moment late must still not write
     * the old place's name over the new one.
     */
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

            // Compared with a tolerance rather than exactly, for the reason given on
            // [onMapCentreChanged]: the camera can settle a fraction of a pixel from where it was
            // sent, and an answer thrown away over that would leave the place permanently unnamed.
            _state.value = if (current != null && isEffectivelySamePoint(current.coordinates, at)) {
                _state.value.copy(
                    isResolvingAddress = false,
                    selected = current.copy(
                        placeName = resolved?.placeName,
                        addressLine = resolved?.addressLine,
                        // Carried across rather than recomputed, because only the lookup can know it:
                        // whether this name came from a named business or from an administrative area
                        // is a fact about *which question answered*, invisible in the string itself.
                        isPoiName = resolved?.isPointOfInterest == true
                    )
                )
            } else {
                _state.value.copy(isResolvingAddress = false)
            }
        }
    }

    /**
     * Whether two coordinates are close enough to be the same place for this screen's purposes.
     *
     * [MIN_MOVE_METRES] apart or less. Two points that close cannot be in different neighbourhoods, so
     * they have the same name, the same address and — for a place a person walks into — the same
     * doorway. Used for all three questions that turn on "has this actually moved?": whether to look
     * the address up again, whether an arriving answer is still about the selected point, and whether
     * a reported camera position is a choice or a rounding.
     */
    private fun isEffectivelySamePoint(a: Coordinates, b: Coordinates): Boolean =
        GeoDistance.distanceKm(a, b) * METRES_PER_KM < MIN_MOVE_METRES

    /**
     * Queues an event.
     *
     * `trySend` rather than a suspending send, so the callers above stay ordinary functions: the
     * channel is buffered and these are the only producer, so the one failure case — a full buffer —
     * cannot arise in practice.
     */
    private fun sendEvent(event: LocationPickerEvent) {
        _events.trySend(event)
    }

    private companion object {

        /**
         * Street level. This map answers "is this the right doorway?" rather than "where is this in
         * the city?", and the centre pin is only as precise as the zoom it is placed at.
         */
        const val SELECTED_ZOOM = 17

        /**
         * City level, used only for the opening view when there is no position at all.
         *
         * Wider than [SELECTED_ZOOM] because the question is different: with no fix the user is
         * hunting for their own part of town before they look for the doorway in it, and a street-level
         * map of somewhere they are not is a map they would have to drag a very long way.
         */
        const val STARTING_ZOOM = 13

        /** How long to wait for a fix, and how old a cached one may be to stand in for it. */
        const val FIX_TIMEOUT_MILLIS = 10_000L
        const val MAX_CACHE_AGE_MILLIS = 30_000L

        /** How long the map must stay still before its new centre is worth a network lookup. */
        const val SETTLE_DELAY_MILLIS = 450L

        /**
         * How long the typing must pause before the search field asks the service anything: 350ms.
         *
         * Short enough that the list appears while the user is still looking at the field — any longer
         * and it feels as though nothing is happening — and long enough to cover the gap between
         * keystrokes of ordinary typing, which is what collapses "P", "Pa", "Pat", "Pata", "Patan"
         * into a single request for "Patan".
         */
        const val SEARCH_DEBOUNCE_MILLIS = 350L

        /** Below this, the pin has not moved far enough to have changed its answer. */
        const val MIN_MOVE_METRES = 25.0
        const val METRES_PER_KM = 1_000.0
    }
}
