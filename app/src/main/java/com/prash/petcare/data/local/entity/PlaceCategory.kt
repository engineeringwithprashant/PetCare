package com.prash.petcare.data.local.entity

/**
 * What kind of somewhere a saved [Place] is.
 *
 * Deliberately a short, closed list rather than free text. The Places tab filters by it, the
 * distance strip labels each place with it, and a free-text "type" field would fragment into
 * "Vet", "vet clinic" and "Veterinary" — three chips for one idea.
 *
 * Kept separate from [TaskCategory] and [ExpenseCategory] for the reason given on
 * [ExpenseCategory]: each list answers a different question. A park is somewhere you go, not a kind
 * of care or a kind of spending.
 *
 * Stored in SQLite as a String via [com.prash.petcare.data.local.converter.Converters].
 */
enum class PlaceCategory {
    VET,
    GROOMING,
    PARK,
    STORE
}
