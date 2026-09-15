package com.prash.petcare.ui.common

import android.content.Context
import com.prash.petcare.R
import java.text.DateFormat
import java.util.Calendar
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

/**
 * Turns a date of birth into the pet's age, worded as an owner would say it.
 *
 * **Years, except for the very young.** "3 years old" is how people describe an adult animal, but
 * a kitten would come out as "0 years old" — which is both odd to read and less use than the months
 * it is hiding. So anything under a year is reported in months instead. A newborn reads as
 * "0 months old", which is correct and reads naturally in a way "0 years old" does not.
 *
 * **Why a UTC calendar.** The stored value is midnight UTC for the chosen day, for the reason
 * explained on [formatStoredDate]. Reading its year and month through a local-time calendar would
 * roll the date back a day for anyone west of Greenwich — usually harmless, but enough to report a
 * pet as a year younger on its own birthday.
 *
 * "Now" is read in UTC for the same comparison to make sense. The result is a whole number of years
 * or months, so the few hours either way that the device's own offset would contribute cannot
 * change the answer.
 */
fun formatAge(context: Context, dateOfBirthMillis: Long): String {
    val months = ageInMonths(dateOfBirthMillis)
    val years = months / MONTHS_PER_YEAR

    // getQuantityString picks the right plural form for the device's language. The first argument
    // selects the form; the second fills the placeholder.
    return if (years >= 1) {
        context.resources.getQuantityString(R.plurals.pet_detail_age_years, years, years)
    } else {
        context.resources.getQuantityString(R.plurals.pet_detail_age_months, months, months)
    }
}

/**
 * The same age, abbreviated: "3 yrs", "5 mo".
 *
 * For the dashboard's pet cards, where the age shares one small capsule with the pet's weight and
 * the full "3 years old" would push the weight off the end of it. Everywhere the space exists,
 * [formatAge] is still the right function — an abbreviation is harder to read and worth using only
 * where it buys something.
 *
 * Both formatters derive their number from [ageInMonths], so the two can never disagree about how
 * old an animal is — which they would if this one repeated the calendar arithmetic and only one of
 * the copies were later corrected.
 */
fun formatAgeShort(context: Context, dateOfBirthMillis: Long): String {
    val months = ageInMonths(dateOfBirthMillis)
    val years = months / MONTHS_PER_YEAR

    return if (years >= 1) {
        context.resources.getQuantityString(R.plurals.home_pet_age_years_short, years, years)
    } else {
        context.resources.getQuantityString(R.plurals.home_pet_age_months_short, months, months)
    }
}

/**
 * A pet's age as a whole number of completed months.
 *
 * The shared calculation behind both age formatters above. It is expressed in months rather than in
 * years-and-months because that is the form both callers need, and because working in months
 * throughout avoids the classic off-by-one where a pet born in December is called a year old in the
 * following January simply because the year number changed.
 *
 * See [formatAge] for why the calendars are fixed to UTC.
 */
private fun ageInMonths(dateOfBirthMillis: Long): Int {
    val utc = TimeZone.getTimeZone("UTC")

    val born = Calendar.getInstance(utc).apply { timeInMillis = dateOfBirthMillis }
    val now = Calendar.getInstance(utc)

    var months = (now.get(Calendar.YEAR) - born.get(Calendar.YEAR)) * MONTHS_PER_YEAR +
        (now.get(Calendar.MONTH) - born.get(Calendar.MONTH))

    // The month has turned but the day of the month has not yet come round, so the last month is
    // not complete. Without this, a pet born on the 30th would gain a month on the 1st.
    if (now.get(Calendar.DAY_OF_MONTH) < born.get(Calendar.DAY_OF_MONTH)) {
        months--
    }

    // A date in the future means the record is wrong rather than that the pet is unborn. Clamping
    // to zero reports "0 months old" instead of a negative age, which would read as nonsense and
    // would also pick the wrong plural form.
    return months.coerceAtLeast(0)
}

private const val MONTHS_PER_YEAR = 12
