package com.prash.petcare.ui.common

import android.view.LayoutInflater
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.prash.petcare.R
import com.prash.petcare.data.local.entity.Pet
import com.prash.petcare.databinding.ViewPetPickerBinding

/**
 * Fills the pet picker's chip row and keeps the right chip selected.
 *
 * Shared by the Tasks and Expenses tabs, which both show one animal at a time and both offer this
 * row to choose between them. Written once here rather than twice in the two fragments, so the two
 * pickers behave identically — the layout is shared for the same reason, see view_pet_picker.xml.
 *
 * **Chips are only rebuilt when the pets actually change.** The screens call this on every state
 * emission, which happens whenever a task is ticked or an expense recorded — many times more often
 * than a pet is added. Tearing down and re-inflating the row each time would be wasteful, would
 * lose the row's scroll position mid-scroll, and would restart the chips' selection animation. So
 * the ids currently on screen are compared against the ids being asked for, and the views are
 * rebuilt only when those differ.
 *
 * @param pets The pets to offer, in the order they should appear.
 * @param selectedPetId Which of them is showing, or [NO_SELECTION] when there is none.
 * @param onPetSelected Called with a pet's id when the user taps its chip. Not called when this
 *   function sets the selection itself — see [bindSelection] for why that distinction matters.
 */
fun ViewPetPickerBinding.bindPets(
    pets: List<Pet>,
    selectedPetId: Long,
    onPetSelected: (petId: Long) -> Unit
) {
    val group = petChipGroup

    if (!group.matchesPets(pets)) {
        group.rebuildChips(pets)
    }

    group.bindSelection(selectedPetId, onPetSelected)
}

/**
 * True when the chips on screen are already exactly these pets, in this order.
 *
 * Compared by id *and* by name, because a rename has to redraw the chip even though the set of pets
 * has not changed. Comparing the whole [Pet] instead would rebuild the row every time an unrelated
 * field moved — a weight being recorded, or the sync clearing a row's pending flag.
 */
private fun ChipGroup.matchesPets(pets: List<Pet>): Boolean {
    if (childCount != pets.size) return false

    return pets.withIndex().all { (index, pet) ->
        val chip = getChildAt(index) as? Chip ?: return false
        chip.id == pet.id.toChipId() && chip.text == pet.name
    }
}

/** Replaces the row with one chip per pet. */
private fun ChipGroup.rebuildChips(pets: List<Pet>) {
    removeAllViews()

    val inflater = LayoutInflater.from(context)

    pets.forEach { pet ->
        // Inflated from a layout rather than constructed with `Chip(context)`, because a Material
        // component takes most of its appearance from the style applied at inflation time. A chip
        // built in code gets the platform defaults instead and would not match the app at all.
        //
        // `attachToRoot = false`: the chip is given its id before being added below, since a
        // ChipGroup reports its selection by view id and a chip added first would briefly be
        // selectable as View.NO_ID.
        val chip = inflater.inflate(R.layout.item_pet_chip, this, false) as Chip

        chip.id = pet.id.toChipId()
        chip.text = pet.name

        addView(chip)
    }
}

/**
 * Marks the selected pet's chip as checked, without that counting as a user tap.
 *
 * The listener is detached first and reattached afterwards. Without it, setting `isChecked` here
 * would fire the change listener, which would report a "selection" back to the ViewModel, which
 * would publish new state, which would arrive here and set the selection again. The loop settles —
 * the value stops changing — but it runs on every single emission, and it makes a genuine tap
 * indistinguishable from the screen redrawing itself.
 */
private fun ChipGroup.bindSelection(selectedPetId: Long, onPetSelected: (petId: Long) -> Unit) {
    setOnCheckedStateChangeListener(null)

    if (selectedPetId == NO_SELECTION) {
        clearCheck()
    } else {
        check(selectedPetId.toChipId())
    }

    setOnCheckedStateChangeListener { _, checkedIds ->
        // A list, because a ChipGroup supports multiple selection in general; this one is declared
        // singleSelection in the layout, so it holds at most one. firstOrNull covers the moment
        // during a change when it is briefly empty.
        val chipId = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
        onPetSelected(chipId.fromChipId())
    }
}

/**
 * Turns a pet's database id into a view id, and back.
 *
 * A [ChipGroup] reports its selection as view ids, so the pet a chip stands for has to be
 * recoverable from one. Using the database id directly is what makes that a lookup-free conversion
 * — no map from view id to pet to keep in step with the chips.
 *
 * The cast narrows a `Long` to an `Int`, which is safe here for a reason worth stating: these are
 * SQLite `AUTOINCREMENT` row ids, and a personal pet-care app will not reach two billion pets.
 *
 * `View.NO_ID` is -1, the same value the app uses for "no id" throughout, so a pet id of -1 could
 * never reach this — it is filtered out as [NO_SELECTION] before any chip is asked about.
 */
private fun Long.toChipId(): Int = toInt()

private fun Int.fromChipId(): Long = toLong()

/** The selected-pet value meaning "nothing chosen", matching the app's convention for absent ids. */
const val NO_SELECTION = -1L
