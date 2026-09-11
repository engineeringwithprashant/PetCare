package com.prash.petcare.data.local.entity

/**
 * The type of care activity a [CareTask] or [CareRoutine] represents.
 *
 * Both entities share this enum on purpose: a routine is a template that generates tasks, so a
 * MEDICATION routine should produce MEDICATION tasks. Sharing one type keeps those in step and
 * lets both screens reuse the same category filter chips.
 *
 * Room cannot store an enum directly, so
 * [com.prash.petcare.data.local.converter.Converters] writes each constant as its [name] String.
 */
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
