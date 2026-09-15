package com.prash.petcare.data.local.entity

/**
 * The sex of a [Pet], as far as the owner knows it.
 *
 * An enum rather than a nullable Boolean or free text, for the same reasons as [PetSpecies]: the
 * form can offer a fixed set of choices, and the rest of the app can branch on a known value (the
 * detail screen picks a Mars or Venus symbol from it) without guarding against typos.
 *
 * [UNKNOWN] is a real constant rather than `null`. "The owner has not said" is an ordinary, common
 * answer — a rescue animal, a bird that has never been sexed — and giving it a name means the column
 * can be `NOT NULL` and every `when` over this enum has to decide explicitly what to do with it,
 * rather than a null quietly slipping through.
 *
 * Stored in SQLite as the constant's name via
 * [com.prash.petcare.data.local.converter.Converters], so the order of the constants below can be
 * changed freely without re-pointing existing rows.
 */
enum class PetGender {
    MALE,
    FEMALE,

    /** Not recorded. The default for every pet created before this field existed. */
    UNKNOWN
}
