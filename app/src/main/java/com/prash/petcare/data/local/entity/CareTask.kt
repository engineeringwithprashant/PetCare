package com.prash.petcare.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A single thing to do for a [Pet] at a particular time, which the user can tick off.
 *
 * A task has **two** parents, and they behave differently:
 *  - [petId] is required. Every task is for exactly one pet, and deleting the pet deletes it.
 *  - [routineId] is optional. A task either came from a repeating [CareRoutine] or was added as
 *    a one-off. Deleting the routine must *not* delete the task history, so that link is broken
 *    rather than cascaded — see the SET_NULL rule below.
 *
 * @property id Primary key, assigned by SQLite on insert.
 * @property petId Foreign key to [Pet.id] — the animal this task is for.
 * @property routineId Foreign key to [CareRoutine.id], or null for a one-off task.
 * @property title Short label such as "Evening meal", shown in lists.
 * @property notes Optional extra detail the user typed.
 * @property category What kind of care this is — see [TaskCategory].
 * @property dueAt When the task should be done, as epoch milliseconds. Indexed, because the
 *   home screen's "what is due today" query sorts and filters on this column constantly.
 * @property isCompleted Whether the user has ticked the task off.
 * @property completedAt When it was ticked off, or null while outstanding. Kept separately from
 *   [isCompleted] so the app can show *when* care actually happened, not just that it did.
 * @property createdAt When the task was created, as epoch milliseconds.
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
    val category: TaskCategory,
    val dueAt: Long,
    val isCompleted: Boolean = false,
    val completedAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis()
)
