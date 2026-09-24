package com.prash.petcare.reminder

import android.util.Log
import com.prash.petcare.data.local.SettingsStore
import com.prash.petcare.data.local.entity.CareTask
import com.prash.petcare.data.local.relation.TaskReminder
import com.prash.petcare.data.repository.CareTaskRepository
import com.prash.petcare.util.CareSchedule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Keeps the set of registered alarms matching the set of care tasks in the database.
 *
 * **The one idea in this class.** Rather than trying to remember to add an alarm here and cancel one
 * there each time a task is saved, edited, deleted, paused or has its pet removed, the app treats
 * the alarms as *derived state*: for the current contents of the database, this is the set of alarms
 * that should exist. [applyPlan] makes reality match that, and it is run whenever the data changes.
 * Nothing else in the app has to know reminders exist — a screen that saves a task is finished when
 * the row is written.
 *
 * That matters because there are a surprising number of ways a reminder can become wrong: the task
 * is renamed, its time is changed, its routine is paused or deleted, the pet is deleted and cascades
 * the task away, or the pet is simply renamed and the notification would now name the wrong animal.
 * Hooking every one of those individually would mean six chances to miss one.
 *
 * **Only the next occurrence is ever scheduled**, one alarm per task. When an alarm fires the app is
 * woken, shows the notification, and re-plans — so a daily task holds a single alarm forever rather
 * than the app trying to register one per day into the future.
 *
 * @param careTaskRepository The source of truth for what should be reminded about. Reached through a
 *   repository, never a DAO directly, in keeping with the app's layering.
 * @param scheduler Registers and withdraws the system alarms.
 * @param notifier Posts the notification when one fires.
 * @param settingsStore Read for one value: whether the user has switched care reminders off on the
 *   profile screen. A data-layer object holding no Android view types, so depending on it here is the
 *   same kind of dependency as the repository above. See [applyPlan] for why the switch is applied by
 *   planning an empty list rather than by skipping the planning.
 */
class ReminderManager(
    private val careTaskRepository: CareTaskRepository,
    private val scheduler: ReminderScheduler,
    private val notifier: ReminderNotifier,
    private val settingsStore: SettingsStore
) {

    /**
     * The tasks this process has alarms registered for.
     *
     * Needed to spot *removals*: a task that has been deleted is no longer in the database, so the
     * only way to know its alarm needs cancelling is to remember that one was set.
     *
     * It starts empty in a fresh process, which is correct rather than a gap: the system discards
     * every alarm on reboot, so after a restart there is nothing stale to cancel.
     */
    private var scheduledTaskIds: Set<Long> = emptySet()

    /**
     * Serialises [applyPlan], so only one re-plan runs at a time.
     *
     * Two callers really can arrive together: the observer reacting to a database change, and a
     * broadcast receiver handling an alarm that has just fired. Both read [scheduledTaskIds], work
     * out a new set and write it back, and interleaving those steps would lose one of the two
     * answers — leaving a deleted task's alarm registered with nothing left to remember it by.
     *
     * A [Mutex] rather than `synchronized`: this is coroutine code, and a suspending lock parks the
     * coroutine instead of blocking the thread it happens to be running on.
     */
    private val planLock = Mutex()

    /**
     * Starts keeping the alarms in step with the database for as long as the process lives.
     *
     * This is what removes the need for any screen to think about reminders. Room re-runs the
     * underlying query on every write to the tasks, routines or pets tables, so each of those edits
     * arrives here as a new list and the plan is re-applied.
     *
     * [distinctUntilChanged] is doing real work, not tidying up. Room invalidates by *table*, so
     * simply ticking a task off the checklist re-emits the whole list even though nothing a reminder
     * depends on has changed. Comparing the lists — cheap, since [TaskReminder] is a data class
     * holding only the eight fields that matter — means a morning of ticking boxes does not
     * re-register every alarm in the app.
     *
     * @param scope A process-lifetime scope, supplied by
     *   [com.prash.petcare.PetCareApplication]. Deliberately not a ViewModel's or a fragment's:
     *   reminders must stay correct while no screen is open.
     */
    fun startObserving(scope: CoroutineScope) {
        scope.launch {
            // Combined with the switch rather than observed separately, because the plan depends on
            // both and either can change on its own: adding a task while reminders are off must not
            // register an alarm, and switching them back on must re-register every task that already
            // exists. `combine` re-emits on a change to either side, so both cases arrive here as one
            // more list to apply.
            combine(
                careTaskRepository.observeReminders(),
                settingsStore.careRemindersEnabled
            ) { reminders, enabled ->
                if (enabled) reminders else emptyList()
            }
                .distinctUntilChanged()
                .collect { reminders -> applyPlan(reminders) }
        }
    }

    /**
     * Re-registers every reminder from scratch.
     *
     * Called after a reboot and after an app update, when the system has forgotten the app's
     * alarms — see [BootReceiver].
     */
    suspend fun syncAll() {
        // An empty plan when reminders are switched off, matching [startObserving]. Without this,
        // a reboot would quietly re-register every alarm the user had turned off.
        applyPlan(currentPlan())
    }

    /**
     * The reminders that should currently hold alarms: every task's, or none at all when the user has
     * switched care reminders off on the profile screen.
     *
     * One place answering it, called from [syncAll] and [onAlarmFired], so the switch cannot be
     * honoured on one path and forgotten on the other.
     */
    private suspend fun currentPlan(): List<TaskReminder> =
        if (settingsStore.careRemindersEnabled.value) careTaskRepository.getReminders() else emptyList()

    /**
     * Handles a reminder coming due: show it, then schedule the one after it.
     *
     * Two checks stand between the alarm firing and a notification appearing, and both exist because
     * an alarm is set in advance and the world can move on before it goes off:
     *
     *  - the task may have been **done early** — fed at 07:00 for a 07:30 reminder. Testing that
     *    here rather than when scheduling is deliberate: at scheduling time, hours or days ahead,
     *    the answer is not yet known.
     *  - the routine may no longer **run today** at all. Re-planning normally catches that the
     *    moment the data changes, so this is a backstop for the case where the data changed while
     *    the process was dead and the stale alarm outlived it.
     *
     * Whether or not anything is shown, the following occurrence is scheduled — the chain must not
     * break just because one morning's reminder was unnecessary. That is what the closing
     * [applyPlan] does, re-planning every other task at the same time and reusing the list already
     * read above rather than querying twice. Since [CareSchedule.nextOccurrence] returns a moment
     * strictly after now, the alarm that has just fired cannot be handed straight back, which is
     * what stops this task re-arming itself for the current instant and firing in a loop.
     *
     * @param taskId The task whose reminder has come due.
     */
    suspend fun onAlarmFired(taskId: Long) {
        // A third reason to show nothing, and the newest: the user has switched care reminders off
        // since this alarm was registered. [currentPlan] returns an empty list in that case, so the
        // reminder below is not found, nothing is posted, and the closing [applyPlan] withdraws every
        // remaining alarm — which is exactly what should happen to a plan that is now empty.
        val reminders = currentPlan()
        val reminder = reminders.firstOrNull { it.taskId == taskId }

        if (reminder == null) {
            // The task, its routine or its pet was removed while this alarm was pending. Nothing to
            // show, and nothing to reschedule for it.
            Log.d(TAG, "Reminder fired for task $taskId, which no longer exists")
            // Still re-plans the others: this process may have just been started to deliver this
            // alarm and might have no alarms registered yet.
            applyPlan(reminders)
            return
        }

        // The task row itself, which is needed twice over: to decide whether to show anything at
        // all, and — if so — for the supplies and notes the notification spells out. Read once here
        // rather than once in each place, so the two cannot disagree about what the task says.
        //
        // A null row means the task was deleted between the plan being read above and this line. The
        // reminder is then simply not shown, exactly as an already-done task is not shown.
        val task = careTaskRepository.getTask(reminder.taskId)

        if (task != null && shouldNotify(reminder, task)) {
            notifier.notifyTaskDue(reminder, task.requiredSupplies, task.notes)
        }

        applyPlan(reminders)
    }

    /**
     * Whether this reminder still deserves to be shown, now that its moment has arrived.
     *
     * The task row is passed in rather than carried in the alarm, because what is being asked about
     * — has it been done yet? — can only be answered at this instant, from the row as it stands now.
     */
    private fun shouldNotify(reminder: TaskReminder, task: CareTask): Boolean {
        val todayStart = CareSchedule.startOfToday()
        val todayEnd = CareSchedule.endOfToday()

        val runsToday = CareSchedule.occursOn(
            routineStartDate = reminder.routineStartDate,
            repeatInterval = reminder.repeatInterval,
            repeatUnit = reminder.repeatUnit,
            dayStartMillis = todayStart
        )
        if (!runsToday) return false

        // Already ticked off today — the user does not need telling to do something they have done.
        return !CareSchedule.isDoneOn(task, todayStart, todayEnd)
    }

    /**
     * Makes the registered alarms match [reminders].
     *
     * Each task's next occurrence is worked out and registered; because re-registering replaces
     * rather than duplicates (see [ReminderScheduler.schedule]), this is safe to run as often as
     * needed and always leaves exactly one alarm per task.
     *
     * Tasks that were scheduled before and are absent now have both their alarm and any notification
     * already on screen withdrawn. Cancelling the notification as well matters for a deleted task:
     * its reminder could otherwise still be sitting in the shade, and tapping it would open a
     * checklist the task has gone from.
     *
     * A task whose next occurrence cannot be found — a monthly routine anchored to a 31st, say — is
     * left with no alarm rather than one at an invented time.
     */
    private suspend fun applyPlan(reminders: List<TaskReminder>) = planLock.withLock {
        val now = System.currentTimeMillis()
        val scheduledNow = mutableSetOf<Long>()

        reminders.forEach { reminder ->
            val triggerAt = CareSchedule.nextOccurrence(
                routineStartDate = reminder.routineStartDate,
                repeatInterval = reminder.repeatInterval,
                repeatUnit = reminder.repeatUnit,
                timeOfDayMinutes = reminder.timeOfDayMinutes,
                afterMillis = now
            )

            if (triggerAt == null) {
                Log.d(TAG, "No upcoming occurrence for task ${reminder.taskId}; not scheduling")
                return@forEach
            }

            scheduler.schedule(reminder.taskId, triggerAt)
            scheduledNow += reminder.taskId
        }

        (scheduledTaskIds - scheduledNow).forEach { staleTaskId ->
            scheduler.cancel(staleTaskId)
            notifier.cancel(staleTaskId)
        }

        scheduledTaskIds = scheduledNow
    }

    private companion object {
        private const val TAG = "ReminderManager"
    }
}
