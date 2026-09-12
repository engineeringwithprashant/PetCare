package com.prash.petcare.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A repeating care schedule for a [Pet] — "walk Bella every morning", "flea treatment monthly".
 *
 * A routine is a *template*, not something that happens on its own. The app turns a routine into
 * real, tickable [CareTask] rows, and each generated task keeps a link back here through
 * [CareTask.routineId]. Separating the two means a user can complete or skip one Tuesday's walk
 * without disturbing the schedule itself.
 *
 * The schedule is described by [repeatInterval] and [repeatUnit] together: `2` + `WEEK` means
 * every two weeks. See [RepeatUnit] for why it is split that way.
 *
 * @property id Primary key, assigned by SQLite on insert.
 * @property petId Foreign key to [Pet.id] — the animal this routine cares for.
 * @property title Short label such as "Morning walk", shown in lists.
 * @property description Optional longer explanation, e.g. dosage instructions.
 * @property category Broad kind of care, defaulted and not currently set by the routine form.
 *   Each [CareTask] carries its own category and that is what the checklist groups by, so a
 *   routine covering a mix of feeding and grooming does not need to claim a single one.
 * @property repeatInterval How many [repeatUnit]s pass between occurrences; must be at least 1.
 * @property repeatUnit The unit that [repeatInterval] counts.
 * @property startDate The first occurrence, as epoch milliseconds. Later due dates are worked
 *   out by stepping forward from here.
 * @property isActive Whether the routine still generates tasks. Pausing sets this to false
 *   instead of deleting the row, so previously generated tasks keep their history intact.
 * @property createdAt When the routine was created, as epoch milliseconds.
 * @property updatedAt When this row was last changed, as epoch milliseconds. See [Pet.updatedAt] —
 *   it exists for the same reason and is stamped the same way.
 * @property isPendingSync True while this row has local changes not yet confirmed as uploaded.
 *   See [Pet.isPendingSync].
 */
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
