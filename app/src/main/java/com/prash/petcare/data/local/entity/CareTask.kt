package com.prash.petcare.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** One item of care within a [CareRoutine] — "Morning meal", "Evening walk", "Brush coat". */
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
            // SET_NULL, not CASCADE: deleting a routine should leave its completed tasks in place as history.
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
