package com.prash.petcare.util

import com.prash.petcare.data.local.entity.CareTask
import com.prash.petcare.data.local.entity.Expense
import com.prash.petcare.data.local.entity.Pet
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Turns the signed-in account's pets, care tasks and expenses into a single CSV document — what the
 * profile screen's "Export my records" row produces.
 *
 * **Why one file with a `record_type` column, rather than three files.** The obvious shape is a
 * pets.csv, a tasks.csv and an expenses.csv in a zip. But the point of the export is that the user can
 * open it — in a spreadsheet, on a phone, without unpacking anything — and a zip is a worse answer to
 * that than a single file they can tap. The first column names what each row is, so the three kinds
 * stay separable by sorting or filtering, which is the operation a spreadsheet is best at anyway.
 *
 * It sits in `util` beside the app's other pure helpers rather than in the data layer: it reads no
 * database and holds no state, it is handed rows that a repository has already fetched, and it hands
 * back a string. That is what makes it testable without a device.
 *
 * **Dates are written as ISO-8601 in UTC**, not in the device's locale. A CSV is read by machines at
 * least as often as by people, and "12/09/2026" is genuinely ambiguous between two continents, while
 * "2026-09-12" is not. UTC matches how the app stores these values (see [Pet] on why dates are epoch
 * milliseconds), so the exported text is exactly the stored fact with no time-zone shift invented on
 * the way out.
 */
object RecordsCsv {

    /** The record-type values in the first column. */
    private const val TYPE_PET = "pet"
    private const val TYPE_TASK = "task"
    private const val TYPE_EXPENSE = "expense"

    /**
     * The header row.
     *
     * One set of columns covers all three record types, so a column means different things depending
     * on the row's type — `name` is the pet's name on a pet row and the task's title on a task row.
     * The alternative, a union of every field under its own name, would produce a sheet of thirty
     * mostly-empty columns. Naming the columns generically and letting `record_type` disambiguate is
     * what keeps the file readable.
     */
    private const val HEADER =
        "record_type,id,pet_name,name,category,detail,amount,date"

    /**
     * Builds the whole document.
     *
     * @param pets Every pet on the account.
     * @param tasksByPetId That pet's care tasks, keyed by pet id.
     * @param expensesByPetId That pet's expenses, keyed by pet id.
     * @return the CSV text, or null when there is nothing at all to export — which the caller reports
     *   rather than writing an empty file the user would have to open to discover was empty.
     */
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
                        // When it was last done, which is the fact worth exporting about a task: the
                        // schedule lives on its routine and would not survive being flattened into a
                        // row anyway.
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
                        // The bare number, with no currency symbol and no grouping separators. A
                        // spreadsheet has to be able to add these up, and "Rs 4,665" is text.
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
        // Newline *before* each row rather than after, so the document does not end with a blank line
        // that some spreadsheet importers read as an empty final record.
        append("\n")
        append(
            listOf(type, id.toString(), petName, name, category, detail, amount, formatDate(date))
                .joinToString(",") { escape(it) }
        )
    }

    /**
     * Escapes one field for CSV.
     *
     * The rule from RFC 4180, and each part of it earns its place with real data from this app: a
     * **comma** appears in notes constantly, a **newline** whenever someone writes a multi-line note,
     * and a **double quote** in a name like `Max "Buddy" Sharma`. A field containing any of them is
     * wrapped in quotes, and any quote inside it is doubled.
     *
     * Fields without those characters are left bare, so the common case stays readable in a text
     * editor rather than every cell being needlessly quoted.
     */
    private fun escape(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }

    /**
     * An epoch timestamp as `2026-09-12`, or an empty field when there is none.
     *
     * The formatter is built per call: [SimpleDateFormat] is not thread-safe, and a cached one would
     * be shared across whatever thread the export happens to run on. [Locale.US] fixes the digits to
     * Western Arabic numerals — a formatter following the device's locale would write Devanagari
     * digits on a Nepali phone, which no spreadsheet would parse as a date.
     */
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
