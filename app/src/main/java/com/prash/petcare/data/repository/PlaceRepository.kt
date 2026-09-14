package com.prash.petcare.data.repository

import com.prash.petcare.data.local.dao.PlaceDao
import com.prash.petcare.data.local.dao.SyncDeletionDao
import com.prash.petcare.data.local.entity.Place
import com.prash.petcare.data.local.entity.SyncDeletion
import com.prash.petcare.data.local.entity.SyncEntityType
import kotlinx.coroutines.flow.Flow

/**
 * Supplies saved places to the ViewModel layer.
 *
 * See [UserRepository] for why this layer exists and how it fits the MVVM chain: the Places screens'
 * ViewModels talk to this class and never to [PlaceDao], so how places are stored could change — a
 * network source added, a cache put in front — without either screen noticing.
 *
 * Like the other synced repositories it does two pieces of bookkeeping on every write, and that is
 * why they live here rather than in the DAO or the ViewModel: every write *must* stamp `updatedAt`
 * and set `isPendingSync`, and every delete *must* leave a tombstone. Putting those rules in the one
 * layer all writes pass through means no screen can forget them. See [PetRepository] for the longer
 * explanation.
 *
 * @property placeDao Injected through the constructor so tests can substitute a fake.
 * @property syncDeletionDao Where deletions are recorded for the sync layer to send on.
 */
class PlaceRepository(
    private val placeDao: PlaceDao,
    private val syncDeletionDao: SyncDeletionDao
) {

    /** Observes every place an owner has saved. */
    fun observePlacesForOwner(ownerId: Long): Flow<List<Place>> = placeDao.observeByOwner(ownerId)

    suspend fun getPlace(placeId: Long): Place? = placeDao.getById(placeId)

    suspend fun addPlace(place: Place): Long = placeDao.insert(
        place.copy(updatedAt = System.currentTimeMillis(), isPendingSync = true)
    )

    suspend fun updatePlace(place: Place) = placeDao.update(
        place.copy(updatedAt = System.currentTimeMillis(), isPendingSync = true)
    )

    /**
     * Pins or unpins a place.
     *
     * Implemented as an ordinary update of the whole row rather than a one-column `UPDATE` query, so
     * that the timestamp and pending flag are stamped by the same code path as every other edit —
     * pinning on one device is a change that should reach the others.
     */
    suspend fun setPinned(place: Place, isPinned: Boolean) = updatePlace(place.copy(isPinned = isPinned))

    /** Deletes a place, recording a tombstone first so the deletion can be synced. */
    suspend fun deletePlace(place: Place) {
        syncDeletionDao.insert(
            SyncDeletion(entityType = SyncEntityType.PLACE, entityId = place.id)
        )
        placeDao.delete(place)
    }

    // -------------------------------------------------------------------------------------------
    // Used by the sync layer — see the equivalent section of PetRepository.
    // -------------------------------------------------------------------------------------------

    /** Every place belonging to an owner, as a one-off read. */
    suspend fun getPlacesForOwner(ownerId: Long): List<Place> = placeDao.getByOwner(ownerId)

    /** The sync layer's change signal — see [PlaceDao.observeLatestUpdateForOwner]. */
    fun observeLatestPlaceUpdate(ownerId: Long): Flow<Long?> =
        placeDao.observeLatestUpdateForOwner(ownerId)

    /** Place ids another account on this device owns — see [PlaceDao.getIdsNotOwnedBy]. */
    suspend fun getPlaceIdsNotOwnedBy(ownerId: Long): Set<Long> =
        placeDao.getIdsNotOwnedBy(ownerId).toSet()

    /** Marks places as uploaded once Firestore has them — see [PlaceDao.clearPendingSync]. */
    suspend fun clearPendingPlaceSync(ownerId: Long, syncedUpTo: Long) =
        placeDao.clearPendingSync(ownerId, syncedUpTo)

    /** How many places are still waiting to be uploaded, for the sync indicator. */
    fun observePendingPlaceCount(ownerId: Long): Flow<Int> = placeDao.observePendingSyncCount(ownerId)

    /** Writes places pulled from Firestore into Room, keeping the timestamps they arrived with. */
    suspend fun upsertPlacesFromSync(places: List<Place>) = placeDao.upsertAll(places)
}
