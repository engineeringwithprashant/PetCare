package com.prash.petcare.ui.common

import androidx.annotation.StringRes
import com.prash.petcare.R
import com.prash.petcare.data.local.entity.PetSpecies

/** Maps a [PetSpecies] to its user-facing label. */
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
