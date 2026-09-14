package com.prash.petcare.ui.checklist

import android.content.Context
import com.prash.petcare.R
import com.prash.petcare.ui.common.formatTimeOfDay
import java.text.DateFormat
import java.util.Date

/**
 * Turns the outstanding part of a checklist into the plain-text body of a delegation message.
 *
 * Part of the View layer's helpers: pure formatting with no state, kept out of both the fragment
 * and the ViewModel so the exact wording lives in one place. The message has to stand on its own in
 * someone else's inbox, with no access to the app, so it repeats everything a carer needs: whose
 * care it is, which day, and for each task its time, supplies and notes.
 *
 * The same text serves both delegation channels — the body of a text message or of an email — so the
 * two can never describe the same checklist differently. Plain text with no markup is what makes that
 * possible.
 *
 * @param context Needed for string resources and the device's time format, so the message is in the
 *   user's own language and clock convention.
 * @param petLabel Whose care this is — one pet's name, or several joined into a list.
 * @param dayStart The day the checklist is for. Stated explicitly because a message may be read
 *   hours later, and "today" would then be ambiguous — and because the Tasks tab can now delegate a
 *   day other than today.
 * @param items The **outstanding** tasks, with their time-of-day headers, so the message is grouped
 *   in the same order as the screen. Finished tasks are excluded by the caller: listing them invites
 *   a dose or a feed being repeated.
 * @param showPetNames True when the tasks span more than one pet, so each line says whose it is.
 */
fun buildDelegationMessage(
    context: Context,
    petLabel: String,
    dayStart: Long,
    items: List<ChecklistItem>,
    showPetNames: Boolean
): String = buildString {

    val date = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(dayStart))
    append(context.getString(R.string.delegate_message_header, petLabel, date))

    items.forEach { item ->
        when (item) {
            is ChecklistItem.SectionHeader -> {
                // A blank line before each section, so the message reads as grouped parts of the day
                // rather than one unbroken block.
                append("\n\n")
                append(context.getString(item.timeOfDay.labelRes))
                append(":")
            }

            is ChecklistItem.TaskRow -> {
                val task = item.task
                append("\n")
                append(taskLine(context, item, showPetNames))

                task.requiredSupplies?.takeIf { it.isNotBlank() }?.let { supplies ->
                    append("\n")
                    append(context.getString(R.string.delegate_message_supplies, supplies))
                }

                task.notes?.takeIf { it.isNotBlank() }?.let { notes ->
                    append("\n")
                    append(context.getString(R.string.delegate_message_notes, notes))
                }
            }
        }
    }
}

/**
 * One task's line: "- Breakfast at 8:30 AM", with "(Krishala)" added when pets are mixed.
 *
 * Four string resources rather than one built by concatenation, because word order around a time
 * and a name differs between languages and only a whole sentence template can be translated well.
 * Every task made in the app has a time, but the column allows null, so the no-time forms keep the
 * message reading correctly if one does not.
 */
private fun taskLine(context: Context, row: ChecklistItem.TaskRow, showPetName: Boolean): String {
    val title = row.task.title
    val time = row.task.timeOfDayMinutes?.let { formatTimeOfDay(context, it) }

    return when {
        time != null && showPetName ->
            context.getString(R.string.delegate_message_task_with_pet, title, time, row.petName)

        time != null -> context.getString(R.string.delegate_message_task, title, time)

        showPetName ->
            context.getString(R.string.delegate_message_task_no_time_with_pet, title, row.petName)

        else -> context.getString(R.string.delegate_message_task_no_time, title)
    }
}

/**
 * The subject line for a delegation email.
 *
 * A text message has no subject, so this is used by the email channel alone. It sits beside
 * [buildDelegationMessage] because the two are one piece of writing: the subject names the pets and
 * the body opens with the same wording, and keeping them together stops one being reworded without
 * the other. The names are what make the mail findable later in an inbox holding several of these.
 */
fun buildDelegationSubject(context: Context, petLabel: String): String =
    context.getString(R.string.delegate_email_subject, petLabel)
