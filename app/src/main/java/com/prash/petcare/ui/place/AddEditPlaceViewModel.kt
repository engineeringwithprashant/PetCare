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
import com.prash.petcare.util.Coordinates
import com.prash.petcare.util.PhoneCheck
import com.prash.petcare.util.PhoneNumbers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * The parts of the place form the ViewModel owns.
 *
 * As on the other forms, what the user types stays in the EditTexts, which restore themselves after a
 * rotation. Held here is everything a view cannot restore on its own: the values chosen from pickers,
 * the captured geotag, validation errors and progress.
 *
 * @property isEditMode True when changing a saved place; controls the title and the delete button.
 * @property category The chosen category.
 * @property openingMinutes Chosen opening time, minutes since midnight, or null if not set.
 * @property closingMinutes Chosen closing time, minutes since midnight, or null if not set.
 *
 * The place's position is **not** here any more. It lives in [AddEditPlaceViewModel.picker], which is
 * shared with the Add a place screen so that the two forms choose a location in exactly the same way —
 * see [PlaceLocationPicker]. Keeping a second copy of the coordinates here is precisely the drift that
 * change was made to remove.
 */
data class PlaceFormState(
    val isEditMode: Boolean = false,
    val category: PlaceCategory = PlaceCategory.VET,
    val openingMinutes: Int? = null,
    val closingMinutes: Int? = null,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    @param:StringRes val nameError: Int? = null,
    @param:StringRes val hoursError: Int? = null,
    @param:StringRes val locationError: Int? = null,
    @param:StringRes val phoneError: Int? = null
)

/** A one-off occurrence the place form reacts to exactly once. */
sealed interface PlaceFormEvent {

    /** A saved place was loaded; fill the text fields once. See CareTaskEvent.PrefillForm for why. */
    data class PrefillForm(val place: Place) : PlaceFormEvent

    /**
     * The map worked out what the pin is standing on, and these are the two strings the form could
     * use for it — a business name for the name box, an address for the area box. Either may be null:
     * because the lookup found only the other, or because the pin has moved off the place it was on
     * and there is no longer a name to offer. The second case is sent rather than swallowed, so a
     * suggestion the form accepted earlier can be taken back once it stops being true.
     *
     * **An offer, not an instruction**, and sent as an event rather than held in state for that
     * reason: the fields on this form keep their own text (they restore themselves after a rotation,
     * which is why this screen has no `name` in its state at all), so a *state* carrying a suggested
     * name would have to be reconciled against whatever the box already held on every emission. As a
     * one-off occurrence it is simply ignored by a box that is already filled in — see
     * AddEditPlaceFragment.applySuggestions, which is where that rule is applied and why it can never
     * overwrite a saved place's own name.
     */
    data class SuggestPlaceDetails(val name: String?, val area: String?) : PlaceFormEvent

    /** The place was created or updated; close the form. */
    data object Saved : PlaceFormEvent

    /** The place was deleted; close the form. */
    data object Deleted : PlaceFormEvent

    /** Something failed that is not tied to one field — shown as a Snackbar. */
    data class Message(@param:StringRes val messageRes: Int) : PlaceFormEvent
}

/**
 * Backs the add/edit place form.
 *
 * In MVVM this is the **ViewModel**: it validates, captures the geotag through [LocationRepository]
 * and writes through [PlaceRepository]. The fragment handles what needs an Activity — the permission
 * prompt and the time pickers — and forwards the results here.
 *
 * **The geotag is chosen on a map, never typed.** There are no latitude/longitude fields: the user
 * either presses "I'm here" or moves the map until the place sits under its centre pin. That sidesteps
 * a whole class of input errors — a transposed digit in a typed longitude moves a clinic hundreds of
 * kilometres — and it is the same picker the Add a place screen uses, so correcting a saved place's
 * position feels identical to setting it in the first place.
 *
 * **Opening an existing place puts the map on it.** [load] hands the stored coordinates to the picker,
 * which centres the map there with the pin on the point, so "keep this location" costs no taps at all
 * and changing it is one drag.
 *
 * **The map offers names, and only ever offers them.** When the pin lands on a named business the
 * lookup can say so — "Patan Hospital" rather than "Lalitpur" — and that is passed to the form as
 * [PlaceFormEvent.SuggestPlaceDetails] so a blank name box can fill itself in. An existing place's own
 * name is never touched by it, which matters more here than on the Add screen: the user came to this
 * form to change something about a place they have already named, and finding that name replaced by
 * whatever the map thinks of the coordinates would be the screen overruling them.
 *
 * @param ownerId The signed-in account a new place is saved under.
 * @param placeId Which place to edit, or [NO_PLACE_ID] to create one.
 */
class AddEditPlaceViewModel(
    private val placeRepository: PlaceRepository,
    locationRepository: LocationRepository,
    geocodingRepository: GeocodingRepository,
    private val ownerId: Long,
    private val placeId: Long
) : ViewModel() {

    private val isEditMode = placeId != NO_PLACE_ID

    /**
     * The map, the chosen point and its address.
     *
     * The same class the Add a place screen owns, which is what requirement 15 asks for: one location
     * picker behind both forms rather than two implementations that start out alike. Exposed for the
     * fragment to draw and to forward the map's gestures to.
     */
    val picker = PlaceLocationPicker(
        locationRepository = locationRepository,
        geocodingRepository = geocodingRepository,
        scope = viewModelScope
    )

    /** The place as loaded, kept so a save preserves what the form does not show (pinned, createdAt). */
    private var loadedPlace: Place? = null

    private val _formState = MutableStateFlow(
        PlaceFormState(isEditMode = isEditMode, isLoading = isEditMode)
    )
    val formState: StateFlow<PlaceFormState> = _formState.asStateFlow()

    private val _events = Channel<PlaceFormEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    /**
     * The last pair offered through [PlaceFormEvent.SuggestPlaceDetails].
     *
     * Kept so the same suggestion is not sent twice. The picker's state is re-emitted for reasons that
     * have nothing to do with the address — the map type changing, a fix arriving — and each emission
     * would otherwise be another event queued behind the last.
     */
    private var lastSuggestion: Pair<String?, String?>? = null

    init {
        if (isEditMode) {
            // The map waits for the row: its opening position is the place's own, and putting it
            // anywhere else first would show the user a location that is not theirs.
            load()
        } else {
            picker.start(savedAt = null)
        }
        observeResolvedPlace()
    }

    /**
     * Offers the form what the map found under its pin.
     *
     * Collected here rather than pushed from the picker, for the reason given on
     * [AddPlaceViewModel.observeResolvedPlace]: the picker is shared between two screens and must not
     * know that either of them has a form above it.
     *
     * Nothing is decided about the *fields* here — this only forwards. Whether a box is empty enough
     * to accept a suggestion is a question only the fragment can answer on this screen, because this
     * form keeps its typed text in its views rather than in state.
     */
    private fun observeResolvedPlace() {
        viewModelScope.launch {
            picker.state.collect { location ->
                val selected = location.selected ?: return@collect
                val suggestion = selected.suggestedName to selected.areaText

                if (suggestion == lastSuggestion) return@collect
                // "Nothing to offer" is only worth sending once something *was* offered, so opening
                // the screen over an unnamed point does not queue an event saying so.
                val hasNothingToSay =
                    lastSuggestion == null && suggestion.first == null && suggestion.second == null
                if (hasNothingToSay) return@collect

                lastSuggestion = suggestion
                _events.send(
                    PlaceFormEvent.SuggestPlaceDetails(
                        name = suggestion.first,
                        area = suggestion.second
                    )
                )
            }
        }
    }

    private fun load() {
        viewModelScope.launch {
            val place = placeRepository.getPlace(placeId)
            if (place == null) {
                _formState.value = _formState.value.copy(isLoading = false)
                _events.send(PlaceFormEvent.Message(R.string.error_place_not_found))
                return@launch
            }

            loadedPlace = place
            _formState.value = _formState.value.copy(
                isLoading = false,
                category = place.category,
                openingMinutes = place.openingMinutes,
                closingMinutes = place.closingMinutes
            )
            // Centres the map on exactly the coordinates the place was saved at, pin on the point —
            // requirement 17. Nothing is changed by opening the form: the selection starts as what is
            // already stored, and only a drag or "I'm here" replaces it.
            picker.start(savedAt = Coordinates(place.latitude, place.longitude))
            _events.send(PlaceFormEvent.PrefillForm(place))
        }
    }

    fun onCategorySelected(category: PlaceCategory) {
        _formState.value = _formState.value.copy(category = category)
    }

    fun onOpeningTimeSelected(hour: Int, minute: Int) {
        _formState.value = _formState.value.copy(openingMinutes = hour * 60 + minute, hoursError = null)
    }

    fun onClosingTimeSelected(hour: Int, minute: Int) {
        _formState.value = _formState.value.copy(closingMinutes = hour * 60 + minute, hoursError = null)
    }

    /** "Clear hours" — for a park or anywhere without opening times. */
    fun clearHours() {
        _formState.value = _formState.value.copy(openingMinutes = null, closingMinutes = null, hoursError = null)
    }

    fun clearNameError() {
        _formState.value = _formState.value.copy(nameError = null)
    }

    /** Called as the number or its country is edited, so a corrected field stops complaining. */
    fun clearPhoneError() {
        _formState.value = _formState.value.copy(phoneError = null)
    }

    /** Called as the map is moved, so a form that complained about a missing location stops. */
    fun clearLocationError() {
        if (_formState.value.locationError == null) return
        _formState.value = _formState.value.copy(locationError = null)
    }

    /**
     * Validates and writes the place.
     *
     * @param phoneRegion The country chosen beside the number box, and [phone] the local number typed
     *   next to it. The number is optional — many parks have none — but a number that *is* given has
     *   to be a real one for that country, because the saved-place row offers to dial it. A number
     *   stored in a shape nothing can dial is a button that fails at the moment somebody needs a vet.
     */
    fun save(name: String, area: String, phoneRegion: String, phone: String, notes: String) {
        val state = _formState.value
        val trimmedName = name.trim()
        val trimmedPhone = phone.trim()

        val nameError = if (trimmedName.isEmpty()) R.string.error_place_name_required else null
        // Half a set of hours cannot answer "is it open?", so both or neither.
        val hoursError = if ((state.openingMinutes == null) != (state.closingMinutes == null)) {
            R.string.error_place_hours_incomplete
        } else {
            null
        }
        // The position comes from the picker rather than from this state — one answer to "where is
        // this place?", held in one object, so the row written below cannot disagree with the map the
        // user was just looking at.
        val selected = picker.state.value.selected
        val locationError = if (selected == null) R.string.error_place_location_required else null
        val phoneCheck = PhoneNumbers.check(phoneRegion, trimmedPhone)
        // requireNumber = false: an empty box is fine here, unlike on the register form.
        val phoneError = phoneCheck.messageRes(requireNumber = false)

        if (nameError != null || hoursError != null || locationError != null || phoneError != null) {
            _formState.value = state.copy(
                nameError = nameError,
                hoursError = hoursError,
                locationError = locationError,
                phoneError = phoneError
            )
            return
        }

        val coordinates = selected?.coordinates ?: return

        viewModelScope.launch {
            _formState.value = state.copy(isSaving = true)

            val existing = loadedPlace
            // `copy` on the loaded place keeps id, owner, pinned state and createdAt as they were.
            val place = (existing ?: Place(
                ownerId = ownerId,
                name = trimmedName,
                category = state.category,
                latitude = coordinates.latitude,
                longitude = coordinates.longitude
            )).copy(
                name = trimmedName,
                category = state.category,
                area = area.trimToNull(),
                latitude = coordinates.latitude,
                longitude = coordinates.longitude,
                // Stored in international form, so a saved place's number dials the same whether the
                // user is standing in Kathmandu or abroad. An empty box stays null, as before.
                phone = if (phoneCheck == PhoneCheck.EMPTY) {
                    null
                } else {
                    PhoneNumbers.toE164(phoneRegion, trimmedPhone) ?: trimmedPhone
                },
                openingMinutes = state.openingMinutes,
                closingMinutes = state.closingMinutes,
                notes = notes.trimToNull()
            )

            if (existing == null) placeRepository.addPlace(place) else placeRepository.updatePlace(place)

            _events.send(PlaceFormEvent.Saved)
        }
    }

    /** Deletes the place being edited. The confirmation dialog has already been answered. */
    fun delete() {
        val place = loadedPlace ?: return
        viewModelScope.launch {
            _formState.value = _formState.value.copy(isSaving = true)
            placeRepository.deletePlace(place)
            _events.send(PlaceFormEvent.Deleted)
        }
    }

    /** Blank input and "not provided" mean the same thing, so both are stored as null. */
    private fun String.trimToNull(): String? = trim().takeIf { it.isNotEmpty() }

    companion object {

        /** [placeId] meaning "create a new place". Matches the default in nav_graph.xml. */
        const val NO_PLACE_ID = -1L

        fun factory(ownerId: Long, placeId: Long): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application =
                    this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as PetCareApplication
                AddEditPlaceViewModel(
                    placeRepository = application.placeRepository,
                    locationRepository = application.locationRepository,
                    geocodingRepository = application.geocodingRepository,
                    ownerId = ownerId,
                    placeId = placeId
                )
            }
        }
    }
}
