package com.prash.petcare.reminder

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.navigation.NavDeepLinkBuilder
import com.prash.petcare.MainActivity
import com.prash.petcare.R
import com.prash.petcare.data.local.relation.TaskReminder
import com.prash.petcare.ui.checklist.ChecklistFragment
import com.prash.petcare.ui.common.formatTimeOfDay

/** Builds and posts the notification a care reminder shows. */
class ReminderNotifier(private val context: Context) {

    private val notificationManager = NotificationManagerCompat.from(context)

    /** Registers the channel every care reminder is posted to. */
    fun createNotificationChannel() {
        val channel = NotificationChannelCompat
            // DEFAULT importance: makes a sound and appears in the status bar.
            .Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_DEFAULT)
            .setName(context.getString(R.string.reminder_channel_name))
            .setDescription(context.getString(R.string.reminder_channel_description))
            .build()

        notificationManager.createNotificationChannel(channel)
    }

    /** Shows the reminder for one task, naming the pet, what needs doing. */
    fun notifyTaskDue(
        reminder: TaskReminder,
        requiredSupplies: String?,
        notes: String?
    ): Boolean {
        // From Android 13 posting a notification needs the user's permission.
        val isPermitted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

        if (!isPermitted) return false

        val body = buildBody(reminder, requiredSupplies, notes)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            // Pet and task together in the title — "Bella: Morning meal".
            .setContentTitle(
                context.getString(
                    R.string.reminder_notification_title,
                    reminder.petName,
                    reminder.taskTitle
                )
            )
            // The whole body goes to both. A collapsed notification has room for one line and shows this one ellipsized.
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(checklistPendingIntent(reminder.petId))
            // Dismisses the notification.
            .setAutoCancel(true)
            // Lets the system treat this as a time-based reminder.
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            // The pre-Android 8 equivalent of the channel's importance.
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        notificationManager.notify(notificationId(reminder.taskId), notification)
        return true
    }

    /** The notification's text: the time it is due, then what to bring, then the user's own note. */
    private fun buildBody(
        reminder: TaskReminder,
        requiredSupplies: String?,
        notes: String?
    ): String = buildString {
        append(
            context.getString(
                R.string.reminder_notification_text,
                formatTimeOfDay(context, reminder.timeOfDayMinutes)
            )
        )

        requiredSupplies?.takeIf { it.isNotBlank() }?.let { supplies ->
            append("\n")
            append(context.getString(R.string.reminder_notification_supplies, supplies.trim()))
        }

        notes?.takeIf { it.isNotBlank() }?.let { note ->
            append("\n")
            append(context.getString(R.string.reminder_notification_notes, note.trim()))
        }
    }

    /** Removes a reminder already on screen — used when its task is no longer being reminded about. */
    fun cancel(taskId: Long) {
        notificationManager.cancel(notificationId(taskId))
    }

    /** What happens when the notification is tapped: the app opens on that pet's checklist. */
    private fun checklistPendingIntent(petId: Long): PendingIntent =
        NavDeepLinkBuilder(context)
            // Named explicitly rather than left to the default lookup of the launcher activity.
            .setComponentName(MainActivity::class.java)
            .setGraph(R.navigation.nav_graph)
            .setDestination(R.id.checklistFragment)
            // The argument name must match the one the graph declares.
            .setArguments(bundleOf(ChecklistFragment.ARG_PET_ID to petId))
            .createPendingIntent()

    /** The notification id for a task. */
    private fun notificationId(taskId: Long): Int = (taskId % Int.MAX_VALUE).toInt()

    private companion object {
        /** Channel id. A fixed, never-translated string: it is a key the system stores. */
        private const val CHANNEL_ID = "care_reminders"
    }
}
