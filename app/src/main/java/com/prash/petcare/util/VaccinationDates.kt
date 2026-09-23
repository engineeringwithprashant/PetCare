package com.prash.petcare.util

import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/*
 * The three date calculations the vaccination feature is built on: how a date is written, how the
 * next due date is worked out from an interval, and how far away a due date is.
 *
 * They live in `util` rather than in a ViewModel or a fragment because all three are pure functions
 * of their arguments — no Context, no Android view, no database — which means the status rule that
 * depends on them can be reasoned about (and tested) without a device. See
 * [com.prash.petcare.ui.pet.VaccinationStatus] for the rule itself.
 *
 * **Every function here works in UTC, and that is load-bearing rather than incidental.** A
 * vaccination date is a *calendar day*, not a moment: "22 September 2026" is the same day in Patan
 * and in London. The app stores such a day as midnight UTC — the convention used for a pet's date of
 * birth and an expense's date, see [com.prash.petcare.ui.common.formatStoredDate] — so reading one
 * back through a local-time calendar would roll it to the previous day for every user west of
 * Greenwich. That is invisible most of the time and then, on exactly the day a booster is due,
 * reports it as overdue.
 */

/** Milliseconds in one day. Used to turn a difference between two midnights into a day count. */
private const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L

/**
 * Writes a stored date the way the design does: "22 Sep 2026".
 *
 * Deliberately *not* [com.prash.petcare.ui.common.formatStoredDate], which is the app's general
 * date formatter and follows the device's locale — it would produce "Sep 22, 2026" for a US user and
 * "22/09/2026" for some others. The vaccination card sets its dates in monospace beside a pill and a
 * coloured bar, where the column has to stay the same width down the card and the reading has to be
 * unambiguous at a glance; a format that changes shape between devices would break both.
 *
 * A locale is named explicitly rather than left to the default for the same reason. The *pattern* is
 * fixed here, but the month abbreviations come from a locale, and the default one would translate
 * them into a language the rest of this card is not written in.
 *
 * **[Locale.US] specifically, and not [Locale.UK]** — which would be the more natural choice for an
 * app otherwise written in British English. The two differ on exactly one month: en-GB abbreviates
 * September to "Sept", four letters where every other month has three. That single ragged month is
 * enough to turn the history row's 40dp date column — sized because every other abbreviation fits —
 * into "SEPT…". en-US gives "Sep", so all twelve are the same width, and "22 Sep 2026" is the form
 * the design writes.
 *
 * The `d` (not `dd`) is intentional: the design writes "2 Jun 2026", not "02 Jun 2026".
 */
fun formatDate(millis: Long): String {
    // android.icu rather than java.text: it is part of the platform from API 24 (this app's minimum)
    // and is the formatter the rest of the app reaches for — see ComingUpAdapter.
    val formatter = android.icu.text.SimpleDateFormat("d MMM yyyy", Locale.US)
    // ICU's formatters take ICU's own TimeZone, not java.util's — the two are separate class
    // hierarchies that happen to share a name, which is why this one is written out in full.
    formatter.timeZone = android.icu.util.TimeZone.getTimeZone(UTC)
    return formatter.format(Date(millis))
}

/**
 * The same date [millis] falls on, [months] calendar months later.
 *
 * **Calendar months, not a fixed number of days.** Adding "12 months" to 22 September must land on
 * 22 September, whether or not a leap day fell in between; adding 365 days would drift by one day
 * every four years and quietly make every annual booster date wrong. [Calendar.add] does the
 * calendar arithmetic properly.
 *
 * It also handles the case a naive implementation gets wrong: adding one month to 31 January cannot
 * produce 31 February, and [Calendar.add] clamps it to 28 (or 29) February rather than rolling over
 * into March. That is the behaviour a vet card implies — "next month, same date, or as close as the
 * month allows".
 *
 * Returns midnight UTC on the resulting day, so the value can be stored and compared alongside every
 * other date in the app. See the file note above for why the calendar is fixed to UTC.
 */
fun addMonths(millis: Long, months: Int): Long {
    val calendar = Calendar.getInstance(TimeZone.getTimeZone(UTC))
    calendar.timeInMillis = millis
    calendar.add(Calendar.MONTH, months)
    return calendar.timeInMillis
}

/**
 * Whole days from today until [millis] — negative when the date has already passed.
 *
 * So a due date two days away gives 2, today gives 0, and yesterday gives -1. That signed number is
 * the entire input to the status rule, which is what keeps "overdue", "due today" and "due in 11
 * days" as three readings of one calculation rather than three separate comparisons that could
 * disagree at the boundaries.
 *
 * **Both sides are truncated to midnight first, which is the whole trick.** Subtracting two raw
 * timestamps and dividing by a day would answer "how many 24-hour periods fit in the gap", so a
 * booster due tomorrow morning, asked about this evening, would come back as 0 — "due today" — which
 * is wrong by a day. Comparing midnight to midnight asks the question the user is actually asking:
 * how many times does the date change between now and then.
 *
 * @param millis The date being asked about, as stored: midnight UTC on some calendar day.
 * @param nowMillis Overridable so a test can ask the question on a fixed day rather than on
 *   whichever day it happens to run. In the app it is always left at the current clock.
 */
fun daysUntil(millis: Long, nowMillis: Long = System.currentTimeMillis()): Int {
    val startOfTarget = startOfUtcDay(millis)
    val startOfToday = startOfUtcDay(nowMillis)

    // Both operands are exact multiples of a day, so the division is exact and there is nothing to
    // round: the result cannot be a fraction of a day in either direction.
    return ((startOfTarget - startOfToday) / MILLIS_PER_DAY).toInt()
}

/**
 * Midnight UTC on the day [millis] falls in.
 *
 * Stored dates are already at midnight and pass through unchanged; "now" is not, and this is what
 * makes it comparable with them. The four fields are cleared rather than the value being rounded
 * down arithmetically, which is the same thing here but says plainly what is meant.
 */
private fun startOfUtcDay(millis: Long): Long {
    val calendar = Calendar.getInstance(TimeZone.getTimeZone(UTC))
    calendar.timeInMillis = millis
    calendar.set(Calendar.HOUR_OF_DAY, 0)
    calendar.set(Calendar.MINUTE, 0)
    calendar.set(Calendar.SECOND, 0)
    calendar.set(Calendar.MILLISECOND, 0)
    return calendar.timeInMillis
}

/** The one time zone every calculation in this file works in — see the file note above. */
private const val UTC = "UTC"
