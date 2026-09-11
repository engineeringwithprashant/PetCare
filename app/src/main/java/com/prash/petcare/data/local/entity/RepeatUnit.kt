package com.prash.petcare.data.local.entity

/**
 * The unit of time used to describe how often a [CareRoutine] repeats.
 *
 * A routine's schedule is expressed as two fields working together: this unit plus
 * [CareRoutine.repeatInterval] (a count). That pairing lets a small set of constants describe
 * many schedules — DAY + 1 is "every day", WEEK + 2 is "every fortnight", MONTH + 3 is
 * "every quarter".
 *
 * Stored in SQLite as a String via [com.prash.petcare.data.local.converter.Converters].
 */
enum class RepeatUnit {
    DAY,
    WEEK,
    MONTH,
    YEAR
}
