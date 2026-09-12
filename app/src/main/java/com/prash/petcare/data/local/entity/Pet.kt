package com.prash.petcare.data.local.entity

import androidx.room.ColumnInfo
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
 * @property photoPath Optional absolute path to a photo copied into the app's internal storage.
 *   Only the path is stored — putting image bytes in SQLite would bloat the database badly. The
 *   picked image is copied rather than referenced, because the `content://` URI the photo picker
 *   hands over only grants temporary read access and would stop working after a restart.
 * @property notes Optional free text for anything the fixed fields do not cover.
 * @property dietaryNotes Optional feeding instructions — food brand, portion sizes, meal times.
 * @property allergies Optional known allergies or reactions, kept separate from [dietaryNotes]
 *   because it is safety information a vet or sitter needs to find immediately.
 * @property favouriteToys Optional list of toys and games the pet enjoys.
 * @property createdAt When the pet was added, as epoch milliseconds.
 * @property updatedAt When this row was last changed, as epoch milliseconds. Unlike [createdAt]
 *   this moves every time the pet is edited, and it is what the Firestore sync layer compares to
 *   decide which copy of a row wins — see
 *   [com.prash.petcare.data.sync.SyncManager]. The repository stamps it on every write, so no
 *   screen has to remember to; a row saved without it moving would be invisible to the sync.
 * @property isPendingSync True while this row has local changes that have not been confirmed as
 *   uploaded. Set by the repository alongside [updatedAt] and cleared only once Firestore has
 *   accepted the row, so an edit made with no connection stays marked until it genuinely goes up.
 *
 *   It is what makes "is anything waiting to sync?" answerable **without a network**, which is both
 *   how the home screen can say so while offline and how the app knows what to send the moment
 *   connectivity returns. Deliberately never uploaded — whether a row has been sent is this
 *   device's business and means nothing to another one.
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
    // The column keeps its original name while the Kotlin property is renamed to describe what is
    // actually stored. Renaming the column instead would need the whole table rebuilt, since
    // SQLite only gained RENAME COLUMN in a version newer than this app's minSdk 24 guarantees.
    @ColumnInfo(name = "photoUri")
    val photoPath: String? = null,
    val notes: String? = null,
    val dietaryNotes: String? = null,
    val allergies: String? = null,
    val favouriteToys: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    // Defaults to false so a row rebuilt from a Firestore document is not immediately considered
    // unsent; the repositories set it to true explicitly on every local write.
    val isPendingSync: Boolean = false
)
