package com.prash.petcare.data.local.relation

import androidx.room.Embedded
import androidx.room.Relation
import com.prash.petcare.data.local.entity.Expense
import com.prash.petcare.data.local.entity.Pet

/**
 * A [Pet] together with every [Expense] recorded against it.
 *
 * Used by the spending screen, which needs the pet's name and photo alongside the cost history
 * in a single observable result.
 *
 * @property pet The pet row, inlined into the result by `@Embedded`.
 * @property expenses Every expense whose [Expense.petId] matches this pet's [Pet.id].
 */
data class PetWithExpenses(
    @Embedded
    val pet: Pet,

    @Relation(
        parentColumn = "id",
        entityColumn = "petId"
    )
    val expenses: List<Expense>
)
