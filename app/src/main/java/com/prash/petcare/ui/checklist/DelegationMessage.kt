package com.prash.petcare.ui.checklist

import android.content.Context
import com.prash.petcare.R
import com.prash.petcare.ui.common.formatTimeOfDay
import com.prash.petcare.ui.common.labelRes
import java.text.DateFormat
import java.util.Date

/**
 * Turns a pet's checklist into the plain-text body of a delegation message.
 *
 * The message has to stand on its own in someone else's inbox, with no access to the app that
 * produced it, so it repeats everything a carer would need: which pet, which day, and for each task
 * its category, time, supplies and notes.
 *
 * The same text serves both delegation channels — it is sent as the body of a text message, or as
 * the body of an email — so the two channels can never describe the same checklist differently.
 * Plain text with no markup is what makes that possible: an SMS has no formatting to lose, and a
 * mail app is happy to compose a plain-text body.
 *
 * Kept out of the fragment and the ViewModel: it is pure formatting, and isolating it means the
 * exact text can be checked without a screen or a database.
 *
 * @param context Needed to resolve string resources and the device's time format, so the message
 *   is written in the user's own language and clock convention.
 * @param petName Whose checklist this is.
 * @param items The **outstanding** part of the checklist, headers included, so the message groups
 *   by category in the same order shown on screen. Tasks already done are excluded by the caller:
 *   the purpose of delegating is to tell someone what still needs doing, and listing finished work
 *   alongside it invites a dose or a feed being repeated.
 */
fun buildDelegationMessage(
    context: Context,
    petName: String,
    items: List<ChecklistItem>
): String = buildString {

    // The date is stated explicitly because a text message may well be read hours later, or the
    // next morning, and "today" would then be ambiguous.
    val today = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date())
    append(context.getString(R.string.delegate_message_header, petName, today))

    items.forEach { item ->
        when (item) {
            is ChecklistItem.CategoryHeader -> {
                // A blank line before each category, so the message reads as grouped sections
                // rather than one unbroken block of text.
                append("\n\n")
                append(context.getString(item.category.labelRes()))
                append(":")
            }

            is ChecklistItem.TaskRow -> {
                val task = item.task
                append("\n")

                val time = task.timeOfDayMinutes?.let { formatTimeOfDay(context, it) }
                append(
                    if (time != null) {
                        context.getString(R.string.delegate_message_task, task.title, time)
                    } else {
                        // Every task made in the app has a time, but the column allows null, so
                        // the message still reads correctly if one somehow does not.
                        context.getString(R.string.delegate_message_task_no_time, task.title)
                    }
                )

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
 * The subject line for a delegation email.
 *
 * A text message has no subject, so this is used by the email channel alone. It lives here beside
 * [buildDelegationMessage] rather than in the fragment because the two are one piece of writing:
 * the subject names the pet and the body then opens with the same wording, and keeping them
 * together is what stops one being reworded without the other.
 *
 * The pet's name is what makes the mail findable later — a carer looking after two animals will
 * have two of these sitting in their inbox, and "Remaining care" alone would not tell them apart in
 * a message list.
 */
fun buildDelegationSubject(context: Context, petName: String): String =
    context.getString(R.string.delegate_email_subject, petName)
