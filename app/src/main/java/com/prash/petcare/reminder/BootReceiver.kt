package com.prash.petcare.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.prash.petcare.PetCareApplication
import kotlinx.coroutines.launch

/**
 * Puts the care reminders back after an event that invalidates every registered alarm.
 *
 * **Why this is necessary.** Alarms registered with [android.app.AlarmManager] live in system
 * memory, not on disk, so the operating system forgets all of them when the device restarts. Without
 * this receiver a user who rebooted their phone would simply stop being reminded — silently, with
 * the app still looking perfectly correct, since the tasks and routines are all still in the
 * database. It is the classic AlarmManager trap.
 *
 * Four broadcasts are handled, all for the same reason — the alarms no longer reflect the data:
 *  - `BOOT_COMPLETED`: the device restarted and every alarm is gone.
 *  - `MY_PACKAGE_REPLACED`: the app was updated, which also clears its alarms. Sent only to the app
 *    that was replaced.
 *  - `TIMEZONE_CHANGED` and `TIME_SET`: the alarms are still registered, but they were calculated
 *    for wall-clock times in the *old* zone or against the old clock. A task due at 07:30 must mean
 *    07:30 wherever the user has flown to, so the times are worked out again.
 *
 * Like [ReminderReceiver] this holds no logic of its own; it exists to be an entry point the system
 * can start the app through, and hands over to [ReminderManager.syncAll].
 *
 * `BOOT_COMPLETED` requires the `RECEIVE_BOOT_COMPLETED` permission, declared in the manifest, and
 * the receiver must be `exported="true"` there because the broadcast comes from outside the app.
 */
class BootReceiver : BroadcastReceiver() {

    /**
     * See [ReminderReceiver.onReceive] for why [goAsync] is used: this runs on the main thread with
     * only seconds to live, and re-planning the alarms needs a database read.
     *
     * At boot this is often the very first thing to run in the app's process, so
     * [PetCareApplication] has just been created — which means its own observer is starting up at the
     * same time and would eventually schedule these alarms too. The explicit call is kept because
     * "eventually" is not good enough for a receiver that the system may stop the process at the end
     * of, and because a boot receiver whose only effect is a side effect of process creation would be
     * an awkward thing for the next reader to trust. Re-planning is idempotent, so the two
     * overlapping does no harm.
     */
    override fun onReceive(context: Context, intent: Intent) {
        // The action is logged rather than branched on, because the response to all four is the
        // same: work the reminders out again from the database as it stands now.
        Log.d(TAG, "Rescheduling care reminders after ${intent.action}")

        val application = context.applicationContext as? PetCareApplication ?: return

        val pendingResult = goAsync()

        application.applicationScope.launch {
            try {
                application.reminderManager.syncAll()
            } catch (throwable: Throwable) {
                // Nothing is on screen to report to, and crashing the process during boot would be
                // the worst possible moment for it.
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
