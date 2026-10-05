package com.prash.petcare.data.local.relation

import androidx.room.Embedded
import androidx.room.Relation
import com.prash.petcare.data.local.entity.CareTask
import com.prash.petcare.data.local.entity.Pet

/** A [Pet] together with all of its [CareTask]s. */
data class PetWithCareTasks(
    @Embedded
    val pet: Pet,

    @Relation(
        parentColumn = "id",
        entityColumn = "petId"
    )
    val careTasks: List<CareTask>
)
