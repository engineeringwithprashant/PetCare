package com.prash.petcare.ui.common

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.prash.petcare.R
import com.prash.petcare.data.local.entity.RepeatUnit
import com.prash.petcare.data.local.entity.TaskCategory
import java.util.Calendar

/**
 * Turns care-related database values into text for the screen.
 *
 * These live in the UI layer rather than on the enums themselves so the data layer stays free of
 * Android resource references and can be unit-tested on a plain JVM. They sit in one file because
 * the routine, task and checklist screens all need them.
 *
 * Each `when` deliberately has no `else` branch: Kotlin then requires every constant to be listed,
 * so adding a category or a repeat unit will not compile until a label has been written for it.
 */

/** Display name for a care category, e.g. VET_VISIT becomes "Vet visit". */
@StringRes
fun TaskCategory.labelRes(): Int = when (this) {
    TaskCategory.FEEDING -> R.string.category_feeding
    TaskCategory.WALK -> R.string.category_walk
    TaskCategory.GROOMING -> R.string.category_grooming
    TaskCategory.MEDICATION -> R.string.category_medication
    TaskCategory.VET_VISIT -> R.string.category_vet_visit
    TaskCategory.TRAINING -> R.string.category_training
    TaskCategory.PLAY -> R.string.category_play
    TaskCategory.OTHER -> R.string.category_other
}

/**
 * Icon for a care category.
 *
 * Pairs with [labelRes] so a category can be shown as a symbol alongside its name. An icon is read
 * far faster than a word when scanning a checklist, and it keeps the grouping legible at a glance
 * once several categories are on screen at once.
 *
 * Like the labels, this `when` has no `else`, so adding a category will not compile until an icon
 * has been chosen for it.
 */
@DrawableRes
fun TaskCategory.iconRes(): Int = when (this) {
    TaskCategory.FEEDING -> R.drawable.ic_category_feeding
    TaskCategory.WALK -> R.drawable.ic_category_walk
    TaskCategory.GROOMING -> R.drawable.ic_category_grooming
    TaskCategory.MEDICATION -> R.drawable.ic_category_medication
    TaskCategory.VET_VISIT -> R.drawable.ic_category_vet
    TaskCategory.TRAINING -> R.drawable.ic_category_training
    TaskCategory.PLAY -> R.drawable.ic_category_play
    TaskCategory.OTHER -> R.drawable.ic_category_other
}

/**
 * Display name for how often a routine repeats.
 *
 * Worded as the user would say it ("Every day") rather than as the enum spells it, since the
 * constants are storage values and were never meant to be read aloud.
 */
@StringRes
fun RepeatUnit.labelRes(): Int = when (this) {
    RepeatUnit.DAY -> R.string.schedule_daily
    RepeatUnit.WEEK -> R.string.schedule_weekly
    RepeatUnit.MONTH -> R.string.schedule_monthly
    RepeatUnit.YEAR -> R.string.schedule_yearly
}

/**
 * Formats a task's time of day for display.
 *
 * Tasks store a time as minutes since midnight, which has no date attached, so a [Calendar] is used
 * purely as a carrier: today's date with that hour and minute set. Only the time part is ever
 * printed, so which day it lands on is irrelevant.
 *
 * [android.text.format.DateFormat.getTimeFormat] is used rather than a fixed pattern because it
 * follows the device's own 12- or 24-hour setting, so the same task reads as "7:30 AM" or "07:30"
 * depending on how the user has their phone configured.
 */
fun formatTimeOfDay(context: Context, minutesSinceMidnight: Int): String {
    val calendar = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, minutesSinceMidnight / MINUTES_PER_HOUR)
        set(Calendar.MINUTE, minutesSinceMidnight % MINUTES_PER_HOUR)
    }
    return android.text.format.DateFormat.getTimeFormat(context).format(calendar.time)
}

private const val MINUTES_PER_HOUR = 60
