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

/**
 * Builds and posts the notification a care reminder shows.
 *
 * Separate from [ReminderScheduler] because the two answer different questions — that one decides
 * *when* the app is woken, this one decides *what the user sees* — and they are needed at different
 * moments: the scheduler runs whenever the data changes, this only when an alarm actually fires.
 *
 * @param context Application context, for the same reason as in [ReminderScheduler]: this object
 *   outlives every screen, so it must not hold one.
 */
class ReminderNotifier(private val context: Context) {

    private val notificationManager = NotificationManagerCompat.from(context)

    /**
     * Registers the channel every care reminder is posted to.
     *
     * From Android 8 a notification **must** name a channel that already exists, or it is silently
     * dropped — nothing appears and nothing is logged, which makes a missing channel one of the
     * more baffling bugs to chase. Called once from
     * [com.prash.petcare.PetCareApplication.onCreate], before any reminder could fire.
     *
     * Creating the same channel again is a no-op, so this is safe to call on every app start; and
     * [NotificationChannelCompat] means no version check is needed here, since on older devices the
     * call simply does nothing.
     *
     * A channel is how the *user*, not the app, controls these notifications: it gives them one
     * switch for care reminders specifically, which they can silence without turning the app's
     * notifications off altogether. Note that importance is only a default — once the channel
     * exists, whatever the user has chosen wins, and the app cannot override it.
     */
    fun createNotificationChannel() {
        val channel = NotificationChannelCompat
            // DEFAULT importance: makes a sound and appears in the status bar, but does not take
            // over the screen the way HIGH's heads-up banner does. Feeding time is worth a nudge,
            // not an interruption.
            .Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_DEFAULT)
            .setName(context.getString(R.string.reminder_channel_name))
            .setDescription(context.getString(R.string.reminder_channel_description))
            .build()

        notificationManager.createNotificationChannel(channel)
    }

    /**
     * Shows the reminder for one task, naming the pet, what needs doing, what to have to hand and
     * anything the user wrote down about it.
     *
     * The notification's id is the task's own, so a reminder that is somehow posted twice replaces
     * itself rather than leaving the user two identical rows to dismiss.
     *
     * **Why the supplies and notes are parameters rather than fields of [reminder].** That
     * projection is read on every re-plan, which happens on any change to the tasks, routines or
     * pets tables; carrying two free-text columns in it would mean re-registering alarms whenever a
     * note was edited, for text no alarm depends on. This text is only ever needed at the moment one
     * actually fires, and by then [com.prash.petcare.reminder.ReminderManager] has re-read the task
     * row anyway to check it has not already been done. So it hands over what it has already got.
     *
     * @param requiredSupplies What the task needs — food brand, brush, medication — or null/blank if
     *   the user did not say. Listed before the notes because it is the one thing that has to be
     *   fetched *before* starting.
     * @param notes Whatever else the user wrote about the task, or null/blank if nothing.
     * @return true if the notification was posted, false if the user has not granted notification
     *   access. The caller is not expected to do anything about a false — a refused permission is
     *   the user's decision, and the checklist inside the app still works exactly as before.
     */
    fun notifyTaskDue(
        reminder: TaskReminder,
        requiredSupplies: String?,
        notes: String?
    ): Boolean {
        // From Android 13 posting a notification needs the user's permission, and without it
        // notify() is simply ignored. Checking first makes that explicit rather than silent.
        //
        // The check is written out here rather than called through a helper on purpose: Android's
        // lint traces the guard only within the method that posts the notification, and flags the
        // notify() call below as an unchecked permission use if the test sits one call away.
        //
        // `POST_NOTIFICATIONS` only exists from Android 13, so on older devices the version check
        // is what passes — there the permission is granted at install time from the manifest
        // declaration alone. Requesting it is the UI's job; see
        // [com.prash.petcare.ui.task.AddEditTaskFragment], which asks at the moment the user first
        // gives a task a time.
        val isPermitted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

        if (!isPermitted) return false

        val body = buildBody(reminder, requiredSupplies, notes)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            // Pet and task together in the title — "Bella: Morning meal". Both belong there rather
            // than split across the two lines, because a collapsed or grouped notification may show
            // the title alone, and "Morning meal" is no use to a household with three animals.
            .setContentTitle(
                context.getString(
                    R.string.reminder_notification_title,
                    reminder.petName,
                    reminder.taskTitle
                )
            )
            // The whole body goes to both. A collapsed notification has room for one line and shows
            // this one ellipsized; BigTextStyle is what lets the user pull it open and read the
            // supplies and notes in full, which is the only way more than a line of text can ever be
            // seen in the shade. Set unconditionally rather than only when there is extra text: with
            // a single line the expanded form is identical, so the branch would buy nothing.
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(checklistPendingIntent(reminder.petId))
            // Dismisses the notification when it is tapped. Without this it would sit in the
            // shade after the user had already acted on it.
            .setAutoCancel(true)
            // Lets the system treat this as a time-based reminder, which is what governs whether it
            // is allowed through Do Not Disturb and how it is grouped.
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            // The pre-Android 8 equivalent of the channel's importance. Both are set so the
            // notification behaves the same across the versions the app supports.
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        notificationManager.notify(notificationId(reminder.taskId), notification)
        return true
    }

    /**
     * The notification's text: the time it is due, then what to bring, then the user's own note.
     *
     * One line each, in the order they are needed — you check the time, fetch the supplies, then
     * read the instructions while doing it. The labels are the same "Needs:" and "Note:" the
     * delegation message uses (see [com.prash.petcare.ui.checklist.buildDelegationMessage]), so the
     * same task described to the user and described to a sitter reads the same way.
     *
     * Both extras are skipped when blank rather than shown empty: the user is not made to expand a
     * notification to find "Note:" followed by nothing. A blank check, not just a null one, because
     * a text field the user typed into and cleared stores "" rather than null.
     */
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

    /**
     * What happens when the notification is tapped: the app opens on that pet's checklist.
     *
     * Built with [NavDeepLinkBuilder] rather than by hand, so the destination and its `petId`
     * argument are described in the same terms as every other navigation in the app and go through
     * the graph in res/navigation/nav_graph.xml. Constructing an Intent with custom extras and
     * having MainActivity interpret them would put a second, parallel navigation mechanism beside
     * the Navigation Component.
     *
     * The builder synthesises a back stack from the graph's start destination down to the
     * checklist, so pressing back from a notification-opened checklist behaves sensibly instead of
     * dropping the user straight out of the app. One consequence worth being honest about: because
     * that start destination is the sign-in screen, a reminder tapped after a reboot shows the
     * checklist with sign-in *beneath* it rather than in front of it. The data is all local to the
     * device and the checklist is read-only until a box is ticked, so this is a coursework-scale
     * trade-off rather than a hole — a production app would gate the deep link behind a saved
     * session.
     *
     * `createPendingIntent` derives its request code from the destination and arguments, so each
     * pet's notification gets its own pending operation and two pets' reminders cannot overwrite
     * each other's tap target.
     */
    private fun checklistPendingIntent(petId: Long): PendingIntent =
        NavDeepLinkBuilder(context)
            // Named explicitly rather than left to the default lookup of the launcher activity, so
            // this keeps working if the app ever gains a second entry point.
            .setComponentName(MainActivity::class.java)
            .setGraph(R.navigation.nav_graph)
            .setDestination(R.id.checklistFragment)
            // The argument name must match the one the graph declares, which is why it is taken
            // from the fragment's own constant rather than written out again here.
            .setArguments(bundleOf(ChecklistFragment.ARG_PET_ID to petId))
            .createPendingIntent()

    /**
     * The notification id for a task.
     *
     * Notification ids are `Int` and task ids are `Long`, reduced the same way
     * [ReminderScheduler] reduces its request codes. Tying the id to the task is what makes
     * re-posting replace rather than duplicate, and lets [cancel] find a reminder again later.
     */
    private fun notificationId(taskId: Long): Int = (taskId % Int.MAX_VALUE).toInt()

    private companion object {
        /**
         * Channel id. A fixed, never-translated string: it is a key the system stores, not
         * something the user reads, and changing it would orphan the channel — leaving behind the
         * user's settings for the old one and quietly creating a fresh one with defaults.
         */
        private const val CHANNEL_ID = "care_reminders"
    }
}
