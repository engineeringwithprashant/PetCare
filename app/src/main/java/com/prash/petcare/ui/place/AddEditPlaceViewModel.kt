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

/** The parts of the place form the ViewModel owns. */
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

    /** The map worked out what the pin is standing on, and these are the two strings the form could use. */
    data class SuggestPlaceDetails(val name: String?, val area: String?) : PlaceFormEvent

    /** The place was created or updated; close the form. */
    data object Saved : PlaceFormEvent

    /** The place was deleted; close the form. */
    data object Deleted : PlaceFormEvent

    /** Something failed that is not tied to one field — shown as a Snackbar. */
    data class Message(@param:StringRes val messageRes: Int) : PlaceFormEvent
}

/** Backs the add/edit place form. */
class AddEditPlaceViewModel(
    private val placeRepository: PlaceRepository,
    locationRepository: LocationRepository,
    geocodingRepository: GeocodingRepository,
    private val ownerId: Long,
    private val placeId: Long
) : ViewModel() {

    private val isEditMode = placeId != NO_PLACE_ID

    /** The map, the chosen point and its address. */
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

    /** The last pair offered through [PlaceFormEvent.SuggestPlaceDetails]. */
    private var lastSuggestion: Pair<String?, String?>? = null

    init {
        if (isEditMode) {
            // The map waits for the row: its opening position is the place's own.
            load()
        } else {
            picker.start(savedAt = null)
        }
        observeResolvedPlace()
    }

    /** Offers the form what the map found under its pin. */
    private fun observeResolvedPlace() {
        viewModelScope.launch {
            picker.state.collect { location ->
                val selected = location.selected ?: return@collect
                val suggestion = selected.suggestedName to selected.areaText

                if (suggestion == lastSuggestion) return@collect
                // "Nothing to offer" is only worth sending once something was offered.
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
            // Centres the map on exactly the coordinates the place was saved at, pin on the point — requirement 17.
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

    /** Validates and writes the place. */
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
        // The position comes from the picker rather than from this state — one answer to "where is this place?".
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
                // Stored in international form.
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
