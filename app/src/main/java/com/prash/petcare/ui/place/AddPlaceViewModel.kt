package com.prash.petcare.ui.place

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.prash.petcare.PetCareApplication
import com.prash.petcare.R
import com.prash.petcare.data.local.entity.Place
import com.prash.petcare.data.local.entity.PlaceCategory
import com.prash.petcare.data.location.LocationFix
import com.prash.petcare.data.location.LocationRepository
import com.prash.petcare.data.places.PlaceSearchRepository
import com.prash.petcare.data.places.PlaceSuggestion
import com.prash.petcare.data.repository.PetRepository
import com.prash.petcare.data.repository.PlaceRepository
import com.prash.petcare.util.Coordinates
import com.prash.petcare.util.PinMapProjection
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The three ways a place's position can be fixed on the Add a place screen.
 *
 * **They are peers, not a ladder.** Every one of them saves a place just as completely as the others,
 * all three are reachable at any moment, and none is gated behind a permission. That is the feature's
 * central rule: a user planning a vet visit from their sofa must be able to save the clinic, and an
 * app that insisted on a GPS fix would make "save this place" mean "go and stand there first".
 *
 * Each one names where its coordinates come from, and those three answers are the whole design:
 *
 *  - [SEARCH] — from the places/geocoding lookup that returned the chosen result. Never the device.
 *  - [PIN] — from the marker's position on the map, projected onto the visible bounding box by
 *    [PinMapProjection]. Never the device.
 *  - [HERE] — from the device's GPS. The *only* method that reads it, and a convenience rather than
 *    a requirement.
 */
enum class AddPlaceMethod { SEARCH, PIN, HERE }

/**
 * Everything the Add a place screen shows.
 *
 * **Why the typed text lives here**, when the app's other forms leave it in the EditTexts and let
 * them restore themselves. On this screen the name is not only typed: tapping a search result fills
 * it, the summary card reads it back, and the save button is enabled or disabled by it. Three things
 * outside the box depend on its value, so the box cannot be the only one who knows it. The note and
 * the query are held alongside it for consistency rather than necessity.
 *
 * @property method Which of the three ways the user is currently using. [AddPlaceMethod.SEARCH] to
 *   begin with, because it is the one that needs nothing — no permission, no map gesture.
 * @property query What has been typed into the search box.
 * @property results The matches for [query]; the first few entries as suggestions when it is blank.
 * @property selectedSuggestion The result the user tapped, or null. Kept as the whole suggestion
 *   rather than an id, because its coordinates are what will be saved.
 * @property pinXPercent How far across the map the marker sits, 0–100.
 * @property pinYPercent How far down the map the marker sits, 0–100.
 * @property isLocating True while a GPS fix is being waited for.
 * @property fix The device's position and how accurate it claims to be, once one has arrived.
 * @property areaName The neighbourhood [fix] falls in, once reverse geocoding has answered.
 * @property locationMessage Why there is no fix — permission refused, or the attempt failed. Shown
 *   on the current-location card in place of a position, so the method explains itself rather than
 *   sitting empty.
 * @property name What the place will be called. The one value that decides whether it can be saved.
 * @property category The chosen category pill.
 * @property note The user's own note, or blank.
 * @property isSaving True while the write is in flight, so the button cannot be pressed twice.
 */
data class AddPlaceUiState(
    val method: AddPlaceMethod = AddPlaceMethod.SEARCH,
    val query: String = "",
    val results: List<PlaceSuggestion> = emptyList(),
    val selectedSuggestion: PlaceSuggestion? = null,
    val pinXPercent: Float = DEFAULT_PIN_X_PERCENT,
    val pinYPercent: Float = DEFAULT_PIN_Y_PERCENT,
    val isLocating: Boolean = false,
    val fix: LocationFix? = null,
    val areaName: String? = null,
    @param:StringRes val locationMessage: Int? = null,
    val name: String = "",
    val category: PlaceCategory = PlaceCategory.VET,
    val note: String = "",
    val isSaving: Boolean = false
) {

    /** Where the marker currently sits, in degrees. Recomputed on every drag; see [PinMapProjection]. */
    val pinCoordinates: Coordinates
        get() = PinMapProjection.coordinatesAt(pinXPercent, pinYPercent)

    /** How far the marker is from the map's home dot, in kilometres. */
    val pinDistanceKm: Double
        get() = PinMapProjection.distanceFromHomeKm(pinXPercent, pinYPercent)

    /**
     * Whether "SAVE PLACE" can be pressed.
     *
     * A name and nothing else. Not a chosen search result, not a fix, not a moved pin: the position
     * always has an answer (see [AddPlaceViewModel.coordinatesForSave]), and a place the user cannot
     * name is one they will not recognise in the list afterwards.
     */
    val canSave: Boolean
        get() = name.isNotBlank() && !isSaving
}

/** A one-off occurrence the Add a place screen reacts to exactly once. */
sealed interface AddPlaceEvent {

    /**
     * The place was written. Carries what the Places tab needs to feature it and to offer the undo:
     * its new row id and the name to put in the confirmation.
     */
    data class Saved(val placeId: Long, val name: String) : AddPlaceEvent

    /** Something worth a Snackbar that is not tied to one field. */
    data class Message(@param:StringRes val messageRes: Int) : AddPlaceEvent
}

/**
 * Backs the Add a place screen.
 *
 * In MVVM this is the **ViewModel**. It owns the three methods' state, asks
 * [PlaceSearchRepository] for matches, asks [LocationRepository] for a fix when — and only when —
 * the user chooses "I'm here", and writes through [PlaceRepository]. It holds no Context, no View and
 * no DAO; the fragment supplies the permission prompt, formats the text and draws the result.
 *
 * ### Where each method's coordinates come from
 *
 * This is the one thing worth reading the class for, so it is stated in one place and repeated at
 * each use site:
 *
 * | Method | Source of latitude/longitude | Reads the device? |
 * |---|---|---|
 * | Search | the coordinates returned with the chosen result | no |
 * | Drop pin | the marker's position projected onto the map's bounding box | no |
 * | I'm here | the fused location provider | yes — and nowhere else |
 *
 * A place saved with a typed name and no chosen result has no coordinates from anywhere, and takes
 * the map's home reference point as a stand-in — see [coordinatesForSave] for why that is the least
 * wrong answer available.
 *
 * ### Why the GPS is never asked for on arrival
 *
 * [requestCurrentLocation] is called by the fragment only after the user has both chosen "I'm here"
 * *and* granted permission. Refusing it costs nothing: the card explains itself, the other two
 * methods are untouched, and the form still saves. That is what "never gate a method behind a
 * permission" means in practice.
 *
 * @param ownerId The signed-in account the new place is saved under.
 */
class AddPlaceViewModel(
    private val placeRepository: PlaceRepository,
    private val placeSearchRepository: PlaceSearchRepository,
    private val locationRepository: LocationRepository,
    petRepository: PetRepository,
    private val ownerId: Long
) : ViewModel() {

    private val _uiState = MutableStateFlow(AddPlaceUiState())
    val uiState: StateFlow<AddPlaceUiState> = _uiState.asStateFlow()

    private val _events = Channel<AddPlaceEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    /**
     * How many pets the account has, for the shared header's badge.
     *
     * Kept apart from [uiState] rather than folded into it: it is the one value on this screen that
     * belongs to the *header* rather than to the form, it changes for reasons nothing here controls,
     * and combining it in would rebuild the whole form state every time a pet was added elsewhere.
     * Every screen drawing the shared header supplies this count from its own ViewModel — see
     * TabHeader.showPetCount.
     */
    val petCount: StateFlow<Int> = petRepository.observePetsForOwner(ownerId)
        .map { it.size }
        .stateIn(
            scope = viewModelScope,
            // Five seconds of grace keeps the Room query alive across a rotation, when the old view
            // stops collecting a moment before the new one starts.
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = 0
        )

    init {
        // The screen opens on Search with nothing typed, and a blank column under the box would look
        // like a failure. The first few directory entries stand in as suggestions.
        search(query = "")
    }

    // ---------------------------------------------------------------------------------------
    // Choosing a method
    // ---------------------------------------------------------------------------------------

    /**
     * Switches method.
     *
     * Nothing is thrown away: the query, the chosen result, the marker's position and any fix all
     * survive, so a user who looks at the map and goes back to the search finds it as they left it.
     * The only side effect is that picking "I'm here" with a fix already in hand does not ask for
     * another — the fragment decides when to call [requestCurrentLocation], because only it can ask
     * for the permission.
     */
    fun selectMethod(method: AddPlaceMethod) {
        if (_uiState.value.method == method) return
        _uiState.value = _uiState.value.copy(method = method)
    }

    // ---------------------------------------------------------------------------------------
    // Method 1 — search
    // ---------------------------------------------------------------------------------------

    /** The search box was edited. Results are filtered live, on every keystroke. */
    fun onQueryChanged(query: String) {
        if (_uiState.value.query == query) return
        _uiState.value = _uiState.value.copy(query = query)
        search(query)
    }

    /**
     * Runs the lookup and publishes the results.
     *
     * The chosen result is deliberately *not* cleared when the query changes. A user who picks
     * "Patan Animal Hospital" and then edits the name in the field above has still chosen that
     * clinic's position; dropping it because the query no longer matches would silently move the
     * place they are saving.
     */
    private fun search(query: String) {
        viewModelScope.launch {
            val results = placeSearchRepository.search(query)
            _uiState.value = _uiState.value.copy(results = results)
        }
    }

    /**
     * A search result was tapped.
     *
     * Fills the name and selects the category, and both stay editable afterwards — the result is a
     * starting point, not a lock. The position is taken from the result itself, which is what makes
     * this method work from anywhere.
     *
     * Tapping the chosen result again clears the choice, which is the only way back out of it once
     * the name has been edited.
     */
    fun onSuggestionTapped(suggestion: PlaceSuggestion) {
        val state = _uiState.value

        if (state.selectedSuggestion?.id == suggestion.id) {
            _uiState.value = state.copy(selectedSuggestion = null)
            return
        }

        _uiState.value = state.copy(
            selectedSuggestion = suggestion,
            name = suggestion.name,
            category = suggestion.category
        )
    }

    // ---------------------------------------------------------------------------------------
    // Method 2 — drop or drag a pin
    // ---------------------------------------------------------------------------------------

    /**
     * The marker was tapped or dragged to a new point on the map.
     *
     * Percentages rather than pixels, so the position survives a rotation and means the same thing on
     * any screen width. Clamped here rather than in the view, because "the marker may not leave the
     * map" is a fact about the model's valid range, not about how it is drawn — and the coordinates
     * derived from it must never come from outside the box the projection describes.
     */
    fun onPinMoved(xPercent: Float, yPercent: Float) {
        _uiState.value = _uiState.value.copy(
            pinXPercent = PinMapProjection.clampX(xPercent),
            pinYPercent = PinMapProjection.clampY(yPercent)
        )
    }

    // ---------------------------------------------------------------------------------------
    // Method 3 — the device's current location
    // ---------------------------------------------------------------------------------------

    /**
     * Takes a GPS fix and resolves the neighbourhood it falls in.
     *
     * Called by the fragment once — and only once — the user has chosen "I'm here" and location
     * access has been granted. **This is the one place in the feature that reads the device.**
     *
     * A fresh fix rather than a cached one: this position is about to be saved as a place, and "where
     * the phone was an hour ago" is not where the user is standing now. Precise accuracy is asked for
     * when precise access was granted, and accepted at coarse accuracy when it was not — a
     * neighbourhood-level position is still a usable answer, and the accuracy figure on the card says
     * so honestly.
     *
     * The area name is a second, slower lookup, so it is published on its own: the card shows the
     * coordinates as soon as they arrive rather than waiting for words to put to them.
     */
    fun requestCurrentLocation() {
        if (_uiState.value.isLocating) return

        val precise = locationRepository.hasPreciseLocationPermission()
        _uiState.value = _uiState.value.copy(isLocating = true, locationMessage = null)

        viewModelScope.launch {
            val fix = locationRepository.getCurrentFix(highAccuracy = precise, allowCached = false)

            if (fix == null) {
                // A failed fix leaves any previous one in place: pressing the method again indoors
                // should not throw away a good position taken by a window a minute ago.
                _uiState.value = _uiState.value.copy(
                    isLocating = false,
                    locationMessage = R.string.add_place_here_failed
                )
                return@launch
            }

            _uiState.value = _uiState.value.copy(isLocating = false, fix = fix, areaName = null)

            val area = locationRepository.describeArea(fix.coordinates)
            // Guarded: a second fix may have arrived while the geocoder was working, and the name
            // that came back describes the older one.
            if (_uiState.value.fix == fix) {
                _uiState.value = _uiState.value.copy(areaName = area)
            }
        }
    }

    /**
     * Location access was refused.
     *
     * Reported as a line on the card, not as a failure of the screen. The two other methods keep
     * working, the form still saves, and nothing is disabled — see the class comment.
     */
    fun onLocationDenied() {
        _uiState.value = _uiState.value.copy(
            isLocating = false,
            locationMessage = R.string.add_place_here_denied
        )
    }

    // ---------------------------------------------------------------------------------------
    // The shared save fields
    // ---------------------------------------------------------------------------------------

    fun onNameChanged(name: String) {
        if (_uiState.value.name == name) return
        _uiState.value = _uiState.value.copy(name = name)
    }

    fun onCategorySelected(category: PlaceCategory) {
        _uiState.value = _uiState.value.copy(category = category)
    }

    fun onNoteChanged(note: String) {
        if (_uiState.value.note == note) return
        _uiState.value = _uiState.value.copy(note = note)
    }

    // ---------------------------------------------------------------------------------------
    // Saving
    // ---------------------------------------------------------------------------------------

    /**
     * Writes the place and reports its new id.
     *
     * The record is assembled from the three sources the screen has: the name, category and note the
     * user gave, the coordinates the chosen method produced, and the fixed values a place saved this
     * way starts life with — no opening hours, no phone number, and the "saved by you" provenance
     * shown beside its category on the Places tab. Hours and a number can be filled in later from the
     * full place form; demanding them here would turn a two-tap save into a form.
     *
     * Once the row is in, the Places tab redraws by itself — it observes Room — so the saved place
     * appears in the list, on the distance ruler and under the category filters with nothing else to
     * do. The event carries the id only so that tab can *feature* the new place and offer the undo.
     */
    fun save(savedByMeta: String, defaultNote: String) {
        val state = _uiState.value
        if (!state.canSave) return

        val trimmedName = state.name.trim()
        val coordinates = coordinatesForSave(state)

        viewModelScope.launch {
            _uiState.value = state.copy(isSaving = true)

            val placeId = placeRepository.addPlace(
                Place(
                    ownerId = ownerId,
                    name = trimmedName,
                    category = state.category,
                    // Renders as "Vet clinic · saved by you" under the name, which is what marks a
                    // place added from this screen apart from one entered through the full form.
                    area = savedByMeta,
                    latitude = coordinates.latitude,
                    longitude = coordinates.longitude,
                    // Left unset rather than guessed. The Places tab already draws "Hours not set"
                    // and an unsaved phone number as plain text rather than as broken controls.
                    phone = null,
                    openingMinutes = null,
                    closingMinutes = null,
                    notes = state.note.trim().takeIf { it.isNotEmpty() } ?: defaultNote
                )
            )

            _events.send(AddPlaceEvent.Saved(placeId = placeId, name = trimmedName))
        }
    }

    /**
     * The coordinates to store, by method. The single point where the three sources meet.
     *
     *  - **Search:** the chosen result's own coordinates, as the lookup returned them.
     *  - **Drop pin:** the marker's position, projected onto the map's bounding box.
     *  - **I'm here:** the device fix, if one arrived.
     *
     * **The fallback**, used when the search found nothing to choose and the user simply typed a name,
     * or when "I'm here" never got a fix, is the map's home reference point. A [Place] must have
     * coordinates — the whole Places tab is built on them — and of the available answers this is the
     * only honest one: it is the same point the screen measures its distances from, it is not a
     * position anybody has claimed the place occupies, and the user can correct it by editing the
     * place. Inventing a position from the device would be worse, because it would look precise.
     */
    private fun coordinatesForSave(state: AddPlaceUiState): Coordinates = when (state.method) {
        AddPlaceMethod.SEARCH -> state.selectedSuggestion?.coordinates ?: PinMapProjection.home()
        AddPlaceMethod.PIN -> state.pinCoordinates
        AddPlaceMethod.HERE -> state.fix?.coordinates ?: PinMapProjection.home()
    }

    companion object {

        fun factory(ownerId: Long): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application =
                    this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as PetCareApplication
                AddPlaceViewModel(
                    placeRepository = application.placeRepository,
                    placeSearchRepository = application.placeSearchRepository,
                    locationRepository = application.locationRepository,
                    petRepository = application.petRepository,
                    ownerId = ownerId
                )
            }
        }
    }
}

/**
 * Where the marker starts, a little above and right of the home dot.
 *
 * Not *on* the home dot: the two marks would overlap and the pin would look like part of the "you are
 * here" indicator rather than something separate to move. Offset far enough to read as a distinct
 * thing, near enough that the first drag is short.
 */
private const val DEFAULT_PIN_X_PERCENT = 56f
private const val DEFAULT_PIN_Y_PERCENT = 40f
