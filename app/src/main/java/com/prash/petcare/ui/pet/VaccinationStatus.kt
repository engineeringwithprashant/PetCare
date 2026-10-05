package com.prash.petcare.ui.pet

import android.content.Context
import androidx.annotation.AttrRes
import androidx.annotation.ColorRes
import com.prash.petcare.R
import com.prash.petcare.data.local.entity.VaccinationRecord
import com.prash.petcare.util.daysUntil

/** How urgent one vaccination is right now — the pill on the right of each history row. */
enum class VaccinationStatus(
    @get:AttrRes val pillBackgroundAttr: Int,
    @get:AttrRes val pillInkAttr: Int,
    @get:ColorRes val barColorRes: Int
) {

    /** A one-off dose with no repeat. Nothing is outstanding, so it reads as finished. */
    DONE(
        R.attr.colorVaccinationClear,
        R.attr.colorOnVaccinationClear,
        R.color.vaccination_bar_clear
    ),

    /** The due date has passed and the dose has not been recorded. The one red state. */
    OVERDUE(
        R.attr.colorVaccinationOverdue,
        R.attr.colorOnVaccinationOverdue,
        R.color.vaccination_bar_overdue
    ),

    /** Due today. */
    DUE_TODAY(
        R.attr.colorVaccinationDue,
        R.attr.colorOnVaccinationDue,
        R.color.vaccination_bar_due
    ),

    /** Due inside the next month — the pill carries the day count. */
    DUE_SOON(
        R.attr.colorVaccinationDue,
        R.attr.colorOnVaccinationDue,
        R.color.vaccination_bar_due
    ),

    /** Due, but far enough off to need no attention. */
    UP_TO_DATE(
        R.attr.colorVaccinationClear,
        R.attr.colorOnVaccinationClear,
        R.color.vaccination_bar_clear
    );

    companion object {

        /** How many days ahead still counts as "due soon" rather than "up to date". */
        private const val DUE_SOON_WINDOW_DAYS = 30

        /** Applies the table above to one due date. */
        fun of(
            dateDue: Long?,
            nowMillis: Long = System.currentTimeMillis()
        ): Pair<VaccinationStatus, Int> {
            // No due date at all: the dose was a one-off, so there is nothing to be early or late for.
            if (dateDue == null) return DONE to 0

            val days = daysUntil(dateDue, nowMillis)

            val status = when {
                days < 0 -> OVERDUE
                days == 0 -> DUE_TODAY
                days <= DUE_SOON_WINDOW_DAYS -> DUE_SOON
                else -> UP_TO_DATE
            }

            return status to days
        }
    }
}

/** The words on the pill: "DONE", "OVERDUE", "DUE TODAY", "DUE IN 11D", "UP TO DATE". */
fun VaccinationStatus.label(context: Context, days: Int): String = when (this) {
    VaccinationStatus.DONE -> context.getString(R.string.vaccination_status_done)
    VaccinationStatus.OVERDUE -> context.getString(R.string.vaccination_status_overdue)
    VaccinationStatus.DUE_TODAY -> context.getString(R.string.vaccination_status_due_today)
    VaccinationStatus.DUE_SOON -> context.getString(R.string.vaccination_status_due_in, days)
    VaccinationStatus.UP_TO_DATE -> context.getString(R.string.vaccination_status_up_to_date)
}
