package com.prash.petcare.data.local.relation

import com.prash.petcare.data.local.entity.CareRoutine
import com.prash.petcare.data.local.entity.CareTask
import com.prash.petcare.data.local.entity.Pet
import com.prash.petcare.data.local.entity.RepeatUnit

/**
 * Everything needed to schedule one care task's reminder and write its notification, gathered from
 * three tables into a single flat row.
 *
 * Unlike the other files in this package this is a **projection**, not a `@Relation`: Room fills it
 * in from a JOIN whose column aliases match these property names, rather than by loading whole
 * entities and stitching them together. That is deliberate. A reminder needs three fields from the
 * task, two from the pet and three from the routine, and nothing else — pulling the full [Pet],
 * [CareTask] and [CareRoutine] rows to reach eight values would read the pet's photo path, notes
 * and allergies on every reschedule for no reason.
 *
 * It also gives the reminder code a value it can compare. The scheduler re-plans whenever the
 * database changes, and because this is a `data class` two identical lists are `equals`, so a
 * change that does not touch any of these fields — a task simply being ticked off — can be
 * recognised and skipped instead of tearing down and re-registering every alarm.
 *
 * The routine's own repeat fields are carried rather than resolved to a date here, because *when*
 * the next occurrence falls depends on the current time and so has to be worked out at the moment
 * of scheduling — see [com.prash.petcare.util.CareSchedule.nextOccurrence].
 *
 * @property taskId The task being reminded about. Doubles as the alarm's and the notification's
 *   identity, so re-scheduling replaces rather than duplicates.
 * @property taskTitle Shown in the notification — "Morning meal".
 * @property timeOfDayMinutes What time of day the task happens, as minutes since midnight. Not
 *   nullable here even though [CareTask.timeOfDayMinutes] is: the query filters out tasks with no
 *   time, because there is no moment to set an alarm for.
 * @property petId Which pet's checklist the notification opens.
 * @property petName Shown in the notification, so a household with several animals can tell at a
 *   glance who the reminder is about.
 * @property routineStartDate The owning routine's first day — the anchor every later occurrence is
 *   counted from.
 * @property repeatInterval How many [repeatUnit]s pass between occurrences.
 * @property repeatUnit The unit that [repeatInterval] counts. Room converts the stored text back
 *   into the enum using the app-wide converters registered on the database.
 */
data class TaskReminder(
    val taskId: Long,
    val taskTitle: String,
    val timeOfDayMinutes: Int,
    val petId: Long,
    val petName: String,
    val routineStartDate: Long,
    val repeatInterval: Int,
    val repeatUnit: RepeatUnit
)
