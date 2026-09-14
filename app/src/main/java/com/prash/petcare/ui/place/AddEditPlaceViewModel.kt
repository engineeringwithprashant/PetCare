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
import com.prash.petcare.data.repository.PlaceRepository
import com.prash.petcare.util.Coordinates
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
 * @property coordinates The captured GPS geotag, or null until one has been taken.
 * @property isLocating True while waiting for a GPS fix, so the button can show progress and not be
 *   pressed twice.
 * @property isApproximate True when only approximate location was granted, so the form can say the
 *   geotag may be a few hundred metres out.
 */
data class PlaceFormState(
    val isEditMode: Boolean = false,
    val category: PlaceCategory = PlaceCategory.VET,
    val openingMinutes: Int? = null,
    val closingMinutes: Int? = null,
    val coordinates: Coordinates? = null,
    val isLocating: Boolean = false,
    val isApproximate: Boolean = false,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    @param:StringRes val nameError: Int? = null,
    @param:StringRes val hoursError: Int? = null,
    @param:StringRes val locationError: Int? = null
)

/** A one-off occurrence the place form reacts to exactly once. */
sealed interface PlaceFormEvent {

    /** A saved place was loaded; fill the text fields once. See CareTaskEvent.PrefillForm for why. */
    data class PrefillForm(val place: Place) : PlaceFormEvent

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
 * **The geotag is captured, never typed.** The spec for this feature is that every saved place keeps
 * the GPS position of where it really is, so there are no latitude/longitude fields: the user stands
 * at (or near) the place and presses "Capture location". That also sidesteps a whole class of input
 * errors — a transposed digit in a typed longitude moves a clinic hundreds of kilometres.
 *
 * @param ownerId The signed-in account a new place is saved under.
 * @param placeId Which place to edit, or [NO_PLACE_ID] to create one.
 */
class AddEditPlaceViewModel(
    private val placeRepository: PlaceRepository,
    private val locationRepository: LocationRepository,
    private val ownerId: Long,
    private val placeId: Long
) : ViewModel() {

    private val isEditMode = placeId != NO_PLACE_ID

    /** The place as loaded, kept so a save preserves what the form does not show (pinned, createdAt). */
    private var loadedPlace: Place? = null

    private val _formState = MutableStateFlow(
        PlaceFormState(isEditMode = isEditMode, isLoading = isEditMode)
    )
    val formState: StateFlow<PlaceFormState> = _formState.asStateFlow()

    private val _events = Channel<PlaceFormEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        if (isEditMode) load()
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
                closingMinutes = place.closingMinutes,
                coordinates = Coordinates(place.latitude, place.longitude)
            )
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

    /**
     * Takes a GPS fix and stores it as this place's geotag.
     *
     * Called by the fragment only once location permission is held. High accuracy is asked for when
     * precise location was granted, and a cached fix is refused: a geotag is saved for good, and "where
     * the phone was twenty minutes ago" is not where the clinic is.
     *
     * A failed fix leaves any previous geotag in place and explains itself rather than clearing the
     * field — pressing "Recapture" indoors should not throw away a good position taken outside.
     */
    fun captureLocation() {
        if (_formState.value.isLocating) return

        val precise = locationRepository.hasPreciseLocationPermission()
        _formState.value = _formState.value.copy(isLocating = true, locationError = null)

        viewModelScope.launch {
            val fix = locationRepository.getCurrentLocation(highAccuracy = precise, allowCached = false)

            _formState.value = if (fix != null) {
                _formState.value.copy(isLocating = false, coordinates = fix, isApproximate = !precise)
            } else {
                _formState.value.copy(isLocating = false)
            }

            if (fix == null) _events.send(PlaceFormEvent.Message(R.string.place_location_failed))
        }
    }

    /** Validates and writes the place. */
    fun save(name: String, area: String, phone: String, notes: String) {
        val state = _formState.value
        val trimmedName = name.trim()

        val nameError = if (trimmedName.isEmpty()) R.string.error_place_name_required else null
        // Half a set of hours cannot answer "is it open?", so both or neither.
        val hoursError = if ((state.openingMinutes == null) != (state.closingMinutes == null)) {
            R.string.error_place_hours_incomplete
        } else {
            null
        }
        val locationError = if (state.coordinates == null) R.string.error_place_location_required else null

        if (nameError != null || hoursError != null || locationError != null) {
            _formState.value = state.copy(
                nameError = nameError,
                hoursError = hoursError,
                locationError = locationError
            )
            return
        }

        val coordinates = state.coordinates ?: return

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
                phone = phone.trimToNull(),
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
                    ownerId = ownerId,
                    placeId = placeId
                )
            }
        }
    }
}
