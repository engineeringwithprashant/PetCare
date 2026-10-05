package com.prash.petcare.data.local.relation

import androidx.room.Embedded
import androidx.room.Relation
import com.prash.petcare.data.local.entity.Pet
import com.prash.petcare.data.local.entity.User

/** A [User] together with every [Pet] they own. */
data class UserWithPets(
    @Embedded
    val user: User,

    @Relation(
        parentColumn = "id",
        entityColumn = "ownerId"
    )
    val pets: List<Pet>
)
