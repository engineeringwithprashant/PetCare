package com.prash.petcare.data.local.relation

import androidx.room.Embedded
import androidx.room.Relation
import com.prash.petcare.data.local.entity.Expense
import com.prash.petcare.data.local.entity.Pet

/** A [Pet] together with every [Expense] recorded against it. */
data class PetWithExpenses(
    @Embedded
    val pet: Pet,

    @Relation(
        parentColumn = "id",
        entityColumn = "petId"
    )
    val expenses: List<Expense>
)
