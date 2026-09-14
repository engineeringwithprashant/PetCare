package com.prash.petcare.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A geotagged location the user takes their pets to — the vet, the groomer, a park, a pet shop.
 *
 * **Why a place belongs to a [User] rather than to a [Pet].** A vet clinic is shared by every animal
 * in the household; hanging it off one pet would mean saving the same clinic three times for three
 * cats, and deleting the first cat would take the clinic with it. The account is the natural owner.
 *
 * **Why the coordinates are required.** The whole Places tab is built on them: distances are
 * computed from them (see [com.prash.petcare.util.GeoDistance]) and directions are handed to a maps
 * app as a `geo:` URI built from them. A place with no coordinates could do neither, so the form will
 * not save one until a GPS fix has been captured.
 *
 * **Why opening hours are minutes since midnight.** The same representation as
 * [CareTask.timeOfDayMinutes], for the same reason: "opens at 8:00" is a time of day with no date
 * attached, and it repeats every day. Both are nullable because not every place has hours — a public
 * park is simply always open.
 *
 * @property id Primary key, assigned by SQLite on insert.
 * @property ownerId Foreign key to [User.id] — the account this place was saved by.
 * @property name What the place is called, e.g. "Patan Animal Hospital".
 * @property category What kind of place it is — see [PlaceCategory].
 * @property area Optional neighbourhood or address, e.g. "Pulchowk". Shown beside the category.
 * @property latitude The GPS latitude captured when the place was saved, in decimal degrees.
 * @property longitude The GPS longitude captured when the place was saved, in decimal degrees.
 * @property phone Optional phone number, stored as typed so formatting such as dashes survives.
 * @property openingMinutes When it opens, as minutes since midnight, or null if hours are unknown.
 * @property closingMinutes When it closes, as minutes since midnight, or null if hours are unknown.
 *   May be *earlier* than [openingMinutes] for somewhere that stays open past midnight.
 * @property notes Optional free text — "walk-ins before 10:00".
 * @property isPinned Whether the user pinned it. Pinned places are featured first and sort to the top
 *   of the saved list.
 * @property createdAt When the place was saved, as epoch milliseconds.
 * @property updatedAt When this row was last changed — see [Pet.updatedAt].
 * @property isPendingSync True while local changes are not yet confirmed as uploaded — see
 *   [Pet.isPendingSync].
 */
@Entity(
    tableName = "places",
    foreignKeys = [
        ForeignKey(
            entity = User::class,
            parentColumns = ["id"],
            childColumns = ["ownerId"],
            // Deleting an account removes its saved places, exactly as it removes its pets.
            onDelete = ForeignKey.CASCADE
        )
    ],
    // Every query on this table filters by owner, so the owner column is indexed. Room also warns
    // when a foreign key column has no index, because SQLite would otherwise scan the whole table
    // each time a user row is deleted to find the places to cascade.
    indices = [Index("ownerId")]
)
data class Place(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val ownerId: Long,
    val name: String,
    val category: PlaceCategory,
    val area: String? = null,
    val latitude: Double,
    val longitude: Double,
    val phone: String? = null,
    val openingMinutes: Int? = null,
    val closingMinutes: Int? = null,
    val notes: String? = null,
    val isPinned: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val isPendingSync: Boolean = false
)
