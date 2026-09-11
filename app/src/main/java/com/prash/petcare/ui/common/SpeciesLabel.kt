package com.prash.petcare.ui.common

import androidx.annotation.StringRes
import com.prash.petcare.R
import com.prash.petcare.data.local.entity.PetSpecies

/**
 * Maps a [PetSpecies] to its user-facing label.
 *
 * Kept in the UI layer rather than on the enum itself, so the data layer stays free of Android
 * resource references and remains testable on a plain JVM. It lives in its own file because both
 * the pet list and the add/edit form need it.
 *
 * The `when` has no `else` branch on purpose: Kotlin then requires every constant to be listed, so
 * adding a species to the enum will not compile until a label has been written for it. An `else`
 * would silently show the wrong name instead.
 */
@StringRes
fun PetSpecies.labelRes(): Int = when (this) {
    PetSpecies.DOG -> R.string.species_dog
    PetSpecies.CAT -> R.string.species_cat
    PetSpecies.BIRD -> R.string.species_bird
    PetSpecies.RABBIT -> R.string.species_rabbit
    PetSpecies.FISH -> R.string.species_fish
    PetSpecies.REPTILE -> R.string.species_reptile
    PetSpecies.OTHER -> R.string.species_other
}
