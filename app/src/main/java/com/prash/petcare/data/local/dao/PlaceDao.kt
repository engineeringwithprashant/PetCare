package com.prash.petcare.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import com.prash.petcare.data.local.entity.Place
import kotlinx.coroutines.flow.Flow

/**
 * Database access for the `places` table.
 *
 * Simpler than the pet-child DAOs ([ExpenseDao], [CareTaskDao]) because a place hangs directly off
 * the account: "this owner's places" is a plain `WHERE ownerId = ...` with no join through `pets`.
 *
 * See [UserDao] for the reasoning behind the [Flow] versus `suspend` return types used here, and
 * [PetDao] for the sync-layer queries, which follow exactly the same pattern.
 */
@Dao
interface PlaceDao {

    @Insert
    suspend fun insert(place: Place): Long

    @Update
    suspend fun update(place: Place)

    @Delete
    suspend fun delete(place: Place)

    /** Applies places pulled from Firestore — see [PetDao.upsertAll] for why [Upsert]. */
    @Upsert
    suspend fun upsertAll(places: List<Place>)

    /**
     * Observes every place an owner has saved.
     *
     * Ordered by name only as a stable starting point. The Places tab re-sorts by pinned state and
     * distance, and distance depends on where the phone is — something SQL cannot know.
     */
    @Query("SELECT * FROM places WHERE ownerId = :ownerId ORDER BY name COLLATE NOCASE")
    fun observeByOwner(ownerId: Long): Flow<List<Place>>

    @Query("SELECT * FROM places WHERE id = :placeId")
    suspend fun getById(placeId: Long): Place?

    /** One snapshot of an owner's places, for the sync layer — see [PetDao.getByOwner]. */
    @Query("SELECT * FROM places WHERE ownerId = :ownerId")
    suspend fun getByOwner(ownerId: Long): List<Place>

    /** The sync layer's change signal for places — see [PetDao.observeLatestUpdateForOwner]. */
    @Query("SELECT MAX(updatedAt) FROM places WHERE ownerId = :ownerId")
    fun observeLatestUpdateForOwner(ownerId: Long): Flow<Long?>

    /** Places belonging to another account on this device — see [PetDao.getIdsNotOwnedBy]. */
    @Query("SELECT id FROM places WHERE ownerId != :ownerId")
    suspend fun getIdsNotOwnedBy(ownerId: Long): List<Long>

    /** Marks this owner's places as uploaded — see [PetDao.clearPendingSync]. */
    @Query(
        """
        UPDATE places SET isPendingSync = 0
        WHERE ownerId = :ownerId AND isPendingSync = 1 AND updatedAt <= :syncedUpTo
        """
    )
    suspend fun clearPendingSync(ownerId: Long, syncedUpTo: Long)

    /** How many of this owner's places are still waiting to be uploaded. */
    @Query("SELECT COUNT(*) FROM places WHERE ownerId = :ownerId AND isPendingSync = 1")
    fun observePendingSyncCount(ownerId: Long): Flow<Int>
}
