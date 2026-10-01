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
import com.prash.petcare.data.location.GeocodingRepository
import com.prash.petcare.data.location.LocationRepository
import com.prash.petcare.data.repository.PlaceRepository
import com.prash.petcare.ui.common.messageRes
import com.prash.petcare.util.PhoneCheck
import com.prash.petcare.util.PhoneNumbers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The part of the Add a place form this ViewModel types into, as distinct from where the place *is*.
 *
 * **Why the typed text lives here**, when the app's other forms leave it in the EditTexts and let them
 * restore themselves: the name is not only typed, it is read back by the summary card and it decides
 * whether the save button can be pressed, so two things outside the box depend on its value and the box
 * cannot be the only one who knows it. The rest is held alongside it for consistency.
 *
 * The phone number is the one exception and stays in its two views, because deciding whether a number
 * is real is a job for `PhoneNumbers` at save time rather than on every keystroke — the same
 * arrangement the full place form uses.
 *
 * @property name What the place will be called. Suggested from the map when the pin lands on a named
 *   business — see [observeResolvedPlace] — and editable at all times.
 * @property isNameEdited True once the user has typed in the name box themselves, after which the
 *   map's suggestions stop arriving in it.
 * @property category The chosen category pill.
 * @property area The neighbourhood or address. Filled in from the map's own address lookup, and
 *   editable afterwards — see [onAreaChanged] for how the two are kept from fighting.
 * @property isAreaEdited True once the user has typed in the address box themselves, after which the
 *   lookup stops writing into it.
 * @property note The user's own note, or blank.
 * @property openingMinutes Chosen opening time, minutes since midnight, or null if not set.
 * @property closingMinutes Chosen closing time, minutes since midnight, or null if not set.
 * @property isSaving True while the write is in flight, so the button cannot be pressed twice.
 */
data class AddPlaceFormState(
    val name: String = "",
    val isNameEdited: Boolean = false,
    val category: PlaceCategory = PlaceCategory.VET,
    val area: String = "",
    val isAreaEdited: Boolean = false,
    val note: String = "",
    val openingMinutes: Int? = null,
    val closingMinutes: Int? = null,
    val isSaving: Boolean = false,
    @param:StringRes val hoursError: Int? = null,
    @param:StringRes val phoneError: Int? = null
)

/**
 * Everything the Add a place screen shows: the form, and the location picked on the map above it.
 *
 * **Why the two halves are combined here rather than collected separately by the fragment.** Whether
 * the place can be saved depends on both of them — a name *and* a location — and that decision belongs
 * on this side of the boundary. A fragment that worked it out for itself would be holding a rule about
 * what makes a valid place, which is the ViewModel's job, and the full place form would then need its
 * own copy of the same rule.
 */
data class AddPlaceUiState(
    val form: AddPlaceFormState = AddPlaceFormState(),
    val location: LocationPickerState = LocationPickerState()
) {

    /**
     * Whether "SAVE PLACE" can be pressed.
     *
     * Both conditions, not either: a place needs a name its owner will recognise in the list, and it
     * needs coordinates, because the whole Places tab is built on them — the map centres on them and
     * Directions navigates to them. There is no third way to produce a position any more; the demo
     * fallback is gone, because an invented geotag is worse than an unsaved place, given that it looks
     * exactly like a real one.
     */
    val canSave: Boolean
        get() = form.name.isNotBlank() && location.selected != null && !form.isSaving
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
 * In MVVM this is the **ViewModel**. It holds the form's values and writes the finished place through
 * [PlaceRepository]; everything to do with *where* the place is belongs to [picker], which it owns and
 * exposes. It holds no Context, no View and no DAO; the fragment supplies the permission prompt and the
 * time pickers, formats the text and draws the result.
 *
 * ### Where a place's position comes from
 *
 * Two ways, either of which may be used at any moment, and the later one always wins — see
 * [PlaceLocationPicker], which is where that rule lives and which the full place form shares. This
 * class adds nothing to it: it simply passes the chosen coordinates through to the saved row.
 *
 * ### Why the name and address boxes fill themselves in
 *
 * The map already looks up what is under its pin, to put a line above the coordinates. Having found
 * it, leaving the user to retype it into the form would be asking them for something the screen
 * already knows. So both halves of that answer are copied across — until the moment the user edits
 * the box in question, after which it is theirs and the lookup stops touching it.
 *
 * The two halves go to different boxes, which is the part worth being careful about: a matched
 * business name ("Patan Hospital") is a candidate for the **place name**, while the address under it
 * ("Lagankhel, Lalitpur") belongs in the **area** box. An area name is never offered as a place name —
 * see [SelectedLocation.suggestedName] — because "Lalitpur" is a city, not the name of a vet.
 *
 * @param ownerId The signed-in account the new place is saved under.
 */
class AddPlaceViewModel(
    private val placeRepository: PlaceRepository,
    locationRepository: LocationRepository,
    geocodingRepository: GeocodingRepository,
    private val ownerId: Long
) : ViewModel() {

    /**
     * The map, the chosen point and its address — the half of this screen that the full place form
     * needs too, so it is written once and owned rather than reimplemented. Exposed for the fragment
     * to forward gestures to and for [uiState] to fold into the screen's state.
     */
    val picker = PlaceLocationPicker(
        locationRepository = locationRepository,
        geocodingRepository = geocodingRepository,
        scope = viewModelScope
    )

    private val _formState = MutableStateFlow(AddPlaceFormState())

    /**
     * The whole screen, as one value.
     *
     * `Eagerly` rather than `WhileSubscribed`: the picker starts asking the device where it is the
     * moment the screen opens, and a state flow that only began combining once something subscribed
     * could miss the first answer.
     */
    val uiState: StateFlow<AddPlaceUiState> =
        combine(_formState, picker.state) { form, location -> AddPlaceUiState(form, location) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, AddPlaceUiState())

    private val _events = Channel<AddPlaceEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        // Opens the map, and takes a fix if — and only if — access is already held. See
        // PlaceLocationPicker.start for why no permission is requested from here.
        picker.start(savedAt = null)
        observeResolvedPlace()
    }

    /**
     * Copies what the map found into the two boxes it can speak for, while they are still the
     * screen's to write in.
     *
     * Collected rather than pushed from the picker, because the picker must not know that a form
     * exists above it — it is shared with a screen whose fields are different views entirely.
     * Listening here keeps the dependency pointing one way.
     *
     * **Each box is filled independently of the other**, so a user who names the place themselves
     * still gets the address filled in, and a user who corrects the address still gets the business
     * name offered. Writing both from one `if` would have tied the two together for no reason.
     *
     * The equality checks are not an optimisation: this state is re-emitted on every keystroke, and
     * writing a value that is already there would mark the box as edited by the user — see
     * [onNameChanged].
     */
    private fun observeResolvedPlace() {
        viewModelScope.launch {
            picker.state.collect { location ->
                val selected = location.selected ?: return@collect
                var form = _formState.value

                // Only ever a POI name, and only while the box is untouched — in which case whatever
                // is in it was written by this method, so it tracks the pin in both directions. That
                // includes *emptying* as the pin leaves the hospital's grounds, which is the point:
                // the alternative leaves "Patan Hospital" sitting in the name box over a card reading
                // "Dropped pin", and a save then files a field under the hospital's name. Nothing a
                // person typed can be lost this way, since a single keystroke ends the arrangement.
                val suggestedName = selected.suggestedName.orEmpty()
                if (!form.isNameEdited && form.name != suggestedName) {
                    form = form.copy(name = suggestedName)
                }

                val area = selected.areaText
                if (area != null && !form.isAreaEdited && form.area != area) {
                    form = form.copy(area = area)
                }

                if (form != _formState.value) _formState.value = form
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // The form
    // ---------------------------------------------------------------------------------------

    /**
     * The name box changed.
     *
     * **The equality guard is what tells a person from the screen**, exactly as on [onAreaChanged]:
     * the fragment writes the state back into the box whenever the two differ, and that write returns
     * here as if it were an edit. A value identical to the one already held cannot have come from a
     * keystroke, so it is ignored; anything else did, and the box is the user's from then on — the map
     * never overwrites a name a person has typed, however good a POI match it finds afterwards.
     */
    fun onNameChanged(name: String) {
        val form = _formState.value
        if (form.name == name) return
        _formState.value = form.copy(name = name, isNameEdited = true)
    }

    fun onCategorySelected(category: PlaceCategory) {
        _formState.value = _formState.value.copy(category = category)
    }

    /**
     * The address box changed.
     *
     * **The equality guard is what tells a person from the screen.** The fragment writes the state back
     * into the box whenever the two differ, and that write comes back through here as an edit — so
     * without the guard, the lookup filling the box in would immediately mark it "edited by the user"
     * and never fill it in again. A value identical to the one already held cannot have come from a
     * keystroke, so it is ignored; anything else did, and the box becomes the user's from then on.
     */
    fun onAreaChanged(area: String) {
        val form = _formState.value
        if (form.area == area) return
        _formState.value = form.copy(area = area, isAreaEdited = true)
    }

    fun onNoteChanged(note: String) {
        if (_formState.value.note == note) return
        _formState.value = _formState.value.copy(note = note)
    }

    fun onOpeningTimeSelected(hour: Int, minute: Int) {
        _formState.value =
            _formState.value.copy(openingMinutes = hour * 60 + minute, hoursError = null)
    }

    fun onClosingTimeSelected(hour: Int, minute: Int) {
        _formState.value =
            _formState.value.copy(closingMinutes = hour * 60 + minute, hoursError = null)
    }

    /** "Clear hours" — for a park or anywhere without opening times. */
    fun clearHours() {
        _formState.value =
            _formState.value.copy(openingMinutes = null, closingMinutes = null, hoursError = null)
    }

    /** Called as the number or its country is edited, so a corrected field stops complaining. */
    fun clearPhoneError() {
        if (_formState.value.phoneError == null) return
        _formState.value = _formState.value.copy(phoneError = null)
    }

    // ---------------------------------------------------------------------------------------
    // Saving
    // ---------------------------------------------------------------------------------------

    /**
     * Validates and writes the place, then reports its new id.
     *
     * **The coordinates are whatever the picker holds at this instant**, which is the latest point
     * chosen by either route — the device's position if that was the last thing asked for, the map's
     * centre if the user has moved it since. There is exactly one place that question is answered, so
     * the geotag written here is always the point the card above the button was showing.
     *
     * The two validations are the full place form's, unchanged, because they are the same fields: a
     * phone number that is given has to be a real one for its country, since the saved place's row
     * offers to dial it; and opening hours are both or neither, because half a pair cannot answer "is
     * it open?".
     *
     * Once the row is in, the Places tab redraws by itself — it observes Room — so the saved place
     * appears in the list and under the category filters with nothing else to do. The event carries the
     * id only so that tab can *select* the new place, which is what re-centres its map.
     *
     * @param phoneRegion The country chosen beside the number box, and [phone] the local number typed
     *   next to it. Passed in rather than held in state for the reason given on [AddPlaceFormState].
     * @param defaultNote What to store when the note box was left empty — a string, so it is resolved
     *   by the fragment: a ViewModel holds no Context to look one up with.
     */
    fun save(phoneRegion: String, phone: String, defaultNote: String) {
        val state = uiState.value
        if (!state.canSave) return

        val selected = state.location.selected ?: return
        val form = state.form
        val trimmedPhone = phone.trim()

        val hoursError = if ((form.openingMinutes == null) != (form.closingMinutes == null)) {
            R.string.error_place_hours_incomplete
        } else {
            null
        }
        val phoneCheck = PhoneNumbers.check(phoneRegion, trimmedPhone)
        // requireNumber = false: an empty box is fine here — plenty of parks have no number.
        val phoneError = phoneCheck.messageRes(requireNumber = false)

        if (hoursError != null || phoneError != null) {
            _formState.value = form.copy(hoursError = hoursError, phoneError = phoneError)
            return
        }

        val trimmedName = form.name.trim()

        viewModelScope.launch {
            _formState.value = form.copy(isSaving = true)

            val placeId = placeRepository.addPlace(
                Place(
                    ownerId = ownerId,
                    name = trimmedName,
                    category = form.category,
                    // The typed address if there is one, otherwise whatever the map resolved — its
                    // *address*, not its headline, so a place saved on top of a hospital does not end
                    // up with "Patan Hospital" as both its name and its area. Null rather than a
                    // placeholder when neither exists: the Places tab already draws a place with no
                    // area, and inventing text for it would put a guess in the database.
                    area = form.area.trimToNull() ?: selected.areaText?.trimToNull(),
                    latitude = selected.coordinates.latitude,
                    longitude = selected.coordinates.longitude,
                    // Stored in international form, so a saved place's number dials the same whether
                    // the user is standing in Kathmandu or abroad.
                    phone = if (phoneCheck == PhoneCheck.EMPTY) {
                        null
                    } else {
                        PhoneNumbers.toE164(phoneRegion, trimmedPhone) ?: trimmedPhone
                    },
                    openingMinutes = form.openingMinutes,
                    closingMinutes = form.closingMinutes,
                    notes = form.note.trimToNull() ?: defaultNote
                )
            )

            _events.send(AddPlaceEvent.Saved(placeId = placeId, name = trimmedName))
        }
    }

    /** Blank input and "not provided" mean the same thing, so both are stored as null. */
    private fun String.trimToNull(): String? = trim().takeIf { it.isNotEmpty() }

    companion object {

        fun factory(ownerId: Long): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application =
                    this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as PetCareApplication
                AddPlaceViewModel(
                    placeRepository = application.placeRepository,
                    locationRepository = application.locationRepository,
                    geocodingRepository = application.geocodingRepository,
                    ownerId = ownerId
                )
            }
        }
    }
}
