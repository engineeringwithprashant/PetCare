package com.prash.petcare.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** A record that one care task was ticked off on one particular day — the app's completion history. */
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
        // Unique on the pair: one row per task per day. Its first column is taskId.
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
