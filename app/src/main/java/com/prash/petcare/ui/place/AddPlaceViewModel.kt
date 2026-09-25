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
import com.prash.petcare.data.location.LocationRepository
import com.prash.petcare.data.repository.PetRepository
import com.prash.petcare.data.repository.PlaceRepository
import com.prash.petcare.util.Coordinates
import com.prash.petcare.util.HomeReference
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
 * How the hunt for the device's position is going.
 *
 * Five states rather than a nullable fix plus a boolean, because the screen says something different
 * in each and the four "no position" cases are not interchangeable — "still looking" invites waiting,
 * "permission denied" invites granting it, and "no location hardware" invites neither.
 *
 *  - [IDLE] — nothing asked for yet. Only ever the state of a freshly built ViewModel: the screen
 *    asks for a fix the moment it opens, so the user never sees it.
 *  - [LOCATING] — a request is in flight.
 *  - [FOUND] — there is a position. The *only* state in which a place can be saved.
 *  - [DENIED] — there is no position and asking again might help: access was refused, or the fix
 *    failed. The two are told apart by [GpsState.message], not by a separate state, because the
 *    screen offers the same two buttons for either.
 *  - [UNSUPPORTED] — there is no position and asking again cannot help, because the device has no
 *    location capability at all.
 */
enum class GpsStatus { IDLE, LOCATING, FOUND, DENIED, UNSUPPORTED }

/**
 * Everything known about where the device is.
 *
 * **Why the coordinates live inside this object rather than beside it.** A latitude with no status,
 * or a status with no latitude, are both states the screen must never be able to draw — "found" with
 * nothing to show, or a stale position under a "permission denied" message. Keeping them in one value
 * that is replaced whole makes those unrepresentable: every state change writes both at once.
 *
 * @property status Which of the five conditions above holds.
 * @property coordinates Where the device is, set only when [status] is [GpsStatus.FOUND].
 * @property accuracyMetres The radius the device believes the true position lies within — the figure
 *   shown as "±8 m". Null when the platform reported none, which is possible for a fix derived from
 *   cell towers alone. Only meaningful alongside [coordinates].
 * @property isDemo True when [coordinates] is the stand-in position rather than a real fix. Carried
 *   so the screen can *say so* — a demo position that passed for a live one would let a user save a
 *   place geotagged somewhere they have never been.
 * @property message Why there is no position, as a string resource. Null whenever there is one.
 */
data class GpsState(
    val status: GpsStatus = GpsStatus.IDLE,
    val coordinates: Coordinates? = null,
    val accuracyMetres: Float? = null,
    val isDemo: Boolean = false,
    @param:StringRes val message: Int? = null
) {

    /** True when there is a position to save — the one thing the rest of the screen asks of this. */
    val isFound: Boolean get() = status == GpsStatus.FOUND && coordinates != null

    /**
     * True when the demo position should be offered: there is no fix, and no amount of retrying will
     * produce one that the user has not already refused. Never while a request is still in flight,
     * where offering a way around it would pre-empt an answer that may still arrive.
     */
    val canUseDemo: Boolean
        get() = status == GpsStatus.DENIED || status == GpsStatus.UNSUPPORTED

    /** How far the fix is from the app's home reference point, or null when there is no fix. */
    val distanceFromHomeKm: Double?
        get() = coordinates?.let { HomeReference.distanceKmFrom(it) }
}

/**
 * Everything the Add a place screen shows.
 *
 * **Why the typed text lives here**, when the app's other forms leave it in the EditTexts and let
 * them restore themselves. On this screen the name is not only typed: the summary card reads it back
 * and the save button is enabled or disabled by it, so two things outside the box depend on its
 * value and the box cannot be the only one who knows it. The note is held alongside it for
 * consistency rather than necessity.
 *
 * @property gps Where the device is, and how that is going. See [GpsState].
 * @property pickedCoordinates Where the user dragged the map's pin to, or null while the place is
 *   still being saved at the device's own position. Held *beside* [gps] rather than written into it:
 *   [GpsState] answers "where is the phone?", and a pin the user moved two streets over is not an
 *   answer to that question. Keeping them apart is what lets the card go on reporting the real fix —
 *   its accuracy, its distance from home — while the place being saved sits somewhere else.
 * @property name What the place will be called.
 * @property category The chosen category pill.
 * @property note The user's own note, or blank.
 * @property isSaving True while the write is in flight, so the button cannot be pressed twice.
 */
data class AddPlaceUiState(
    val gps: GpsState = GpsState(),
    val pickedCoordinates: Coordinates? = null,
    val name: String = "",
    val category: PlaceCategory = PlaceCategory.VET,
    val note: String = "",
    val isSaving: Boolean = false
) {

    /**
     * The coordinates the place will actually be saved at: the dropped pin if there is one, otherwise
     * the device's own position.
     *
     * One property that every part of the screen reads — the map's centre, the summary line, the save
     * button's enabled state and [AddPlaceViewModel.save] — so none of them can disagree about where
     * the place is. A saved place whose map card showed one point while its geotag carried another
     * would be the worst kind of bug here, because nothing about it would look wrong.
     */
    val coordinates: Coordinates? get() = pickedCoordinates ?: gps.coordinates

    /** True when the pin has been moved off the device's position, so the screen can say so. */
    val isPositionPicked: Boolean get() = pickedCoordinates != null

    /**
     * Where the map card is centred, which is **not** the same question as where the place is.
     *
     * Usually it is [coordinates] and the two agree. The case they part company is the one this
     * property exists for: the device has given up — permission refused, no hardware, or the fix
     * timed out — and there is no position at all. The screen used to answer that by hiding the map,
     * on the sound reasoning that a map of nowhere in particular looks like an answer when it is not.
     *
     * That reasoning expired when the map became draggable. The user can now place the pin themselves,
     * so taking the map away leaves them holding the one tool that would have solved their problem;
     * a phone that cannot see the sky is precisely when somebody needs to point at the place by hand.
     * So the map is shown from a neutral starting point instead, and they drag from there.
     *
     * **It stays honest** because it is kept apart from [coordinates]. The starting point is somewhere
     * to look from, never something to save: [canSave] reads `coordinates`, which is still null, so the
     * button stays disabled until the user actually drops the pin. Nothing is ever written that the
     * user did not choose.
     *
     * Null while a fix may still arrive, where the placeholder's "Finding your position…" is the more
     * useful thing to show than a map about to be replaced.
     */
    val mapCentre: Coordinates? get() = coordinates ?: HomeReference.COORDINATES.takeIf { gps.canUseDemo }

    /** True when the map is up as a *starting point* and the user has yet to choose anything on it. */
    val isAwaitingPin: Boolean get() = coordinates == null && mapCentre != null

    /**
     * Whether "SAVE PLACE" can be pressed.
     *
     * Both conditions, not either: a place needs a name its owner will recognise in the list, and it
     * needs coordinates, because the whole Places tab is built on them — the map centres on them and
     * Directions navigates to them. Saving without either a fix or a dropped pin would mean inventing
     * a position, and an invented geotag is worse than an unsaved place because it looks exactly like
     * a real one.
     */
    val canSave: Boolean
        get() = name.isNotBlank() && coordinates != null && !isSaving
}

/** A one-off occurrence the Add a place screen reacts to exactly once. */
sealed interface AddPlaceEvent {

    /**
     * The place was written. Carries what the Places tab needs to select it on the map and to offer
     * the undo: its new row id and the name to put in the confirmation.
     */
    data class Saved(val placeId: Long, val name: String) : AddPlaceEvent

    /** Something worth a Snackbar that is not tied to one field. */
    data class Message(@param:StringRes val messageRes: Int) : AddPlaceEvent
}

/**
 * Backs the Add a place screen.
 *
 * In MVVM this is the **ViewModel**. It asks [LocationRepository] for a fix, holds the form's values
 * and writes the finished place through [PlaceRepository]. It holds no Context, no View and no DAO;
 * the fragment supplies the permission prompt, formats the text and draws the result.
 *
 * ### Where a place's position comes from
 *
 * The device's GPS starts it, through [locate]: the user is usually standing at the vet, so the phone
 * already knows roughly where the place is and the fastest possible save is "type the name, press
 * save".
 *
 * From there the pin can be **moved**, through [onCentrePicked]. That is not a second source competing
 * with the first — it is the fix being corrected by the one party who can: the phone reports where the
 * *phone* is, to within a few metres at best, and the user is the only one who knows whether the place
 * is this doorway or the one across the road. It also covers the case the GPS cannot: saving the park
 * you are going to visit rather than the room you are sitting in.
 *
 * [useDemoLocation] is the third and last way, and it is honest about itself: the position it sets is
 * marked [GpsState.isDemo] and the screen says "demo" wherever it says anything about the fix.
 *
 * ### Why the fix is asked for on arrival, unlike everywhere else in the app
 *
 * The app's rule elsewhere is never to prompt for a permission as a screen opens, because a prompt
 * the user has not yet seen a reason for is the one most likely to be refused. This screen is the
 * exception that proves it: its heading, its subtitle and its map all say "we are about to save
 * where you are", so by the time the system dialog appears the reason is already on screen — and
 * waiting for a second tap would make a two-tap flow a four-tap one.
 *
 * @param ownerId The signed-in account the new place is saved under.
 */
class AddPlaceViewModel(
    private val placeRepository: PlaceRepository,
    private val locationRepository: LocationRepository,
    petRepository: PetRepository,
    private val ownerId: Long
) : ViewModel() {

    private val _uiState = MutableStateFlow(AddPlaceUiState())
    val uiState: StateFlow<AddPlaceUiState> = _uiState.asStateFlow()

    private val _events = Channel<AddPlaceEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    /**
     * Whether the map card is showing Google's aerial imagery rather than its drawn map.
     *
     * Kept apart from [uiState] rather than folded into it: it is a fact about *how the map is drawn*
     * and about nothing else on the screen. Nothing in the form
     * depends on it, the place saved is identical either way, and putting it in the state would rebuild
     * the whole form — re-running the summary line, the chip row and the save button's label — every
     * time the user flipped the layer.
     *
     * It lives here rather than in the fragment so the choice survives a rotation. A user who switched
     * to imagery to find a rooftop, then turned the phone, should not be handed the drawn map back.
     */
    private val _isSatellite = MutableStateFlow(false)
    val isSatellite: StateFlow<Boolean> = _isSatellite.asStateFlow()

    /** Flips between the drawn map and the imagery. */
    fun toggleMapLayer() {
        _isSatellite.value = !_isSatellite.value
    }

    // ---------------------------------------------------------------------------------------
    // Finding the device
    // ---------------------------------------------------------------------------------------

    /**
     * Asks the device where it is, and publishes whichever of the four answers comes back.
     *
     * Called when the screen opens and again whenever "Refresh location" or "Try again" is pressed —
     * the same function for both, because they are the same request; only the button's label differs,
     * and that is the fragment's business.
     *
     * **The four outcomes**, in the order they are decided:
     *
     *  1. The device has no location capability at all → [GpsStatus.UNSUPPORTED]. Checked first
     *     because it makes the other three impossible, and because it is the one case where asking
     *     again is pointless.
     *  2. The permission is not held → [GpsStatus.DENIED], "Location permission was denied". Tested
     *     here as well as by the fragment's prompt, because access can be revoked from system
     *     settings while the app sits in the background.
     *  3. No fix arrived within the timeout → [GpsStatus.DENIED], "try again outdoors". The same
     *     state as a refusal, because the screen offers the same two ways forward for either.
     *  4. A fix arrived → [GpsStatus.FOUND], with the coordinates and the device's own accuracy.
     *
     * **The request's shape** mirrors the design's `getCurrentPosition` options exactly: high
     * accuracy, a ten-second ceiling, and a cached fix accepted only if it is less than thirty
     * seconds old. High accuracy because this position is about to be stored as a place's permanent
     * geotag, and a neighbourhood-level estimate would mark the wrong street; the narrow cache window
     * because a fix from a minute ago may be from the bus stop the user walked from, while one from
     * twenty seconds ago is where they are standing.
     *
     * **A failure clears any previous fix**, rather than leaving the old position under the new
     * message. That is the invariant [GpsState] exists to keep: "denied" with a latitude still
     * showing would let a user save a geotag the screen has just said it cannot vouch for. The cost
     * is that refreshing indoors loses a good fix taken by a window a moment earlier — which the
     * button's "Try again" label then invites them to retake.
     */
    fun locate() {
        if (_uiState.value.gps.status == GpsStatus.LOCATING) return

        if (!locationRepository.hasLocationCapability()) {
            setGps(GpsStatus.UNSUPPORTED, R.string.add_place_gps_unsupported)
            return
        }

        if (!locationRepository.hasLocationPermission()) {
            setGps(GpsStatus.DENIED, R.string.add_place_gps_denied)
            return
        }

        updateGps { it.copy(status = GpsStatus.LOCATING, message = null) }

        viewModelScope.launch {
            val fix = locationRepository.getCurrentFix(
                highAccuracy = true,
                allowCached = true,
                timeoutMillis = FIX_TIMEOUT_MILLIS,
                maxCacheAgeMillis = MAX_CACHE_AGE_MILLIS
            )

            if (fix == null) {
                setGps(GpsStatus.DENIED, R.string.add_place_gps_failed)
                return@launch
            }

            // A new fix drops any pin the user had dragged: "Refresh location" means "put the map back
            // on me", and leaving the pin where it was would make the button appear to do nothing.
            // A *failed* refresh above leaves the pin alone, because losing a point the user chose on
            // purpose because the GPS could not see the sky would be the wrong way round.
            _uiState.value = _uiState.value.copy(
                pickedCoordinates = null,
                gps = GpsState(
                    status = GpsStatus.FOUND,
                    coordinates = fix.coordinates,
                    accuracyMetres = fix.accuracyMetres,
                    isDemo = false,
                    message = null
                )
            )
        }
    }

    /**
     * The user dragged the map, and this is the point now under its centre.
     *
     * Reported by the fragment, which owns the gesture — see MapEmbedView for why a coordinate panned
     * to inside Google's embedded map has to be measured by the app rather than read back out of it.
     *
     * Stored as the *picked* position rather than written over [GpsState.coordinates], for the reason
     * given on [AddPlaceUiState.pickedCoordinates]: the fix is a fact about the phone and stays true
     * however far the pin travels.
     */
    fun onCentrePicked(coordinates: Coordinates) {
        if (_uiState.value.pickedCoordinates == coordinates) return
        _uiState.value = _uiState.value.copy(pickedCoordinates = coordinates)
    }

    /**
     * The user refused the system permission prompt.
     *
     * Reported by the fragment, which is the only part of the app that can see the dialog's answer.
     * Recorded as a state rather than treated as a failure of the screen: the demo position below
     * appears beside it, so the form can still be filled in and saved.
     */
    fun onLocationDenied() {
        setGps(GpsStatus.DENIED, R.string.add_place_gps_denied)
    }

    /**
     * Falls back to a fixed stand-in position — "Jhamsikhel, Lalitpur (demo)".
     *
     * **Why this exists at all.** Without it, a user who refuses the location permission, or whose
     * phone cannot get a fix indoors, reaches a screen where the save button can never be pressed —
     * a dead end with no way out but leaving. It also makes the feature demonstrable on an emulator
     * with no location configured, which for coursework being marked is not a small thing.
     *
     * **Why it is safe.** The position is flagged [GpsState.isDemo] and every line that mentions the
     * fix says so: the map's status chip reads "DEMO LOCATION" rather than "LIVE GPS", and the card's
     * title carries "(demo)". Nothing pretends this is where the phone is.
     */
    fun useDemoLocation() {
        // Clears the pin for the same reason a fresh fix does: this is the map being put somewhere
        // definite, and the pin can be dragged from there.
        _uiState.value = _uiState.value.copy(
            pickedCoordinates = null,
            gps = GpsState(
                status = GpsStatus.FOUND,
                coordinates = DEMO_COORDINATES,
                accuracyMetres = DEMO_ACCURACY_METRES,
                isDemo = true,
                message = null
            )
        )
    }

    /** Replaces the GPS state wholesale — see [GpsState] for why it is never edited in pieces. */
    private fun setGps(status: GpsStatus, @StringRes message: Int) {
        updateGps { GpsState(status = status, message = message) }
    }

    /** Applies [transform] to the current GPS state and republishes the screen. */
    private fun updateGps(transform: (GpsState) -> GpsState) {
        _uiState.value = _uiState.value.copy(gps = transform(_uiState.value.gps))
    }

    // ---------------------------------------------------------------------------------------
    // The form
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
     * user gave; the coordinates the GPS produced; and the fixed values a place saved this way starts
     * life with — no opening hours, no phone number, and the "saved by you" provenance shown beside
     * its category on the Places tab. Hours and a number can be filled in later from the full place
     * form; demanding them here would turn a two-tap save into a form.
     *
     * The coordinates are [AddPlaceUiState.coordinates] — the dropped pin if the user moved one, and
     * the device's own fix otherwise, decided in one place so the geotag written here is the same point
     * the map card was showing. [AddPlaceUiState.canSave] guarantees they exist, which is why the
     * `?: return` below is a guard against a race (a state change between the tap and this line)
     * rather than a case the screen expects.
     *
     * Once the row is in, the Places tab redraws by itself — it observes Room — so the saved place
     * appears in the list and under the category filters with nothing else to do. The event carries
     * the id only so that tab can *select* the new place, which is what re-centres its map.
     */
    fun save(savedByMeta: String, defaultNote: String) {
        val state = _uiState.value
        if (!state.canSave) return

        val coordinates = state.coordinates ?: return
        val trimmedName = state.name.trim()

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

    companion object {

        fun factory(ownerId: Long): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application =
                    this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as PetCareApplication
                AddPlaceViewModel(
                    placeRepository = application.placeRepository,
                    locationRepository = application.locationRepository,
                    petRepository = application.petRepository,
                    ownerId = ownerId
                )
            }
        }

        /**
         * How long to wait for a fix before giving up, and how old a cached one may be to stand in
         * for it. Both come straight from the design's `getCurrentPosition` options.
         *
         * Ten seconds is long enough for a warm GPS lock outdoors and short enough that a user
         * indoors gets an answer rather than a spinner. Thirty seconds of cache is roughly how long
         * somebody stays in one place while opening a screen and reading its heading.
         */
        private const val FIX_TIMEOUT_MILLIS = 10_000L
        private const val MAX_CACHE_AGE_MILLIS = 30_000L

        /**
         * The stand-in position: Jhamsikhel, Lalitpur, a few hundred metres from the home reference
         * point so the "0.4 km from home" line has something to say. Its accuracy is stated as 8 m,
         * the figure a good urban GPS fix reports, so the card looks the way a real one would — the
         * point of the demo being to show the flow, not to imitate a failure.
         */
        private val DEMO_COORDINATES = Coordinates(latitude = 27.6743, longitude = 85.3094)
        private const val DEMO_ACCURACY_METRES = 8f
    }
}
