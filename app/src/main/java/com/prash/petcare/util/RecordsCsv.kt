package com.prash.petcare.util

import com.prash.petcare.data.local.entity.CareTask
import com.prash.petcare.data.local.entity.Expense
import com.prash.petcare.data.local.entity.Pet
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Turns the signed-in account's pets, care tasks and expenses into a single CSV document. */
object RecordsCsv {

    /** The record-type values in the first column. */
    private const val TYPE_PET = "pet"
    private const val TYPE_TASK = "task"
    private const val TYPE_EXPENSE = "expense"

    /** The header row. */
    private const val HEADER =
        "record_type,id,pet_name,name,category,detail,amount,date"

    /** Builds the whole document. */
    fun build(
        pets: List<Pet>,
        tasksByPetId: Map<Long, List<CareTask>>,
        expensesByPetId: Map<Long, List<Expense>>
    ): String? {
        if (pets.isEmpty()) return null

        return buildString {
            append(HEADER)

            pets.forEach { pet ->
                appendRow(
                    type = TYPE_PET,
                    id = pet.id,
                    petName = pet.name,
                    name = pet.name,
                    category = pet.species.name,
                    detail = pet.breed.orEmpty(),
                    amount = pet.weightKg?.toString().orEmpty(),
                    date = pet.dateOfBirth
                )

                tasksByPetId[pet.id].orEmpty().forEach { task ->
                    appendRow(
                        type = TYPE_TASK,
                        id = task.id,
                        petName = pet.name,
                        name = task.title,
                        category = task.category.name,
                        detail = task.notes.orEmpty(),
                        amount = "",
                        // When it was last done, which is the fact worth exporting about a task.
                        date = task.completedAt
                    )
                }

                expensesByPetId[pet.id].orEmpty().forEach { expense ->
                    appendRow(
                        type = TYPE_EXPENSE,
                        id = expense.id,
                        petName = pet.name,
                        name = expense.title,
                        category = expense.category.name,
                        detail = expense.notes.orEmpty(),
                        // The bare number, with no currency symbol and no grouping separators.
                        amount = expense.amount.toString(),
                        date = expense.incurredOn
                    )
                }
            }
        }
    }

    /** Writes one row, escaping every field. */
    private fun StringBuilder.appendRow(
        type: String,
        id: Long,
        petName: String,
        name: String,
        category: String,
        detail: String,
        amount: String,
        date: Long?
    ) {
        // Newline before each row rather.
        append("\n")
        append(
            listOf(type, id.toString(), petName, name, category, detail, amount, formatDate(date))
                .joinToString(",") { escape(it) }
        )
    }

    /** Escapes one field for CSV. */
    private fun escape(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }

    /** An epoch timestamp as `2026-09-12`, or an empty field when there is none. */
    private fun formatDate(millis: Long?): String {
        if (millis == null) return ""

        return SimpleDateFormat("yyyy-MM-dd", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(millis))
    }

    /** The date stamped into the suggested file name, so two exports do not collide. */
    fun fileNameDate(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(millis))
}
