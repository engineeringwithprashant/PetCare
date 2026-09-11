package com.prash.petcare.ui.common

import java.text.DateFormat
import java.util.Date
import java.util.TimeZone

/**
 * Formats a stored calendar date for display.
 *
 * The formatter is fixed to UTC on purpose. Dates chosen through Material's date picker are
 * returned as midnight **UTC** for the selected day, so formatting them in the device's local time
 * zone would shift the value backwards for anyone west of Greenwich and print the previous day.
 *
 * [DateFormat.getDateInstance] follows the device's locale, so the same date reads as "5 Mar 2026"
 * or "Mar 5, 2026" depending on the user's settings rather than being pinned to one country's
 * convention.
 *
 * This is for whole calendar dates only. A time of day is formatted by [formatTimeOfDay], which
 * deliberately uses the local time zone instead.
 */
fun formatStoredDate(millis: Long): String {
    val formatter = DateFormat.getDateInstance(DateFormat.MEDIUM)
    formatter.timeZone = TimeZone.getTimeZone("UTC")
    return formatter.format(Date(millis))
}
