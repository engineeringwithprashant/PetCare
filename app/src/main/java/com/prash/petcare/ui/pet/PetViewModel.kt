package com.prash.petcare.ui.pet

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.prash.petcare.PetCareApplication
import com.prash.petcare.R
import com.prash.petcare.data.local.entity.Pet
import com.prash.petcare.data.local.entity.PetSpecies
import com.prash.petcare.data.repository.PetRepository
import com.prash.petcare.util.PetPhotoStorage
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * The parts of the pet form the ViewModel owns.
 *
 * Note what is *not* here: the name, breed, weight and notes the user types. Those live in the
 * EditTexts themselves, which Android saves and restores across rotation automatically. Mirroring
 * them into state as well would mean writing back into the fields on every update and fighting the
 * user's cursor.
 *
 * What is here is everything the text fields cannot hold on their own: values chosen through a
 * dialog, and the errors and progress the screen has to display.
 *
 * @property isEditMode True when changing an existing pet, false when creating one. Controls the
 *   screen title and whether the delete button appears.
 * @property isLoading True while an existing pet is being fetched.
 * @property isSaving True while a save or delete is in flight, so buttons can be disabled.
 * @property species Currently selected species; the dropdown reflects this.
 * @property dateOfBirth Chosen birth date as epoch milliseconds, or null if not set.
 * @property photoPath Path to the photo in internal storage, or null if there is none.
 */
data class PetFormState(
    val isEditMode: Boolean = false,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val species: PetSpecies = PetSpecies.DOG,
    val dateOfBirth: Long? = null,
    val photoPath: String? = null,
    @param:StringRes val nameError: Int? = null,
    @param:StringRes val weightError: Int? = null
)

/**
 * A one-off occurrence the fragment reacts to exactly once.
 *
 * Kept separate from [PetFormState] because these are moments, not conditions: navigating back
 * after a save should happen once, whereas state is re-applied after every rotation.
 */
sealed interface PetEvent {

    /**
     * Carries a loaded pet so the fragment can fill in the text fields.
     *
     * Sent as an event rather than exposed as state precisely because it must happen once. If the
     * fragment wrote these values on every state emission it would overwrite whatever the user was
     * part-way through typing.
     */
    data class PrefillForm(val pet: Pet) : PetEvent

    /** The pet was created or updated; the screen should close. */
    data object Saved : PetEvent

    /** The pet was deleted; the screen should close. */
    data object Deleted : PetEvent

    /** Something failed in a way not tied to a single field. */
    data class Error(@param:StringRes val messageRes: Int) : PetEvent
}

/**
 * Backs the add/edit pet screen.
 *
 * One ViewModel serves both modes, distinguished by [petId]: a value of [NO_PET_ID] means "create
 * a new pet", anything else means "edit that one". Splitting this into two nearly identical
 * ViewModels would duplicate all the validation and photo handling for the sake of one branch.
 *
 * It holds no views and no Context. Photo *files* are reached through [PetPhotoStorage], which
 * takes plain paths, so cleaning up an orphaned image needs no Android context here.
 *
 * @param petRepository Data access, injected so tests can supply a fake.
 * @param petId Which pet to edit, or [NO_PET_ID] to create one.
 * @param ownerId The signed-in user who will own a newly created pet.
 */
class PetViewModel(
    private val petRepository: PetRepository,
    private val petId: Long,
    private val ownerId: Long
) : ViewModel() {

    /** True when editing an existing record rather than creating one. */
    private val isEditMode: Boolean = petId != NO_PET_ID

    /**
     * The pet as it was loaded from the database, kept so a save can preserve the fields the form
     * does not show — the id, owner and creation time — and so the original photo can be found and
     * deleted if it gets replaced.
     */
    private var loadedPet: Pet? = null

    /**
     * Set once the record has been written or deleted.
     *
     * [onCleared] uses it to tell an abandoned form from a completed one, so it only deletes a
     * newly picked photo when the user backs out without saving.
     */
    private var wasCompleted: Boolean = false

    private val _formState = MutableStateFlow(
        PetFormState(isEditMode = isEditMode, isLoading = isEditMode)
    )
    val formState: StateFlow<PetFormState> = _formState.asStateFlow()

    private val _events = Channel<PetEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        // Only edit mode has anything to fetch; a new pet starts from the defaults above.
        if (isEditMode) {
            loadExistingPet()
        }
    }

    /** Fetches the pet being edited and pushes its values into the form. */
    private fun loadExistingPet() {
        viewModelScope.launch {
            val pet = petRepository.getPet(petId)

            if (pet == null) {
                // The pet was deleted on another screen while this one was opening.
                _formState.value = _formState.value.copy(isLoading = false)
                _events.send(PetEvent.Error(R.string.error_pet_not_found))
                return@launch
            }

            loadedPet = pet

            // Dialog-chosen values become state, because the views holding them cannot restore
            // themselves the way an EditText can.
            _formState.value = _formState.value.copy(
                isLoading = false,
                species = pet.species,
                dateOfBirth = pet.dateOfBirth,
                photoPath = pet.photoPath
            )

            // The typed fields are filled once, via an event.
            _events.send(PetEvent.PrefillForm(pet))
        }
    }

    /** Records the species chosen in the dropdown. */
    fun onSpeciesSelected(species: PetSpecies) {
        _formState.value = _formState.value.copy(species = species)
    }

    /** Records the date chosen in the date picker. */
    fun onDateOfBirthSelected(dateMillis: Long) {
        _formState.value = _formState.value.copy(dateOfBirth = dateMillis)
    }

    /**
     * Records a newly saved photo.
     *
     * If the user already picked a photo during this visit and then picked another, the first file
     * is now referenced by nothing, so it is deleted here. The pet's *original* photo is left
     * alone — it stays on disk until the save actually succeeds, so backing out of the form
     * cannot destroy the picture that is still in the database.
     */
    fun onPhotoSelected(newPath: String) {
        val current = _formState.value.photoPath
        if (current != null && current != loadedPet?.photoPath) {
            PetPhotoStorage.delete(current)
        }
        _formState.value = _formState.value.copy(photoPath = newPath)
    }

    /** Reports that the picked image could not be copied into internal storage. */
    fun onPhotoFailed() {
        viewModelScope.launch {
            _events.send(PetEvent.Error(R.string.error_photo_save_failed))
        }
    }

    /**
     * Validates the form and writes the pet.
     *
     * The typed values arrive as parameters because the fragment owns them; everything else comes
     * from [formState].
     */
    fun save(
        name: String,
        breed: String,
        weightText: String,
        dietaryNotes: String,
        allergies: String,
        favouriteToys: String
    ) {
        val trimmedName = name.trim()
        val trimmedWeight = weightText.trim()

        // An empty weight is allowed — not every owner knows it. A non-empty one has to be a
        // positive number.
        val weight = trimmedWeight.toDoubleOrNull()
        val weightError = when {
            trimmedWeight.isEmpty() -> null
            weight == null || weight <= 0.0 -> R.string.error_pet_weight_invalid
            else -> null
        }

        val nameError = if (trimmedName.isEmpty()) R.string.error_pet_name_required else null

        if (nameError != null || weightError != null) {
            _formState.value = _formState.value.copy(
                nameError = nameError,
                weightError = weightError
            )
            return
        }

        viewModelScope.launch {
            _formState.value = _formState.value.copy(
                isSaving = true,
                nameError = null,
                weightError = null
            )

            val state = _formState.value
            val existing = loadedPet

            // `copy` on the loaded pet keeps id, ownerId and createdAt untouched; building a fresh
            // Pet would reset them and orphan the record's history.
            val pet = existing?.copy(
                name = trimmedName,
                species = state.species,
                breed = breed.trimToNull(),
                dateOfBirth = state.dateOfBirth,
                weightKg = if (trimmedWeight.isEmpty()) null else weight,
                photoPath = state.photoPath,
                dietaryNotes = dietaryNotes.trimToNull(),
                allergies = allergies.trimToNull(),
                favouriteToys = favouriteToys.trimToNull()
            ) ?: Pet(
                ownerId = ownerId,
                name = trimmedName,
                species = state.species,
                breed = breed.trimToNull(),
                dateOfBirth = state.dateOfBirth,
                weightKg = if (trimmedWeight.isEmpty()) null else weight,
                photoPath = state.photoPath,
                dietaryNotes = dietaryNotes.trimToNull(),
                allergies = allergies.trimToNull(),
                favouriteToys = favouriteToys.trimToNull()
            )

            if (existing == null) {
                petRepository.addPet(pet)
            } else {
                petRepository.updatePet(pet)
            }

            // Only now that the new path is safely in the database is the replaced image removed.
            val replacedPhoto = existing?.photoPath
            if (replacedPhoto != null && replacedPhoto != state.photoPath) {
                PetPhotoStorage.delete(replacedPhoto)
            }

            wasCompleted = true
            _events.send(PetEvent.Saved)
        }
    }

    /**
     * Deletes the pet being edited.
     *
     * The confirmation dialog is the fragment's responsibility; by the time this is called the
     * user has already agreed. Removing the pet also removes its care tasks, routines and expenses
     * through the CASCADE rules on those tables.
     */
    fun delete() {
        val pet = loadedPet ?: return

        viewModelScope.launch {
            _formState.value = _formState.value.copy(isSaving = true)

            petRepository.deletePet(pet)

            // The row is gone, so nothing references the image any more.
            PetPhotoStorage.delete(pet.photoPath)

            wasCompleted = true
            _events.send(PetEvent.Deleted)
        }
    }

    /**
     * Called when the screen is finished with for good.
     *
     * If the user picked a photo and then left without saving, that file is now referenced by
     * nothing — internal storage would slowly fill with abandoned images without this.
     */
    override fun onCleared() {
        super.onCleared()
        if (wasCompleted) return

        val unsavedPhoto = _formState.value.photoPath
        if (unsavedPhoto != null && unsavedPhoto != loadedPet?.photoPath) {
            PetPhotoStorage.delete(unsavedPhoto)
        }
    }

    /** Blank input and "not provided" mean the same thing, so both are stored as null. */
    private fun String.trimToNull(): String? = trim().takeIf { it.isNotEmpty() }

    companion object {

        /** [petId] value meaning "create a new pet". Matches the default in the nav graph. */
        const val NO_PET_ID = -1L

        /**
         * Builds the ViewModel for a particular pet and owner.
         *
         * A function rather than a property because both ids are only known at runtime, from the
         * navigation arguments.
         */
        fun factory(petId: Long, ownerId: Long): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application =
                    this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as PetCareApplication
                PetViewModel(application.petRepository, petId, ownerId)
            }
        }
    }
}
