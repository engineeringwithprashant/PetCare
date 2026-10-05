package com.prash.petcare.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.prash.petcare.PetCareApplication
import kotlinx.coroutines.launch

/** Receives the broadcast an alarm delivers when a care task comes due. */
class ReminderReceiver : BroadcastReceiver() {

    /** Called by the system on the main thread. */
    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getLongExtra(EXTRA_TASK_ID, NO_TASK_ID)

        if (taskId == NO_TASK_ID) {
            // Should not happen — every alarm this app registers carries an id.
            Log.w(TAG, "Reminder broadcast with no task id; ignoring")
            return
        }

        val application = context.applicationContext as? PetCareApplication ?: return

        val pendingResult = goAsync()

        // The application's own scope, not a new one created here: it is tied to the process.
        application.applicationScope.launch {
            try {
                application.reminderManager.onAlarmFired(taskId)
            } catch (throwable: Throwable) {
                // A failure here must not bring the app down — nothing is on screen to show an error.
                Log.e(TAG, "Failed to handle reminder for task $taskId", throwable)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "ReminderReceiver"

        /** Action naming what this broadcast is for. */
        const val ACTION_TASK_DUE = "com.prash.petcare.action.TASK_DUE"

        /** Extra carrying which task has come due. */
        const val EXTRA_TASK_ID = "com.prash.petcare.extra.TASK_ID"

        /** Sentinel for "the broadcast carried no task id", which should never occur. */
        private const val NO_TASK_ID = -1L
    }
}
