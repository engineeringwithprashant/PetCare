package com.prash.petcare.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One item of care within a [CareRoutine] — "Morning meal", "Evening walk", "Brush coat".
 *
 * A task is a **repeating template, not a single dated event**. It says *what* to do and *at what
 * time of day*; the parent routine says *which days* it applies to. One "Morning meal" row
 * therefore covers every day the routine runs, rather than the database filling up with a new row
 * per day.
 *
 * **How "done today" works.** Because the same row reappears each day, a plain done/not-done flag
 * would stay ticked forever. Instead [completedAt] records *when* it was last ticked, and the
 * checklist treats a task as done only if that timestamp falls inside today. Completion therefore
 * clears itself overnight with no scheduled job to reset it.
 *
 * A task has two parents:
 *  - [petId] is required — every task is for exactly one pet, and deleting the pet deletes it.
 *  - [routineId] points at the owning routine. It is nullable only because the foreign key uses
 *    SET_NULL (see below); tasks created through the app always have one.
 *
 * @property id Primary key, assigned by SQLite on insert.
 * @property petId Foreign key to [Pet.id] — the animal this task is for.
 * @property routineId Foreign key to [CareRoutine.id] — the routine this task belongs to.
 * @property title Short label such as "Evening meal", shown in lists.
 * @property notes Optional extra detail the user typed.
 * @property requiredSupplies Optional list of what is needed to carry the task out — food brand,
 *   brush, medication. Kept apart from [notes] so a sitter can see at a glance what to bring.
 * @property category What kind of care this is — see [TaskCategory]. The checklist groups by it.
 * @property timeOfDayMinutes When in the day this happens, as minutes since midnight, so 450 is
 *   07:30. Storing a time of day rather than a full timestamp is what lets one row repeat: a
 *   timestamp would pin the task to a single date. Nullable only so the column could be added to
 *   an existing table without a default; tasks created through the app always set it.
 * @property dueAt Epoch milliseconds of this task's *first* occurrence — the owning routine's
 *   start date at [timeOfDayMinutes]. Later occurrences are derived from the routine's schedule
 *   rather than stored.
 * @property isCompleted Whether the task was ticked off the last time it was actioned. Read
 *   alongside [completedAt], which is what decides whether that tick counts for today.
 * @property completedAt When it was last ticked off, or null if never. This is the field the
 *   checklist actually tests, because it carries the date as well as the fact.
 * @property createdAt When the task was created, as epoch milliseconds.
 * @property updatedAt When this row was last changed, as epoch milliseconds. See [Pet.updatedAt].
 *   Ticking a task off counts as a change, so completing care on one device is what carries that
 *   fact to another.
 * @property isPendingSync True while this row has local changes not yet confirmed as uploaded.
 *   See [Pet.isPendingSync].
 */
@Entity(
    tableName = "care_tasks",
    foreignKeys = [
        ForeignKey(
            entity = Pet::class,
            parentColumns = ["id"],
            childColumns = ["petId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = CareRoutine::class,
            parentColumns = ["id"],
            childColumns = ["routineId"],
            // SET_NULL, not CASCADE: deleting a routine should leave its completed tasks in
            // place as history, simply no longer attached to a schedule. This is why
            // routineId must be a nullable column.
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [Index("petId"), Index("routineId"), Index("dueAt")]
)
data class CareTask(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val petId: Long,
    val routineId: Long? = null,
    val title: String,
    val notes: String? = null,
    val requiredSupplies: String? = null,
    val category: TaskCategory,
    val timeOfDayMinutes: Int? = null,
    val dueAt: Long,
    val isCompleted: Boolean = false,
    val completedAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val isPendingSync: Boolean = false
)
