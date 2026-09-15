package com.prash.petcare.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A record that one care task was ticked off on one particular day — the app's completion *history*.
 *
 * **Why this table exists alongside [CareTask.completedAt].** A care task is a repeating template, not
 * one row per day, so the task itself can only remember the *last* time it was ticked: ticking
 * Monday's walk on Tuesday overwrites Monday's timestamp. That is exactly right for "is it done
 * today?", and exactly wrong for any question about the past — such as the dashboard's care streak,
 * which needs to know whether *every* task due last Friday was done last Friday. A row here is written
 * each time a task is ticked and is never overwritten by a later day, so that history survives.
 *
 * It sits in the data layer like every other entity. Only
 * [com.prash.petcare.data.repository.CareTaskRepository] writes to it, as a side effect of ticking or
 * un-ticking a task, so no screen can forget to keep the history in step with the checklist.
 *
 * **Local only.** It is not mirrored to Firestore: it is derived from ticks the device has seen, and a
 * new device simply starts its history from the day it is signed in. Ticks that *arrive* from the sync
 * are still recorded here — see `CareTaskRepository.upsertTasksFromSync`.
 *
 * @property id Primary key, assigned by SQLite on insert.
 * @property taskId The task that was completed. Deleting the task deletes its history (CASCADE), since
 *   a streak day cannot sensibly count a task that no longer exists.
 * @property petId The pet the task belongs to — stored here as well so a pet's history can be cleared
 *   or queried without joining through `care_tasks`.
 * @property dayStart Local midnight at the start of the day the tick counts for. Together with
 *   [taskId] this is unique: a task is either done on a day or it is not, so ticking it twice on the
 *   same day replaces the row rather than adding a second.
 * @property completedAt The exact moment of the tick, as epoch milliseconds.
 */
@Entity(
    tableName = "care_completions",
    foreignKeys = [
        ForeignKey(
            entity = CareTask::class,
            parentColumns = ["id"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = Pet::class,
            parentColumns = ["id"],
            childColumns = ["petId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        // Unique on the pair: one row per task per day. Its first column is taskId, so it also serves
        // as the index SQLite needs to cascade a task's deletion without scanning the whole table.
        Index(value = ["taskId", "dayStart"], unique = true),
        Index("petId"),
        // The dashboard reads "everything since a week ago", which this makes a range scan.
        Index("completedAt")
    ]
)
data class CareCompletion(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val taskId: Long,
    val petId: Long,
    val dayStart: Long,
    val completedAt: Long
)
