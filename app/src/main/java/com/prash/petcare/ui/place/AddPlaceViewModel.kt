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

/** The part of the Add a place form this ViewModel types into, as distinct from where the place is. */
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

/** Everything the Add a place screen shows: the form, and the location picked on the map above it. */
data class AddPlaceUiState(
    val form: AddPlaceFormState = AddPlaceFormState(),
    val location: LocationPickerState = LocationPickerState()
) {

    /** Whether "SAVE PLACE" can be pressed. */
    val canSave: Boolean
        get() = form.name.isNotBlank() && location.selected != null && !form.isSaving
}

/** A one-off occurrence the Add a place screen reacts to exactly once. */
sealed interface AddPlaceEvent {

    /** The place was written. Carries what the Places tab needs to select it on the map and to offer the undo. */
    data class Saved(val placeId: Long, val name: String) : AddPlaceEvent

    /** Something worth a Snackbar that is not tied to one field. */
    data class Message(@param:StringRes val messageRes: Int) : AddPlaceEvent
}

/** Backs the Add a place screen. */
class AddPlaceViewModel(
    private val placeRepository: PlaceRepository,
    locationRepository: LocationRepository,
    geocodingRepository: GeocodingRepository,
    private val ownerId: Long
) : ViewModel() {

    /** The map, the chosen point and its address — the half of this screen that the full place form needs too. */
    val picker = PlaceLocationPicker(
        locationRepository = locationRepository,
        geocodingRepository = geocodingRepository,
        scope = viewModelScope
    )

    private val _formState = MutableStateFlow(AddPlaceFormState())

    /** The whole screen, as one value. */
    val uiState: StateFlow<AddPlaceUiState> =
        combine(_formState, picker.state) { form, location -> AddPlaceUiState(form, location) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, AddPlaceUiState())

    private val _events = Channel<AddPlaceEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        // Opens the map, and takes a fix if — and only if — access is already held.
        picker.start(savedAt = null)
        observeResolvedPlace()
    }

    /** Copies what the map found into the two boxes it can speak. */
    private fun observeResolvedPlace() {
        viewModelScope.launch {
            picker.state.collect { location ->
                val selected = location.selected ?: return@collect
                var form = _formState.value

                // Only ever a POI name, and only while the box is untouched.
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

    // The form.

    /** The name box changed. */
    fun onNameChanged(name: String) {
        val form = _formState.value
        if (form.name == name) return
        _formState.value = form.copy(name = name, isNameEdited = true)
    }

    fun onCategorySelected(category: PlaceCategory) {
        _formState.value = _formState.value.copy(category = category)
    }

    /** The address box changed. */
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

    // Saving.

    /** Validates and writes the place, then reports its new id. */
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
                    // The typed address if there is one, otherwise whatever the map resolved — its address.
                    area = form.area.trimToNull() ?: selected.areaText?.trimToNull(),
                    latitude = selected.coordinates.latitude,
                    longitude = selected.coordinates.longitude,
                    // Stored in international form.
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
