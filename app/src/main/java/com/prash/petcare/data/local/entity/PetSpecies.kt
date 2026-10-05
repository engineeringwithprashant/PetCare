package com.prash.petcare.data.local.entity

/** The kind of animal a [Pet] is. */
enum class PetSpecies {
    DOG,
    CAT,
    BIRD,
    RABBIT,
    FISH,
    REPTILE,
    /** Fallback so the user is never blocked from adding an unusual pet. */
    OTHER
}
