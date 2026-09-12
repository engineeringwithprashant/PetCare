package com.prash.petcare.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.net.toUri

/**
 * Registers and cancels the operating system alarms that fire the app's care reminders.
 *
 * **Where this sits.** It is the only class that touches [AlarmManager], and it knows nothing about
 * pets, routines or repeat rules — it is handed a task id and a moment, and makes the system wake
 * the app then. Deciding *which* moment is [com.prash.petcare.util.CareSchedule]'s job and deciding
 * *which tasks* is [ReminderManager]'s; keeping the three apart means the date arithmetic stays unit
 * testable on a plain JVM, with only this thin layer needing a real device.
 *
 * **Why AlarmManager and not a background service.** A reminder has to arrive at 07:30 whether or
 * not the app is running, and Android will not keep a process alive just in case. AlarmManager is
 * the system's own scheduler: the app registers an intent to be delivered at a time, the process can
 * then be killed freely, and Android starts it again to deliver it.
 *
 * **Alarms do not survive a reboot.** The system forgets every registered alarm when the device
 * restarts, which is why [BootReceiver] exists to have them all set up again.
 *
 * @param context Application context. Deliberately not an Activity's: this object is created once
 *   by [com.prash.petcare.PetCareApplication] and lives for the whole process, so holding a screen's
 *   context would leak that screen for as long as the app ran.
 */
class ReminderScheduler(private val context: Context) {

    /** Null only on a device with no alarm service at all, which in practice does not happen. */
    private val alarmManager: AlarmManager? =
        context.getSystemService(AlarmManager::class.java)

    /**
     * Asks the system to deliver [taskId]'s reminder at [triggerAtMillis].
     *
     * Registering a second alarm for a task that already has one **replaces** it rather than
     * stacking up a duplicate. That is what `FLAG_UPDATE_CURRENT` combined with a per-task request
     * code buys: the system recognises the new [PendingIntent] as the same pending operation as the
     * old one. The whole re-planning strategy rests on it — [ReminderManager] can safely re-register
     * every reminder whenever anything changes, without having to work out what was already set.
     */
    fun schedule(taskId: Long, triggerAtMillis: Long) {
        val manager = alarmManager ?: return

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode(taskId),
            reminderIntent(taskId),
            // UPDATE_CURRENT refreshes the extras of an existing alarm instead of creating a
            // second one. IMMUTABLE says whoever holds this PendingIntent — here the system's
            // alarm service — cannot fill in or change any field of the Intent inside it. From
            // API 31 one of IMMUTABLE/MUTABLE must be stated explicitly, and immutable is the
            // right choice: nothing outside this class has any business rewriting which task a
            // reminder is for.
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // RTC_WAKEUP, not ELAPSED_REALTIME: a care reminder belongs to a wall-clock time of day,
        // so it must track the clock rather than time since boot. WAKEUP asks the system to bring
        // the device out of sleep to deliver it, which is the point of a reminder — one that waited
        // politely for the user to pick the phone up would arrive hours late.
        try {
            if (canScheduleExactAlarms(manager)) {
                // ...AndWhileIdle: still delivered when the device is in Doze, which it will be at
                // 07:30 if the phone spent the night on a bedside table. A plain setExact() is
                // deferred to the next maintenance window, which could be a long way off.
                manager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
            } else {
                // From Android 12 exact alarms need a permission the user can refuse, and Google
                // reserves the always-granted version for clocks and calendars. A pet reminder is
                // not one of those, so it degrades gracefully instead of failing: this still wakes
                // the device and still arrives, the system just batches it with other work and may
                // shift it by a few minutes. For "time to feed the cat" that is no loss, and it is
                // far better than an alarm that throws and never fires at all.
                manager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
            }
        } catch (securityException: SecurityException) {
            // Defensive: exact-alarm access can be revoked between the check above and this call.
            // A reminder failing to register must never take the app down with it, so it is logged
            // and the rest of the reminders carry on being scheduled.
            Log.w(TAG, "Could not schedule reminder for task $taskId", securityException)
        }
    }

    /**
     * Withdraws [taskId]'s alarm, if it has one.
     *
     * Used when a task is deleted, when its routine is paused or removed, and when its pet goes —
     * a notification for care that no longer exists would be worse than no notification at all.
     *
     * `FLAG_NO_CREATE` is what makes this safe to call for a task that was never scheduled: it asks
     * the system for the *existing* pending operation and returns null rather than manufacturing a
     * new one just to cancel it.
     */
    fun cancel(taskId: Long) {
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode(taskId),
            reminderIntent(taskId),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        ) ?: return

        alarmManager?.cancel(pendingIntent)
        // Also discards the PendingIntent itself, so nothing is left registered with the system
        // holding a reference to this app on behalf of a task that is gone.
        pendingIntent.cancel()
    }

    /**
     * Whether the app may set alarms that fire at a precise minute.
     *
     * Before Android 12 every app could, so there is nothing to ask. From API 31 it is a
     * user-visible setting, and [AlarmManager.canScheduleExactAlarms] reports the current answer —
     * which can change while the app is installed, so it is read at each scheduling rather than
     * cached.
     */
    private fun canScheduleExactAlarms(manager: AlarmManager): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()

    /**
     * The broadcast that will be delivered when a reminder comes due.
     *
     * Two details matter for identity rather than for delivery. Android compares PendingIntents by
     * action, data, component and categories — **not** by their extras — so two tasks whose intents
     * differed only in an id extra would be treated as the same pending operation, and the second
     * task's alarm would silently overwrite the first's. The per-task URI in `data` is what keeps
     * them distinct; the extra is how the receiver actually reads the id.
     *
     * That URI is never resolved or opened. Its scheme is arbitrary and exists purely to be unique
     * per task.
     */
    private fun reminderIntent(taskId: Long): Intent =
        Intent(context, ReminderReceiver::class.java).apply {
            action = ReminderReceiver.ACTION_TASK_DUE
            data = "$REMINDER_URI_PREFIX$taskId".toUri()
            putExtra(ReminderReceiver.EXTRA_TASK_ID, taskId)
        }

    /**
     * A per-task request code, the other half of what makes each alarm its own.
     *
     * Task ids are `Long` and request codes are `Int`, so the id is reduced into the positive Int
     * range. Two ids could in principle collide, but that needs them to differ by over two billion
     * — a household would have to create that many care tasks first.
     */
    private fun requestCode(taskId: Long): Int =
        (REQUEST_CODE_BASE + (taskId % Int.MAX_VALUE)).toInt()

    private companion object {
        private const val TAG = "ReminderScheduler"

        private const val REMINDER_URI_PREFIX = "petcare://reminder/"

        /**
         * Keeps reminder request codes clear of any other PendingIntent the app might register in
         * future, so an unrelated feature numbering its own from zero cannot collide with a task's.
         */
        private const val REQUEST_CODE_BASE = 1_000L
    }
}
