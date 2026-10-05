package com.prash.petcare.ui.common

import android.content.Context
import com.prash.petcare.R
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import java.util.TimeZone

/** Formats a stored calendar date for display. */
fun formatStoredDate(millis: Long): String {
    val formatter = DateFormat.getDateInstance(DateFormat.MEDIUM)
    formatter.timeZone = TimeZone.getTimeZone("UTC")
    return formatter.format(Date(millis))
}

/** Turns a date of birth into the pet's age, worded as an owner would say it. */
fun formatAge(context: Context, dateOfBirthMillis: Long): String {
    val months = ageInMonths(dateOfBirthMillis)
    val years = months / MONTHS_PER_YEAR

    // getQuantityString picks the right plural form for the device's language.
    return if (years >= 1) {
        context.resources.getQuantityString(R.plurals.pet_detail_age_years, years, years)
    } else {
        context.resources.getQuantityString(R.plurals.pet_detail_age_months, months, months)
    }
}

/** The same age, abbreviated: "3 yrs", "5 mo". */
fun formatAgeShort(context: Context, dateOfBirthMillis: Long): String {
    val months = ageInMonths(dateOfBirthMillis)
    val years = months / MONTHS_PER_YEAR

    return if (years >= 1) {
        context.resources.getQuantityString(R.plurals.home_pet_age_years_short, years, years)
    } else {
        context.resources.getQuantityString(R.plurals.home_pet_age_months_short, months, months)
    }
}

/** A pet's age as a whole number of completed months. */
private fun ageInMonths(dateOfBirthMillis: Long): Int {
    val utc = TimeZone.getTimeZone("UTC")

    val born = Calendar.getInstance(utc).apply { timeInMillis = dateOfBirthMillis }
    val now = Calendar.getInstance(utc)

    var months = (now.get(Calendar.YEAR) - born.get(Calendar.YEAR)) * MONTHS_PER_YEAR +
        (now.get(Calendar.MONTH) - born.get(Calendar.MONTH))

    // The month has turned but the day of the month has not yet come round, so the last month is not complete.
    if (now.get(Calendar.DAY_OF_MONTH) < born.get(Calendar.DAY_OF_MONTH)) {
        months--
    }

    // A date in the future means the record is wrong rather than that the pet is unborn.
    return months.coerceAtLeast(0)
}

private const val MONTHS_PER_YEAR = 12
