package com.prash.petcare.ui.common

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.prash.petcare.R
import com.prash.petcare.data.local.entity.RepeatUnit
import com.prash.petcare.data.local.entity.TaskCategory
import java.util.Calendar

/** Turns care-related database values into text for the screen. */

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

/** Icon for a care category. */
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

/** The emoji that stands for a care category: 🍖 feeding, 🐾 walk, 💊 medication. */
@StringRes
fun TaskCategory.emojiRes(): Int = when (this) {
    TaskCategory.FEEDING -> R.string.category_emoji_feeding
    TaskCategory.WALK -> R.string.category_emoji_walk
    TaskCategory.GROOMING -> R.string.category_emoji_grooming
    TaskCategory.MEDICATION -> R.string.category_emoji_medication
    TaskCategory.VET_VISIT -> R.string.category_emoji_vet_visit
    TaskCategory.TRAINING -> R.string.category_emoji_training
    TaskCategory.PLAY -> R.string.category_emoji_play
    TaskCategory.OTHER -> R.string.category_emoji_other
}

/** The category as it appears where it is chosen — "💊 Medication". */
fun TaskCategory.emojiLabel(context: Context): String = context.getString(
    R.string.category_emoji_label,
    context.getString(emojiRes()),
    context.getString(labelRes())
)

/** Display name for how often a routine repeats. */
@StringRes
fun RepeatUnit.labelRes(): Int = when (this) {
    RepeatUnit.DAY -> R.string.schedule_daily
    RepeatUnit.WEEK -> R.string.schedule_weekly
    RepeatUnit.MONTH -> R.string.schedule_monthly
    RepeatUnit.YEAR -> R.string.schedule_yearly
}

/** Formats a task's time of day for display. */
fun formatTimeOfDay(context: Context, minutesSinceMidnight: Int): String {
    val calendar = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, minutesSinceMidnight / MINUTES_PER_HOUR)
        set(Calendar.MINUTE, minutesSinceMidnight % MINUTES_PER_HOUR)
    }
    return android.text.format.DateFormat.getTimeFormat(context).format(calendar.time)
}

private const val MINUTES_PER_HOUR = 60
