package com.prash.petcare.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** A geotagged location the user takes their pets to — the vet, the groomer, a park, a pet shop. */
@Entity(
    tableName = "places",
    foreignKeys = [
        ForeignKey(
            entity = User::class,
            parentColumns = ["id"],
            childColumns = ["ownerId"],
            // Deleting an account removes its saved places, exactly as it removes its pets.
            onDelete = ForeignKey.CASCADE
        )
    ],
    // Every query on this table filters by owner, so the owner column is indexed.
    indices = [Index("ownerId")]
)
data class Place(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val ownerId: Long,
    val name: String,
    val category: PlaceCategory,
    val area: String? = null,
    val latitude: Double,
    val longitude: Double,
    val phone: String? = null,
    val openingMinutes: Int? = null,
    val closingMinutes: Int? = null,
    val notes: String? = null,
    val isPinned: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val isPendingSync: Boolean = false
)
