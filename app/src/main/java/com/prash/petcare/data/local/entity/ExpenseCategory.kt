package com.prash.petcare.data.local.entity

/**
 * The spending category of an [Expense].
 *
 * Kept separate from [TaskCategory] even though a few names overlap (GROOMING, MEDICATION),
 * because the two lists answer different questions: what care was given, versus what money was
 * spent. Merging them would force unusable options into both screens — a "PLAY" expense, or an
 * "INSURANCE" care task.
 *
 * Stored in SQLite as a String via [com.prash.petcare.data.local.converter.Converters].
 */
enum class ExpenseCategory {
    FOOD,
    VET,
    MEDICATION,
    GROOMING,
    TOYS,
    ACCESSORIES,
    INSURANCE,
    TRAINING,
    OTHER
}
