package com.prash.petcare.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** An animal belonging to a [User]. */
@Entity(
    tableName = "pets",
    foreignKeys = [
        ForeignKey(
            entity = User::class,
            parentColumns = ["id"],
            childColumns = ["ownerId"],
            // Deleting an account deletes that account's pets. Without CASCADE the pets would survive as orphans.
            onDelete = ForeignKey.CASCADE
        )
    ],
    // Room warns about un-indexed foreign keys for good reason.
    indices = [Index("ownerId")]
)
data class Pet(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val ownerId: Long,
    val name: String,
    val species: PetSpecies,
    // Defaulted so every existing `Pet(...)` call.
    val gender: PetGender = PetGender.UNKNOWN,
    val breed: String? = null,
    val dateOfBirth: Long? = null,
    val weightKg: Double? = null,
    // The column keeps its original name while the Kotlin property is renamed to describe what is actually stored.
    @ColumnInfo(name = "photoUri")
    val photoPath: String? = null,
    val notes: String? = null,
    val dietaryNotes: String? = null,
    val allergies: String? = null,
    val favouriteToys: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    // Defaults to false so a row rebuilt from a Firestore document is not immediately considered unsent.
    val isPendingSync: Boolean = false
)
