package com.prash.petcare.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** One vaccination a pet has already been given, and when the next dose of it falls due. */
@Entity(
    tableName = "vaccination_records",
    foreignKeys = [
        ForeignKey(
            entity = Pet::class,
            parentColumns = ["id"],
            childColumns = ["petId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    // Every query this table serves is "the records for this pet".
    indices = [Index("petId")]
)
data class VaccinationRecord(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val petId: Long,
    val vaccineName: String,
    val dateGiven: Long,
    val dateDue: Long? = null,
    val clinic: String? = null
)
