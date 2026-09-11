package com.prash.petcare.data.local.relation

import androidx.room.Embedded
import androidx.room.Relation
import com.prash.petcare.data.local.entity.CareRoutine
import com.prash.petcare.data.local.entity.CareTask

/**
 * A [CareRoutine] together with the [CareTask]s it has generated.
 *
 * Lets the routine detail screen show a schedule and its history in one place — useful for
 * answering "has this month's flea treatment actually been done?".
 *
 * Because [CareTask.routineId] is nullable, this relation only ever returns tasks that are still
 * linked to the routine; one-off tasks and tasks orphaned by a deleted routine are excluded.
 *
 * @property routine The routine row, inlined into the result by `@Embedded`.
 * @property careTasks Every task whose [CareTask.routineId] matches this routine's id.
 */
data class CareRoutineWithTasks(
    @Embedded
    val routine: CareRoutine,

    @Relation(
        parentColumn = "id",
        entityColumn = "routineId"
    )
    val careTasks: List<CareTask>
)
