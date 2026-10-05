package com.prash.petcare.data.local.entity

/** The type of care activity a [CareTask] or [CareRoutine] represents. */
enum class TaskCategory {
    FEEDING,
    WALK,
    GROOMING,
    MEDICATION,
    VET_VISIT,
    TRAINING,
    PLAY,
    OTHER
}
