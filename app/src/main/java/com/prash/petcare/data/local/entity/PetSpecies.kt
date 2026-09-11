package com.prash.petcare.data.local.entity

/**
 * The kind of animal a [Pet] is.
 *
 * Modelling this as an enum rather than a free-text String means the UI can offer a fixed
 * dropdown and the rest of the app can branch on a known set of values (for example, to pick
 * a placeholder icon) without having to guard against typos or inconsistent capitalisation.
 *
 * Room cannot store an enum directly, so
 * [com.prash.petcare.data.local.converter.Converters] turns each constant into its [name]
 * String when writing to SQLite and back again when reading.
 */
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
