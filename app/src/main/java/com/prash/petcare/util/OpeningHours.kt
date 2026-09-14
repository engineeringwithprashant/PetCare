package com.prash.petcare.util

/**
 * Decides whether a place is open at a given moment, from its opening and closing times.
 *
 * Kept as a pure function outside the ViewModel for the same reason as [CareSchedule]: it is exactly
 * the kind of logic that fails quietly. A clinic that closes at 02:00 showing "closed" all evening,
 * or a place that shuts at 20:00 still claiming "open" at 20:00, looks fine in a screenshot taken at
 * lunchtime. Tests (see OpeningHoursTest) are the only reliable way to catch that.
 */
object OpeningHours {

    /** Minutes in a day — a time of day is always in `0 until MINUTES_PER_DAY`. */
    const val MINUTES_PER_DAY = 24 * 60

    /**
     * True when [nowMinutes] falls within the opening hours.
     *
     * All three values are minutes since midnight. The window is **half-open**: a place is open at
     * its opening minute and closed at its closing minute, which is how "8:00 – 20:00" is meant — at
     * 20:00 the door is locked.
     *
     * Three shapes of hours are handled:
     *  - **unknown** (either time null): never reported open, because "OPEN NOW" is a promise and
     *    there is nothing to base it on;
     *  - **same day** (opens before it closes, e.g. 08:00–20:00): open between the two;
     *  - **past midnight** (closes before it opens, e.g. 18:00–02:00): open from the opening time to
     *    midnight *and* from midnight to the closing time.
     *
     * Opening and closing at the same minute is treated as open around the clock, since that is the
     * only sensible reading of "00:00 – 00:00" typed into a form.
     */
    fun isOpen(openingMinutes: Int?, closingMinutes: Int?, nowMinutes: Int): Boolean {
        if (openingMinutes == null || closingMinutes == null) return false

        return when {
            openingMinutes == closingMinutes -> true
            openingMinutes < closingMinutes -> nowMinutes in openingMinutes until closingMinutes
            else -> nowMinutes >= openingMinutes || nowMinutes < closingMinutes
        }
    }
}
