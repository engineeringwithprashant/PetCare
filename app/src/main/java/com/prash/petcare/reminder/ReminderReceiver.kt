package com.prash.petcare.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.prash.petcare.PetCareApplication
import kotlinx.coroutines.launch

/**
 * Receives the broadcast an alarm delivers when a care task comes due.
 *
 * **Why a receiver and not something inside the app.** By the time a reminder is due the app is
 * very likely not running at all. A [BroadcastReceiver] declared in the manifest can be started by
 * the system on the app's behalf — Android creates the process, the [PetCareApplication] and then
 * this receiver, just to deliver the broadcast. This is the entry point the alarm wakes up.
 *
 * The class itself holds no logic. It reads which task fired and hands straight over to
 * [ReminderManager], so deciding whether to notify and scheduling the next occurrence live in one
 * place whether they were reached from an alarm, from a reboot, or from the app running normally.
 *
 * Registered in AndroidManifest.xml with `android:exported="false"` — nothing outside this app has
 * any reason to send it a reminder, and an exported receiver could be triggered by any other app on
 * the device.
 */
class ReminderReceiver : BroadcastReceiver() {

    /**
     * Called by the system on the **main thread**, with a hard limit of a few seconds before the app
     * is considered unresponsive — and, worse, with the process eligible to be killed the instant
     * this method returns.
     *
     * That is what [goAsync] is for. It tells the system the receiver is not finished yet and keeps
     * the process alive until [android.content.BroadcastReceiver.PendingResult.finish] is called, so
     * the database reads can happen off the main thread in a coroutine. Without it, the work would
     * be cancelled part-way through: the notification might appear but the *next* occurrence would
     * never be scheduled, and every reminder for that task would stop arriving from then on.
     *
     * `finish()` is in a `finally` so it runs even if the work throws. Failing to call it leaks the
     * process; the system eventually kills it and logs a complaint.
     */
    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getLongExtra(EXTRA_TASK_ID, NO_TASK_ID)

        if (taskId == NO_TASK_ID) {
            // Should not happen — every alarm this app registers carries an id. Guarded rather than
            // assumed, because a receiver is a public-ish entry point and must not crash.
            Log.w(TAG, "Reminder broadcast with no task id; ignoring")
            return
        }

        val application = context.applicationContext as? PetCareApplication ?: return

        val pendingResult = goAsync()

        // The application's own scope, not a new one created here: it is tied to the process rather
        // than to this receiver, and it is where the app's other background work already runs.
        application.applicationScope.launch {
            try {
                application.reminderManager.onAlarmFired(taskId)
            } catch (throwable: Throwable) {
                // A failure here must not bring the app down — nothing is on screen to show an
                // error on, and the process exists only to deliver this one broadcast.
                Log.e(TAG, "Failed to handle reminder for task $taskId", throwable)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "ReminderReceiver"

        /**
         * Action naming what this broadcast is for.
         *
         * Part of how the system tells one task's pending alarm from another's — see
         * [ReminderScheduler.reminderIntent]. Namespaced with the package so it cannot collide with
         * another app's action.
         */
        const val ACTION_TASK_DUE = "com.prash.petcare.action.TASK_DUE"

        /** Extra carrying which task has come due. */
        const val EXTRA_TASK_ID = "com.prash.petcare.extra.TASK_ID"

        /** Sentinel for "the broadcast carried no task id", which should never occur. */
        private const val NO_TASK_ID = -1L
    }
}
