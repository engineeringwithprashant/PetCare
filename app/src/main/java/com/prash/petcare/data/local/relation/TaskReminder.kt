package com.prash.petcare.data.local.relation

import com.prash.petcare.data.local.entity.CareRoutine
import com.prash.petcare.data.local.entity.CareTask
import com.prash.petcare.data.local.entity.Pet
import com.prash.petcare.data.local.entity.RepeatUnit

/** Everything needed to schedule one care task's reminder and write its notification. */
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
