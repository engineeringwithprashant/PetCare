package com.prash.petcare.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Money spent on a [Pet] — a vet bill, a bag of food, an insurance premium.
 *
 * Expenses are deliberately independent of [CareTask]. A vet visit may be logged as care, as a
 * cost, or as both, and forcing every expense to attach to a task would stop the user recording
 * a purchase they never scheduled.
 *
 * @property id Primary key, assigned by SQLite on insert.
 * @property petId Foreign key to [Pet.id] — the animal the money was spent on.
 * @property title Short label such as "Annual booster", shown in lists.
 * @property amount The sum spent, in the user's currency.
 * @property category What the money went on — see [ExpenseCategory].
 * @property incurredOn When the money was spent, as epoch milliseconds. This is separate from
 *   [createdAt] because users often enter a receipt days after the purchase, and the spending
 *   reports must group by when it happened, not when it was typed in.
 * @property notes Optional extra detail, e.g. which clinic.
 * @property createdAt When the record was entered, as epoch milliseconds.
 */
@Entity(
    tableName = "expenses",
    foreignKeys = [
        ForeignKey(
            entity = Pet::class,
            parentColumns = ["id"],
            childColumns = ["petId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("petId"), Index("incurredOn")]
)
data class Expense(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val petId: Long,
    val title: String,
    val amount: Double,
    val category: ExpenseCategory,
    val incurredOn: Long,
    val notes: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)
