package com.prash.petcare.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.net.toUri

/** Registers and cancels the operating system alarms that fire the app's care reminders. */
class ReminderScheduler(private val context: Context) {

    /** Null only on a device with no alarm service at all, which in practice does not happen. */
    private val alarmManager: AlarmManager? =
        context.getSystemService(AlarmManager::class.java)

    /** Asks the system to deliver [taskId]'s reminder at [triggerAtMillis]. */
    fun schedule(taskId: Long, triggerAtMillis: Long) {
        val manager = alarmManager ?: return

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode(taskId),
            reminderIntent(taskId),
            // UPDATE_CURRENT refreshes the extras of an existing alarm instead of creating a second one.
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // RTC_WAKEUP, not ELAPSED_REALTIME: a care reminder belongs to a wall-clock time of day.
        try {
            if (canScheduleExactAlarms(manager)) {
                // ...AndWhileIdle: still delivered when the device is in Doze.
                manager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
            } else {
                // From Android 12 exact alarms need a permission the user can refuse.
                manager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
            }
        } catch (securityException: SecurityException) {
            // Defensive: exact-alarm access can be revoked between the check above and this call.
            Log.w(TAG, "Could not schedule reminder for task $taskId", securityException)
        }
    }

    /** Withdraws [taskId]'s alarm, if it has one. */
    fun cancel(taskId: Long) {
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode(taskId),
            reminderIntent(taskId),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        ) ?: return

        alarmManager?.cancel(pendingIntent)
        // Also discards the PendingIntent itself.
        pendingIntent.cancel()
    }

    /** Whether the app may set alarms that fire at a precise minute. */
    private fun canScheduleExactAlarms(manager: AlarmManager): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()

    /** The broadcast that will be delivered when a reminder comes due. */
    private fun reminderIntent(taskId: Long): Intent =
        Intent(context, ReminderReceiver::class.java).apply {
            action = ReminderReceiver.ACTION_TASK_DUE
            data = "$REMINDER_URI_PREFIX$taskId".toUri()
            putExtra(ReminderReceiver.EXTRA_TASK_ID, taskId)
        }

    /** A per-task request code, the other half of what makes each alarm its own. */
    private fun requestCode(taskId: Long): Int =
        (REQUEST_CODE_BASE + (taskId % Int.MAX_VALUE)).toInt()

    private companion object {
        private const val TAG = "ReminderScheduler"

        private const val REMINDER_URI_PREFIX = "petcare://reminder/"

        /** Keeps reminder request codes clear of any other PendingIntent the app might register in future. */
        private const val REQUEST_CODE_BASE = 1_000L
    }
}
