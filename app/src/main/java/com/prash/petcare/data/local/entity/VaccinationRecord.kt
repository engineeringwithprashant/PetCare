package com.prash.petcare.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One vaccination a pet has already been given, and when the next dose of it falls due.
 *
 * **This is a record of the past, not a reminder.** The row says "this shot was given on this day,
 * at this clinic, and the next one is due then". Everything the vaccination card on the pet detail
 * screen shows about *urgency* — overdue, due today, due in eleven days, up to date — is worked out
 * from [dateDue] against today's date every time the card is drawn. See
 * [com.prash.petcare.ui.pet.VaccinationStatus].
 *
 * **Why the status is derived rather than stored.** A status is a fact about *now*, and a column
 * holding one starts to lie the moment the clock passes midnight: a record saved as "due in 1 day"
 * would still claim that a week later, and nothing would have written to the database to correct it.
 * Deriving it means the card is right on every draw with no scheduled job, no migration when the
 * rules change, and no possibility of two rows disagreeing about what "overdue" means.
 *
 * **Dates are whole calendar days, stored as midnight UTC** — the same convention the rest of the
 * app uses for a date with no time attached (a pet's date of birth, an expense's date). See
 * [com.prash.petcare.ui.common.formatStoredDate] for why the time zone is pinned rather than local.
 *
 * **Not synced to Firestore.** Unlike pets, routines, tasks, expenses and places, this table carries
 * no `updatedAt` or `isPendingSync` and is not listed in
 * [com.prash.petcare.data.sync.SyncManager] — the same position `care_completions` is in. This pass
 * adds the record and the screen that writes it; putting a table on the wire means a remote mapper,
 * a tombstone type and a last-write-wins rule for it, which is a piece of work in its own right and
 * is deliberately not half-done here.
 *
 * @property id Primary key, assigned by SQLite on insert.
 * @property petId Foreign key to [Pet.id] — the animal that had the shot. Deleting the pet deletes
 *   its vaccination history with it (CASCADE below): a record of a dose given to an animal the app
 *   no longer knows about could never be shown or explained.
 * @property vaccineName What was given — "Rabies", "FVRCP (core)", "Leptospirosis". Free text rather
 *   than an enum, because vaccine names differ by country, species and vet; the record screen offers
 *   the common ones as chips and still lets anything be typed.
 * @property dateGiven The day the dose was administered. Required — a vaccination record with no
 *   date is not a record of anything — and it is what the history is sorted by, newest first.
 * @property dateDue When the next dose of this vaccine falls due, or **null for a one-off dose**
 *   that never repeats (a course of deworming, a titre test). Null is the reason the status rule has
 *   a "DONE" case at all: there is nothing outstanding to be early or late for.
 * @property clinic Optional name of the vet or clinic that gave it, shown in the row's meta line.
 *   Nullable because the owner may not remember, and a half-remembered clinic is not worth blocking
 *   the record on.
 */
@Entity(
    tableName = "vaccination_records",
    foreignKeys = [
        ForeignKey(
            entity = Pet::class,
            parentColumns = ["id"],
            childColumns = ["petId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    // Every query this table serves is "the records for this pet", and the cascading delete above
    // runs the same lookup. Without the index both scan the whole table — see the note on [Pet].
    indices = [Index("petId")]
)
data class VaccinationRecord(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val petId: Long,
    val vaccineName: String,
    val dateGiven: Long,
    val dateDue: Long? = null,
    val clinic: String? = null
)
