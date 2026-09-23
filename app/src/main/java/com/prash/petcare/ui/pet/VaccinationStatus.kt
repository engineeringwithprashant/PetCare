package com.prash.petcare.ui.pet

import android.content.Context
import androidx.annotation.AttrRes
import androidx.annotation.ColorRes
import com.prash.petcare.R
import com.prash.petcare.data.local.entity.VaccinationRecord
import com.prash.petcare.util.daysUntil

/**
 * How urgent one vaccination is **right now** — the pill on the right of each history row, and the
 * colour of the bar down its left.
 *
 * This is the rule the whole feature turns on, so it is worth stating plainly. Given a record's next
 * due date and today's date:
 *
 * | Due date            | Status        | Reads as      | Colour |
 * |---------------------|---------------|---------------|--------|
 * | none (one-off dose) | [DONE]        | "DONE"        | green  |
 * | in the past         | [OVERDUE]     | "OVERDUE"     | red    |
 * | today               | [DUE_TODAY]   | "DUE TODAY"   | accent |
 * | 1–30 days away      | [DUE_SOON]    | "DUE IN 11D"  | accent |
 * | more than 30 away   | [UP_TO_DATE]  | "UP TO DATE"  | green  |
 *
 * **Three things are worth noticing about the shape of that table.**
 *
 * First, the whole rule is a function of one signed number — [daysUntil] — rather than of several
 * separate comparisons against the clock. That is what makes the boundaries impossible to get wrong:
 * "due today" is exactly `0`, not "within twenty-four hours", so a booster due tomorrow can never
 * briefly read as due today late this evening.
 *
 * Second, **no status is ever stored**. A status is a statement about today, and a column holding
 * one would start lying at the next midnight with nothing to correct it — see [VaccinationRecord].
 * Every row's pill is recomputed each time the card is drawn, which costs nothing and cannot drift.
 *
 * Third, [DONE] and [UP_TO_DATE] are deliberately *different* states that happen to share a colour.
 * They mean different things — "there was never anything more to do" against "there is something to
 * do, but not for a while" — and merging them would make a one-off deworming dose claim a future due
 * date it does not have.
 *
 * The thirty-day window for [DUE_SOON] is the one arbitrary number here. It is a month, which is
 * roughly the notice a vet appointment needs, and it is what separates "put this in the diary" from
 * "nothing to think about".
 *
 * @property pillBackgroundAttr The theme attribute for the pill's fill.
 * @property pillInkAttr The colour guaranteed legible on that fill. Always used with the fill above
 *   it and never with another — see attrs.xml.
 * @property barColorRes The 3dp bar beside the row. A colour rather than an attribute because, like
 *   the app's other small coloured marks, it is the same saturated value on the cream page and the
 *   near-black one — see the vaccination section of colors.xml.
 */
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

        /**
         * Applies the table above to one due date.
         *
         * @param dateDue The record's next due date, or null for a one-off dose.
         * @param nowMillis Overridable so the rule can be exercised on a fixed day in a test; the app
         *   always leaves it at the current clock, which is what lets a screen left open overnight
         *   redraw itself correctly the next morning.
         * @return the status, paired with the day count — negative in the past — that produced it, so
         *   the caller can write "DUE IN 11D" without repeating the calculation and risking a pill
         *   whose number disagrees with its own colour.
         */
        fun of(
            dateDue: Long?,
            nowMillis: Long = System.currentTimeMillis()
        ): Pair<VaccinationStatus, Int> {
            // No due date at all: the dose was a one-off, so there is nothing to be early or late
            // for. Returning 0 days rather than null keeps the pair simple; no caller reads the
            // count for this status.
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

/**
 * The words on the pill: "DONE", "OVERDUE", "DUE TODAY", "DUE IN 11D", "UP TO DATE".
 *
 * An extension taking a [Context] rather than a property on the enum, because the text comes from
 * string resources — which is what keeps the rule above free of Android types and testable, and what
 * would let these five labels be translated without touching the rule at all. The same arrangement
 * as the app's other label extensions (see ui/common/CareLabels.kt).
 *
 * [VaccinationStatus.DUE_SOON] is the only one that needs the number, and it takes it from the same
 * calculation that chose the status — see [VaccinationStatus.of].
 */
fun VaccinationStatus.label(context: Context, days: Int): String = when (this) {
    VaccinationStatus.DONE -> context.getString(R.string.vaccination_status_done)
    VaccinationStatus.OVERDUE -> context.getString(R.string.vaccination_status_overdue)
    VaccinationStatus.DUE_TODAY -> context.getString(R.string.vaccination_status_due_today)
    VaccinationStatus.DUE_SOON -> context.getString(R.string.vaccination_status_due_in, days)
    VaccinationStatus.UP_TO_DATE -> context.getString(R.string.vaccination_status_up_to_date)
}
