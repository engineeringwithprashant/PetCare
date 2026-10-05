package com.prash.petcare.ui.pet

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.activity.result.PickVisualMediaRequest
import androidx.appcompat.app.AlertDialog
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.prash.petcare.R
import com.prash.petcare.data.local.entity.Pet
import com.prash.petcare.data.local.entity.PetGender
import com.prash.petcare.data.local.entity.PetSpecies
import com.prash.petcare.databinding.DialogCropPhotoBinding
import com.prash.petcare.databinding.FragmentAddEditPetBinding
import com.prash.petcare.ui.common.PhotoCropView
import com.prash.petcare.ui.common.formatStoredDate
import com.prash.petcare.ui.common.labelRes
import com.prash.petcare.ui.common.setToolbarTitle
import com.prash.petcare.util.PetPhotoStorage
import kotlinx.coroutines.launch

/** Form for creating a new pet or editing an existing one. */
class AddEditPetFragment : Fragment() {

    /** Nulled in [onDestroyView] to avoid leaking the destroyed view hierarchy. */
    private var _binding: FragmentAddEditPetBinding? = null
    private val binding get() = _binding!!

    private val petId: Long by lazy {
        requireArguments().getLong(ARG_PET_ID, PetViewModel.NO_PET_ID)
    }
    private val ownerId: Long by lazy { requireArguments().getLong(ARG_OWNER_ID) }

    private val viewModel: PetViewModel by viewModels {
        PetViewModel.factory(petId, ownerId)
    }

    /** Which photo path is currently drawn on screen. */
    private var renderedPhotoPath: String? = null

    /** Launcher for the system photo picker. */
    private val pickPhotoLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        // Null when the user backed out without choosing anything.
        if (uri != null) {
            showCropDialog(uri)
        }
    }

    /** The framing dialog, held so it can be dismissed with the view. */
    private var cropDialog: AlertDialog? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAddEditPetBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpSpeciesDropdown()
        setUpGenderToggle()
        setUpClickListeners()
        observeViewModel()
    }

    /** Fills the species dropdown. */
    private fun setUpSpeciesDropdown() {
        val labels = PetSpecies.entries.map { getString(it.labelRes()) }.toTypedArray()
        binding.speciesInput.setSimpleItems(labels)

        binding.speciesInput.setOnItemClickListener { _, _, position, _ ->
            viewModel.onSpeciesSelected(PetSpecies.entries[position])
        }
    }

    /** Forwards a tap on the Male / Female / Unknown group to the ViewModel. */
    private fun setUpGenderToggle() {
        binding.genderToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            viewModel.onGenderSelected(genderForButton(checkedId))
        }
    }

    private fun setUpClickListeners() {
        binding.choosePhotoButton.setOnClickListener {
            // ImageOnly keeps videos out of the picker, since only a still can be shown here.
            pickPhotoLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        }

        // The field is focusable so it can be reached by keyboard, D-pad or switch access.
        binding.dateOfBirthInput.showSoftInputOnFocus = false
        binding.dateOfBirthInput.setOnClickListener { showDatePicker() }

        binding.saveButton.setOnClickListener {
            // The typed values are read straight off the fields and handed.
            viewModel.save(
                name = binding.nameInput.text?.toString().orEmpty(),
                breed = binding.breedInput.text?.toString().orEmpty(),
                weightText = binding.weightInput.text?.toString().orEmpty(),
                dietaryNotes = binding.dietaryNotesInput.text?.toString().orEmpty(),
                allergies = binding.allergiesInput.text?.toString().orEmpty(),
                favouriteToys = binding.favouriteToysInput.text?.toString().orEmpty()
            )
        }

        binding.deleteButton.setOnClickListener { showDeleteConfirmation() }

        // Clear a validation message as soon as the user starts correcting the field.
        binding.nameInput.doOnTextChanged { _, _, _, _ ->
            binding.nameInputLayout.error = null
        }
        binding.weightInput.doOnTextChanged { _, _, _, _ ->
            binding.weightInputLayout.error = null
        }
    }

    /** Opens the framing dialog on the chosen image. */
    private fun showCropDialog(uri: Uri) {
        val appContext = requireContext().applicationContext

        lifecycleScope.launch {
            // Decoded before the dialog is built.
            val source = PetPhotoStorage.decodeForCrop(appContext, uri)
            if (source == null) {
                viewModel.onPhotoFailed()
                Snackbar.make(binding.root, R.string.pet_photo_crop_unreadable, Snackbar.LENGTH_LONG).show()
                return@launch
            }

            val cropBinding = DialogCropPhotoBinding.inflate(layoutInflater)
            cropBinding.photoCropView.setBitmap(source)

            cropDialog = MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.pet_photo_crop_title)
                .setView(cropBinding.root)
                .setNegativeButton(R.string.pet_photo_crop_cancel, null)
                .setPositiveButton(R.string.pet_photo_crop_confirm) { _, _ ->
                    saveCrop(cropBinding.photoCropView)
                }
                // Cancelling stores nothing at all, which is the point of decoding to memory first.
                .show()
        }
    }

    /** Writes the framed photo and hands its path to the ViewModel. */
    private fun saveCrop(cropView: PhotoCropView) {
        val appContext = requireContext().applicationContext
        val cropped = cropView.croppedBitmap(cropView.width)

        if (cropped == null) {
            viewModel.onPhotoFailed()
            return
        }

        // The fragment's own scope, not the view's: see the note on the picker result above.
        lifecycleScope.launch {
            val savedPath = PetPhotoStorage.saveBitmap(appContext, cropped)
            if (savedPath != null) {
                viewModel.onPhotoSelected(savedPath)
            } else {
                viewModel.onPhotoFailed()
            }
        }
    }

    /** Opens the Material date picker, starting from the date already chosen if there is one. */
    private fun showDatePicker() {
        val existingSelection = viewModel.formState.value.dateOfBirth

        val picker = MaterialDatePicker.Builder.datePicker()
            .setTitleText(R.string.pet_dob_picker_title)
            .setSelection(existingSelection ?: MaterialDatePicker.todayInUtcMilliseconds())
            .build()

        picker.addOnPositiveButtonClickListener { selectionMillis ->
            viewModel.onDateOfBirthSelected(selectionMillis)
        }

        picker.show(childFragmentManager, DATE_PICKER_TAG)
    }

    /** Asks before deleting. */
    private fun showDeleteConfirmation() {
        val petName = binding.nameInput.text?.toString()?.trim().orEmpty()

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete_pet_dialog_title)
            .setMessage(getString(R.string.delete_pet_dialog_message, petName))
            // Cancel is listed first and does nothing, so the safe choice is the easy one.
            .setNegativeButton(R.string.delete_pet_dialog_cancel, null)
            .setPositiveButton(R.string.delete_pet_dialog_confirm) { _, _ ->
                viewModel.delete()
            }
            .show()
    }

    /** Collects state and events for as long as the screen is visible. */
    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.formState.collect { state -> render(state) }
                }
                launch {
                    viewModel.events.collect { event -> handleEvent(event) }
                }
            }
        }
    }

    /** Applies the current state to the views. */
    private fun render(state: PetFormState) {
        // Which mode the form is in is the screen's title, so it goes in the app bar.
        setToolbarTitle(
            getString(if (state.isEditMode) R.string.edit_pet_title else R.string.add_pet_title)
        )

        // Only an existing pet can be deleted, so the button has no meaning in add mode.
        binding.deleteButton.visibility = if (state.isEditMode) View.VISIBLE else View.GONE

        binding.nameInputLayout.error = state.nameError?.let { getString(it) }
        binding.weightInputLayout.error = state.weightError?.let { getString(it) }

        // `false` stops the dropdown treating this as typed input and filtering its own list.
        binding.speciesInput.setText(getString(state.species.labelRes()), false)

        // Reflects the ViewModel's choice back onto the group.
        binding.genderToggleGroup.check(buttonForGender(state.gender))

        binding.dateOfBirthInput.setText(state.dateOfBirth?.let { formatStoredDate(it) }.orEmpty())

        binding.choosePhotoButton.setText(
            if (state.photoPath != null) R.string.pet_photo_change else R.string.pet_photo_choose
        )
        renderPhoto(state.photoPath)

        val isBusy = state.isLoading || state.isSaving
        binding.progressIndicator.visibility = if (isBusy) View.VISIBLE else View.GONE
        binding.saveButton.isEnabled = !isBusy
        binding.deleteButton.isEnabled = !isBusy
        binding.choosePhotoButton.isEnabled = !isBusy
    }

    /** Loads and shows the pet's photo, or the placeholder when there is none. */
    private fun renderPhoto(path: String?) {
        if (path == renderedPhotoPath) return
        renderedPhotoPath = path

        if (path == null) {
            binding.petPhotoImage.setImageResource(R.drawable.ic_pet_placeholder)
            binding.petPhotoImage.scaleType = ImageView.ScaleType.CENTER_INSIDE
            return
        }

        // Decoding touches the disk, so it happens in a coroutine.
        viewLifecycleOwner.lifecycleScope.launch {
            val bitmap = PetPhotoStorage.load(path)
            if (bitmap != null) {
                binding.petPhotoImage.setImageBitmap(bitmap)
                // centerCrop fills the whole card with the photo.
                binding.petPhotoImage.scaleType = ImageView.ScaleType.CENTER_CROP
                binding.petPhotoImage.setPadding(0, 0, 0, 0)
            }
        }
    }

    /** Reacts to a one-off event from the ViewModel. */
    private fun handleEvent(event: PetEvent) {
        when (event) {
            is PetEvent.PrefillForm -> prefillForm(event.pet)

            // Both outcomes simply close the screen.
            is PetEvent.Saved, is PetEvent.Deleted -> findNavController().navigateUp()

            is PetEvent.Error -> {
                Snackbar.make(binding.root, getString(event.messageRes), Snackbar.LENGTH_LONG)
                    .show()
            }
        }
    }

    /** Writes a loaded pet's values into the text fields. */
    @SuppressLint("SetTextI18n")
    private fun prefillForm(pet: Pet) {
        binding.nameInput.setText(pet.name)
        binding.breedInput.setText(pet.breed.orEmpty())
        // A null weight leaves the field blank rather than showing "null" or a misleading 0.
        binding.weightInput.setText(pet.weightKg?.toString().orEmpty())
        binding.dietaryNotesInput.setText(pet.dietaryNotes.orEmpty())
        binding.allergiesInput.setText(pet.allergies.orEmpty())
        binding.favouriteToysInput.setText(pet.favouriteToys.orEmpty())
    }

    /** The two directions between a toggle button id and a [PetGender]. */
    private fun buttonForGender(gender: PetGender): Int = when (gender) {
        PetGender.MALE -> R.id.genderMaleButton
        PetGender.FEMALE -> R.id.genderFemaleButton
        PetGender.UNKNOWN -> R.id.genderUnknownButton
    }

    private fun genderForButton(buttonId: Int): PetGender = when (buttonId) {
        R.id.genderMaleButton -> PetGender.MALE
        R.id.genderFemaleButton -> PetGender.FEMALE
        // The group holds only three buttons, so this is the Unknown button.
        else -> PetGender.UNKNOWN
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Forces the photo to be drawn again against the new view hierarchy.
        renderedPhotoPath = null
        // The framing dialog holds a decoded bitmap and a view hierarchy of its own; left showing.
        cropDialog?.dismiss()
        cropDialog = null
        _binding = null
    }

    companion object {
        /** Navigation argument names; must match those declared in nav_graph.xml. */
        const val ARG_PET_ID = "petId"
        const val ARG_OWNER_ID = "ownerId"

        /** Tag the FragmentManager uses to restore the date picker after a rotation. */
        private const val DATE_PICKER_TAG = "pet_date_of_birth_picker"
    }
}
