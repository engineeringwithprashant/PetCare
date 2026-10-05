package com.prash.petcare.util

/** Decides whether a place is open at a given moment, from its opening and closing times. */
object OpeningHours {

    /** Minutes in a day — a time of day is always in `0 until MINUTES_PER_DAY`. */
    const val MINUTES_PER_DAY = 24 * 60

    /** True when [nowMinutes] falls within the opening hours. */
    fun isOpen(openingMinutes: Int?, closingMinutes: Int?, nowMinutes: Int): Boolean {
        if (openingMinutes == null || closingMinutes == null) return false

        return when {
            openingMinutes == closingMinutes -> true
            openingMinutes < closingMinutes -> nowMinutes in openingMinutes until closingMinutes
            else -> nowMinutes >= openingMinutes || nowMinutes < closingMinutes
        }
    }
}
