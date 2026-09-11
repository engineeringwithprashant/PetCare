package com.prash.petcare.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * An animal belonging to a [User].
 *
 * Pet sits in the middle of the data model: care tasks, care routines and expenses all point at
 * a Pet, so removing one has to clean up everything beneath it — see the `onDelete` rule below.
 *
 * @property id Primary key, assigned by SQLite on insert.
 * @property ownerId Foreign key to [User.id]; this is what makes a Pet "belong to" a User.
 * @property name The pet's name, shown throughout the UI.
 * @property species Fixed list of animal types — see [PetSpecies].
 * @property breed Optional free text, since breed is not meaningful for every species.
 * @property dateOfBirth Optional birth date as epoch milliseconds. Null when the owner does not
 *   know it, which is common for rescue animals.
 * @property weightKg Optional most recent recorded weight, used by the health screens.
 * @property photoUri Optional `content://` URI for a photo the user picked. Only the URI is
 *   stored — copying the image bytes into SQLite would bloat the database badly.
 * @property notes Optional free text for anything the fixed fields above do not cover.
 * @property createdAt When the pet was added, as epoch milliseconds.
 */
@Entity(
    tableName = "pets",
    foreignKeys = [
        ForeignKey(
            entity = User::class,
            parentColumns = ["id"],
            childColumns = ["ownerId"],
            // Deleting an account deletes that account's pets. Without CASCADE the pets would
            // survive as orphans pointing at a user row that no longer exists.
            onDelete = ForeignKey.CASCADE
        )
    ],
    // Room warns about un-indexed foreign keys for good reason: without this index every
    // "pets for this user" query scans the whole table, and so does every cascading delete.
    indices = [Index("ownerId")]
)
data class Pet(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val ownerId: Long,
    val name: String,
    val species: PetSpecies,
    val breed: String? = null,
    val dateOfBirth: Long? = null,
    val weightKg: Double? = null,
    val photoUri: String? = null,
    val notes: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)
