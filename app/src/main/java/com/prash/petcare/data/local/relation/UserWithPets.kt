package com.prash.petcare.data.local.relation

import androidx.room.Embedded
import androidx.room.Relation
import com.prash.petcare.data.local.entity.Pet
import com.prash.petcare.data.local.entity.User

/**
 * A [User] together with every [Pet] they own.
 *
 * This is not a table. It is a plain result holder that lets one DAO call return data spanning
 * two tables, instead of the UI fetching a user and then separately fetching their pets.
 *
 * @property user The owner row, pulled in by `@Embedded` — its columns appear directly in the
 *   query result, as though they were declared on this class.
 * @property pets Every pet whose [Pet.ownerId] matches the embedded user's [User.id].
 *   `@Relation` tells Room to run that second query and stitch the results together itself.
 *   A DAO method returning this type must be marked `@Transaction`, so both queries see the
 *   same consistent snapshot of the database.
 */
data class UserWithPets(
    @Embedded
    val user: User,

    @Relation(
        parentColumn = "id",
        entityColumn = "ownerId"
    )
    val pets: List<Pet>
)
