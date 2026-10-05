package com.prash.petcare.ui.checklist

import android.content.Context
import com.prash.petcare.R
import com.prash.petcare.ui.common.formatTimeOfDay
import java.text.DateFormat
import java.util.Date

/** Turns the outstanding part of a checklist into the plain-text body of a delegation message. */
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
                // A blank line before each section, so the message reads as grouped parts of the day.
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

/** One task's line: "- Breakfast at 8:30 AM", with "(Krishala)" added when pets are mixed. */
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

/** The subject line for a delegation email. */
fun buildDelegationSubject(context: Context, petLabel: String): String =
    context.getString(R.string.delegate_email_subject, petLabel)
