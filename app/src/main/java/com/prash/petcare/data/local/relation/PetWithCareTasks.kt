package com.prash.petcare.data.local.relation

import androidx.room.Embedded
import androidx.room.Relation
import com.prash.petcare.data.local.entity.CareTask
import com.prash.petcare.data.local.entity.Pet

/**
 * A [Pet] together with all of its [CareTask]s.
 *
 * Used by the pet detail screen, which shows the animal's header information and its task list
 * at the same time and would otherwise need two separate observers kept in sync.
 *
 * @property pet The pet row, whose columns are inlined into the result by `@Embedded`.
 * @property careTasks Every task whose [CareTask.petId] matches this pet's [Pet.id].
 */
data class PetWithCareTasks(
    @Embedded
    val pet: Pet,

    @Relation(
        parentColumn = "id",
        entityColumn = "petId"
    )
    val careTasks: List<CareTask>
)
