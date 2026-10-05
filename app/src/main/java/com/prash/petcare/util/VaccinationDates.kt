package com.prash.petcare.util

import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/* The three date calculations the vaccination feature is built on: how a date is written. */

/** Milliseconds in one day. Used to turn a difference between two midnights into a day count. */
private const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L

/** Writes a stored date the way the design does: "22 Sep 2026". */
fun formatDate(millis: Long): String {
    // android.icu rather than java.text: it is part of the platform from API 24.
    val formatter = android.icu.text.SimpleDateFormat("d MMM yyyy", Locale.US)
    // ICU's formatters take ICU's own TimeZone, not java.util's.
    formatter.timeZone = android.icu.util.TimeZone.getTimeZone(UTC)
    return formatter.format(Date(millis))
}

/** The same date [millis] falls on, [months] calendar months later. */
fun addMonths(millis: Long, months: Int): Long {
    val calendar = Calendar.getInstance(TimeZone.getTimeZone(UTC))
    calendar.timeInMillis = millis
    calendar.add(Calendar.MONTH, months)
    return calendar.timeInMillis
}

/** Whole days from today until [millis] — negative when the date has already passed. */
fun daysUntil(millis: Long, nowMillis: Long = System.currentTimeMillis()): Int {
    val startOfTarget = startOfUtcDay(millis)
    val startOfToday = startOfUtcDay(nowMillis)

    // Both operands are exact multiples of a day, so the division is exact and there is nothing to round.
    return ((startOfTarget - startOfToday) / MILLIS_PER_DAY).toInt()
}

/** Midnight UTC on the day [millis] falls in. */
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
