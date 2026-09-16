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

/**
 * Form for creating a new pet or editing an existing one.
 *
 * Which mode it runs in depends on the `petId` navigation argument: [PetViewModel.NO_PET_ID] means
 * add, anything else means edit. The distinction is entirely the ViewModel's to interpret — this
 * fragment simply renders whatever state comes back.
 *
 * As the **View** in MVVM it owns no logic: validation, persistence and photo clean-up all live in
 * [PetViewModel]. What it does own are the things that genuinely need a Context or a view — the
 * photo picker, the date picker and the confirmation dialog.
 */
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

    /**
     * Which photo path is currently drawn on screen.
     *
     * Decoding an image is not free, so this guards against re-loading the same file every time
     * any unrelated part of the state changes. Cleared in [onDestroyView] so a rebuilt view draws
     * the photo again.
     */
    private var renderedPhotoPath: String? = null

    /**
     * Launcher for the system photo picker.
     *
     * Registered as a property because `registerForActivityResult` must be called while the
     * fragment is being created — doing it later throws, since the result callback has to be in
     * place before the fragment can be restored and handed a pending result.
     *
     * The photo picker needs no storage permission: the user chooses the image in a system UI that
     * the app never sees, and only the single chosen item is shared back.
     */
    private val pickPhotoLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        // Null when the user backed out without choosing anything.
        if (uri != null) {
            showCropDialog(uri)
        }
    }

    /**
     * The framing dialog, held so it can be dismissed with the view.
     *
     * A dialog that outlives the fragment's view leaks the whole hierarchy behind it, and one still
     * on screen when the user rotates would be left orphaned by the recreation.
     */
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

    /**
     * Fills the species dropdown.
     *
     * The visible labels and [PetSpecies.entries] are built from the same list in the same order,
     * so the tapped position maps straight back to an enum constant. Reading the enum back from the
     * displayed text instead would break the moment the app is translated.
     */
    private fun setUpSpeciesDropdown() {
        val labels = PetSpecies.entries.map { getString(it.labelRes()) }.toTypedArray()
        binding.speciesInput.setSimpleItems(labels)

        binding.speciesInput.setOnItemClickListener { _, _, position, _ ->
            viewModel.onSpeciesSelected(PetSpecies.entries[position])
        }
    }

    /**
     * Forwards a tap on the Male / Female / Unknown group to the ViewModel.
     *
     * The group reports both the button being selected and the one being cleared, so `isChecked`
     * is tested — without it every tap would record two genders in quick succession, the one
     * chosen and the one moved away from, and whichever arrived last would win.
     */
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

        // The field is focusable so it can be reached by keyboard, D-pad or switch access —
        // with focusable="false" the picker could only ever be opened by touch. Suppressing the
        // soft keyboard keeps focusing it from popping one up for a field nobody types into. This
        // has no public XML attribute, so it is set here.
        binding.dateOfBirthInput.showSoftInputOnFocus = false
        binding.dateOfBirthInput.setOnClickListener { showDatePicker() }

        binding.saveButton.setOnClickListener {
            // The typed values are read straight off the fields and handed over; deciding whether
            // they are valid is the ViewModel's job.
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

    /**
     * Opens the framing dialog on the chosen image.
     *
     * **Why a picked photo is no longer saved straight away.** Every pet card draws its photo in one
     * fixed shape, and the app used to fill that shape by cropping to the centre — which on a
     * full-body photo is the animal's middle rather than its face. So the picked image is decoded to
     * memory, the user frames it, and only what they framed is written to disk. Nothing is stored if
     * they cancel.
     *
     * Done here rather than in the ViewModel because reading the picked URI needs a ContentResolver
     * — and therefore a Context, which the ViewModel deliberately does not hold. Only the resulting
     * file path is passed on.
     *
     * Uses the fragment's own `lifecycleScope`, not `viewLifecycleOwner`'s: a picker result can
     * arrive before the view has been recreated, and the view lifecycle owner does not exist yet at
     * that point.
     */
    private fun showCropDialog(uri: Uri) {
        val appContext = requireContext().applicationContext

        lifecycleScope.launch {
            // Decoded before the dialog is built, so a file that turns out not to be an image is
            // reported plainly instead of opening an empty framing window over nothing.
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
                // Cancelling stores nothing at all, which is the point of decoding to memory first:
                // there is no half-finished file on disk to tidy up if the user changes their mind.
                .show()
        }
    }

    /**
     * Writes the framed photo and hands its path to the ViewModel.
     *
     * The bitmap is produced on the main thread — it is one `drawBitmap` into an image no larger
     * than the screen, so it is quick — and only the compress-and-write half is moved to a
     * background thread inside [PetPhotoStorage.saveBitmap]. Doing it in this order is what lets the
     * crop be read from the view before the dialog tears it down.
     *
     * The width asked for is the view's own, so a phone with a wider screen stores a
     * correspondingly sharper image, capped inside the crop view at a sensible maximum.
     */
    private fun saveCrop(cropView: PhotoCropView) {
        val appContext = requireContext().applicationContext
        val cropped = cropView.croppedBitmap(cropView.width)

        if (cropped == null) {
            viewModel.onPhotoFailed()
            return
        }

        // The fragment's own scope, not the view's: see the note on the picker result above — the
        // same reasoning applies to the write that follows it.
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

    /**
     * Asks before deleting.
     *
     * Deleting a pet also removes its care tasks, routines and expenses through the database's
     * cascade rules, and none of that can be undone — far too much to lose to a mis-tap, which is
     * why the dialog spells out the consequence rather than just asking "are you sure?".
     */
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

        // Reflects the ViewModel's choice back onto the group — which is what restores it after a
        // rotation and fills it in for an existing pet. `check` does nothing when that button is
        // already selected, so this cannot loop with the listener above.
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

        // Decoding touches the disk, so it happens in a coroutine. Tying it to the view lifecycle
        // means the work is cancelled if the screen closes first.
        viewLifecycleOwner.lifecycleScope.launch {
            val bitmap = PetPhotoStorage.load(path)
            if (bitmap != null) {
                binding.petPhotoImage.setImageBitmap(bitmap)
                // centerCrop fills the whole card with the photo; the padding that gives the
                // placeholder icon breathing room would leave a border around a real image.
                binding.petPhotoImage.scaleType = ImageView.ScaleType.CENTER_CROP
                binding.petPhotoImage.setPadding(0, 0, 0, 0)
            }
        }
    }

    /** Reacts to a one-off event from the ViewModel. */
    private fun handleEvent(event: PetEvent) {
        when (event) {
            is PetEvent.PrefillForm -> prefillForm(event.pet)

            // Both outcomes simply close the screen; the home screen behind it is already
            // observing the database and updates itself.
            is PetEvent.Saved, is PetEvent.Deleted -> findNavController().navigateUp()

            is PetEvent.Error -> {
                Snackbar.make(binding.root, getString(event.messageRes), Snackbar.LENGTH_LONG)
                    .show()
            }
        }
    }

    /**
     * Writes a loaded pet's values into the text fields.
     *
     * Deliberately called once, from a one-shot event, rather than on every state update — writing
     * into an EditText the user is typing in would move their cursor and undo their edits. After a
     * rotation these fields restore themselves from Android's own saved instance state, so there is
     * nothing to repeat.
     *
     * The weight is written with a plain `toString`, which SetTextI18n would normally flag as
     * ignoring locale — hence the suppression. It is deliberate: this value is read back and parsed
     * with `toDoubleOrNull` on save, and that expects a full stop as the decimal separator. Writing
     * "24,5" here in a locale that uses a comma would make the pet's own weight fail validation the
     * next time the form was saved.
     */
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

    /**
     * The two directions between a toggle button id and a [PetGender].
     *
     * Kept as a matched pair next to each other so a fourth option would be added to both at once.
     * The `when` over the enum has no `else`, so adding a constant to [PetGender] will not compile
     * until it has a button here.
     */
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
        // The framing dialog holds a decoded bitmap and a view hierarchy of its own; left showing,
        // it would keep the destroyed view alive behind it.
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
