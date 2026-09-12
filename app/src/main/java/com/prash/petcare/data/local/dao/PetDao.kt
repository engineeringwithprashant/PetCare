package com.prash.petcare.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import com.prash.petcare.data.local.entity.Pet
import com.prash.petcare.data.local.relation.PetWithCareTasks
import com.prash.petcare.data.local.relation.PetWithExpenses
import kotlinx.coroutines.flow.Flow

/**
 * Database access for the `pets` table.
 *
 * See [UserDao] for the reasoning behind the [Flow] versus `suspend` return types used here.
 */
@Dao
interface PetDao {

    @Insert
    suspend fun insert(pet: Pet): Long

    @Update
    suspend fun update(pet: Pet)

    /**
     * Deletes a pet. The CASCADE rules declared on [com.prash.petcare.data.local.entity.CareTask],
     * [com.prash.petcare.data.local.entity.CareRoutine] and
     * [com.prash.petcare.data.local.entity.Expense] mean SQLite removes that pet's tasks,
     * routines and expenses too — no manual clean-up needed here.
     */
    @Delete
    suspend fun delete(pet: Pet)

    /**
     * Inserts rows that are new and overwrites ones already present, matching on primary key.
     *
     * This is how the sync layer applies pets pulled from Firestore, and the reason it is [Upsert]
     * rather than an `@Insert(onConflict = REPLACE)` is a trap worth knowing about: REPLACE is
     * implemented by SQLite as a delete followed by an insert, so replacing a pet would fire the
     * CASCADE rules on its routines, tasks and expenses and silently wipe them. [Upsert] issues a
     * real UPDATE for a row that already exists, leaving the children alone.
     */
    @Upsert
    suspend fun upsertAll(pets: List<Pet>)

    /** Observes every pet belonging to one owner, newest first, for the home screen list. */
    @Query("SELECT * FROM pets WHERE ownerId = :ownerId ORDER BY createdAt DESC")
    fun observeByOwner(ownerId: Long): Flow<List<Pet>>

    /**
     * One snapshot of an owner's pets, for the sync layer.
     *
     * A `suspend` read rather than a [Flow]: syncing is a job that runs, finishes and stops, so it
     * wants the rows as they are now rather than a subscription that would keep firing while it
     * works.
     */
    @Query("SELECT * FROM pets WHERE ownerId = :ownerId")
    suspend fun getByOwner(ownerId: Long): List<Pet>

    /**
     * The most recent change to any of this owner's pets, or null when they have none.
     *
     * This is the sync layer's "has anything happened?" signal, and it is a single number on
     * purpose. Room invalidates a query by *table*, so observing the pets themselves would re-emit
     * the whole list every time any row was touched, including by the sync writing rows it had just
     * pulled — and each of those emissions would start another sync.
     *
     * A maximum cannot go up unless something was genuinely written with a newer timestamp, so
     * paired with `distinctUntilChanged` it fires for a real edit and stays quiet for everything
     * else. Deletions lower it rather than raise it and so are invisible here; they are picked up
     * from the tombstone queue instead.
     */
    @Query("SELECT MAX(updatedAt) FROM pets WHERE ownerId = :ownerId")
    fun observeLatestUpdateForOwner(ownerId: Long): Flow<Long?>

    /**
     * The ids of pets belonging to somebody else on this device.
     *
     * Sounds odd for a single-user app, but two accounts can exist in one install, and row ids are
     * handed out by SQLite per device. Another person's phone may well have numbered their pet 3,
     * and if account B pulls that pet while account A's pet 3 is sitting in this database, writing
     * it would overwrite A's animal and reassign it to B. The sync excludes these ids so a pull can
     * never reach outside the account it is syncing.
     */
    @Query("SELECT id FROM pets WHERE ownerId != :ownerId")
    suspend fun getIdsNotOwnedBy(ownerId: Long): List<Long>

    /**
     * Marks this owner's pets as uploaded, once Firestore has accepted them.
     *
     * [syncedUpTo] is the moment the sync read the rows it went on to upload. A row edited after
     * that has a later `updatedAt`, is therefore not covered by what was sent, and keeps its flag —
     * which is what stops an edit made *during* a sync from being quietly marked as uploaded when
     * it never was.
     *
     * The redundant-looking `isPendingSync = 1` is doing real work. Room detects changes with SQL
     * triggers, and a trigger only fires for a row the statement actually modifies, so restricting
     * the UPDATE to rows that need it means a sync with nothing to clear touches no rows, fires no
     * trigger, and does not wake every observer of this table for nothing.
     */
    @Query(
        """
        UPDATE pets SET isPendingSync = 0
        WHERE ownerId = :ownerId AND isPendingSync = 1 AND updatedAt <= :syncedUpTo
        """
    )
    suspend fun clearPendingSync(ownerId: Long, syncedUpTo: Long)

    /** How many of this owner's pets are still waiting to be uploaded. */
    @Query("SELECT COUNT(*) FROM pets WHERE ownerId = :ownerId AND isPendingSync = 1")
    fun observePendingSyncCount(ownerId: Long): Flow<Int>

    @Query("SELECT * FROM pets WHERE id = :petId")
    fun observeById(petId: Long): Flow<Pet?>

    @Query("SELECT * FROM pets WHERE id = :petId")
    suspend fun getById(petId: Long): Pet?

    /** Drives the "you have no pets yet" empty state without loading the whole list. */
    @Query("SELECT COUNT(*) FROM pets WHERE ownerId = :ownerId")
    fun observeCountForOwner(ownerId: Long): Flow<Int>

    /** Observes a pet with its full task list — see [PetWithCareTasks]. */
    @Transaction
    @Query("SELECT * FROM pets WHERE id = :petId")
    fun observePetWithCareTasks(petId: Long): Flow<PetWithCareTasks?>

    /** Observes a pet with its full spending history — see [PetWithExpenses]. */
    @Transaction
    @Query("SELECT * FROM pets WHERE id = :petId")
    fun observePetWithExpenses(petId: Long): Flow<PetWithExpenses?>
}
