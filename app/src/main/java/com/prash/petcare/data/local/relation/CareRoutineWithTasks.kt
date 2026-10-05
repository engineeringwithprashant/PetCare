package com.prash.petcare.data.local.relation

import androidx.room.Embedded
import androidx.room.Relation
import com.prash.petcare.data.local.entity.CareRoutine
import com.prash.petcare.data.local.entity.CareTask

/** A [CareRoutine] together with the [CareTask]s it has generated. */
data class CareRoutineWithTasks(
    @Embedded
    val routine: CareRoutine,

    @Relation(
        parentColumn = "id",
        entityColumn = "routineId"
    )
    val careTasks: List<CareTask>
)
