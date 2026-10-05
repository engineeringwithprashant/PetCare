package com.prash.petcare.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.prash.petcare.PetCareApplication
import kotlinx.coroutines.launch

/** Puts the care reminders back after an event that invalidates every registered alarm. */
class BootReceiver : BroadcastReceiver() {

    /** See [ReminderReceiver.onReceive] for why [goAsync] is used. */
    override fun onReceive(context: Context, intent: Intent) {
        // The action is logged rather than branched on, because the response to all four is the same.
        Log.d(TAG, "Rescheduling care reminders after ${intent.action}")

        val application = context.applicationContext as? PetCareApplication ?: return

        val pendingResult = goAsync()

        application.applicationScope.launch {
            try {
                application.reminderManager.syncAll()
            } catch (throwable: Throwable) {
                // Nothing is on screen to report.
                Log.e(TAG, "Failed to reschedule reminders", throwable)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        private const val TAG = "BootReceiver"
    }
}
