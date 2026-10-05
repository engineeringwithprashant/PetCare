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

/** Keeps the set of registered alarms matching the set of care tasks in the database. */
class ReminderManager(
    private val careTaskRepository: CareTaskRepository,
    private val scheduler: ReminderScheduler,
    private val notifier: ReminderNotifier,
    private val settingsStore: SettingsStore
) {

    /** The tasks this process has alarms registered for. */
    private var scheduledTaskIds: Set<Long> = emptySet()

    /** Serialises [applyPlan], so only one re-plan runs at a time. */
    private val planLock = Mutex()

    /** Starts keeping the alarms in step with the database for as long as the process lives. */
    fun startObserving(scope: CoroutineScope) {
        scope.launch {
            // Combined with the switch rather than observed separately.
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

    /** Re-registers every reminder from scratch. */
    suspend fun syncAll() {
        // An empty plan when reminders are switched off, matching [startObserving].
        applyPlan(currentPlan())
    }

    /** The reminders that should currently hold alarms: every task's, or none at all. */
    private suspend fun currentPlan(): List<TaskReminder> =
        if (settingsStore.careRemindersEnabled.value) careTaskRepository.getReminders() else emptyList()

    /** Handles a reminder coming due: show it, then schedule the one after it. */
    suspend fun onAlarmFired(taskId: Long) {
        // A third reason to show nothing, and the newest: the user has switched care reminders off.
        val reminders = currentPlan()
        val reminder = reminders.firstOrNull { it.taskId == taskId }

        if (reminder == null) {
            // The task, its routine or its pet was removed while this alarm was pending.
            Log.d(TAG, "Reminder fired for task $taskId, which no longer exists")
            // Still re-plans the others.
            applyPlan(reminders)
            return
        }

        // The task row itself, which is needed twice over: to decide whether to show anything at all.
        val task = careTaskRepository.getTask(reminder.taskId)

        if (task != null && shouldNotify(reminder, task)) {
            notifier.notifyTaskDue(reminder, task.requiredSupplies, task.notes)
        }

        applyPlan(reminders)
    }

    /** Whether this reminder still deserves to be shown, now that its moment has arrived. */
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

    /** Makes the registered alarms match [reminders]. */
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
