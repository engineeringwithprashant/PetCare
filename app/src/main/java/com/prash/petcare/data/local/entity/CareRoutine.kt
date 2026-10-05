package com.prash.petcare.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** A repeating care schedule for a [Pet] — "walk Bella every morning", "flea treatment monthly". */
@Entity(
    tableName = "care_routines",
    foreignKeys = [
        ForeignKey(
            entity = Pet::class,
            parentColumns = ["id"],
            childColumns = ["petId"],
            // Removing a pet removes its routines; a schedule for a pet that is gone is meaningless.
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("petId")]
)
data class CareRoutine(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val petId: Long,
    val title: String,
    val description: String? = null,
    val category: TaskCategory = TaskCategory.OTHER,
    val repeatInterval: Int = 1,
    val repeatUnit: RepeatUnit = RepeatUnit.DAY,
    val startDate: Long,
    val isActive: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val isPendingSync: Boolean = false
)
