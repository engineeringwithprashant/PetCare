package com.prash.petcare.data.sync

import android.util.Log
import com.prash.petcare.data.local.entity.CareRoutine
import com.prash.petcare.data.local.entity.CareTask
import com.prash.petcare.data.local.entity.Expense
import com.prash.petcare.data.local.entity.Pet
import com.prash.petcare.data.local.entity.Place
import com.prash.petcare.data.local.entity.SyncEntityType
import com.prash.petcare.data.local.entity.User
import com.prash.petcare.data.remote.FirestoreSyncService
import com.prash.petcare.data.remote.careRoutineFromRemote
import com.prash.petcare.data.remote.careTaskFromRemote
import com.prash.petcare.data.remote.expenseFromRemote
import com.prash.petcare.data.remote.petFromRemote
import com.prash.petcare.data.remote.placeFromRemote
import com.prash.petcare.data.remote.toRemoteMap
import com.prash.petcare.data.repository.CareRoutineRepository
import com.prash.petcare.data.repository.CareTaskRepository
import com.prash.petcare.data.repository.ExpenseRepository
import com.prash.petcare.data.repository.PetRepository
import com.prash.petcare.data.repository.PlaceRepository
import com.prash.petcare.data.repository.SyncDeletionRepository
import com.prash.petcare.data.repository.UserRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** Keeps one account's data mirrored between Room and Firestore. */
class SyncManager(
    private val userRepository: UserRepository,
    private val petRepository: PetRepository,
    private val careRoutineRepository: CareRoutineRepository,
    private val careTaskRepository: CareTaskRepository,
    private val expenseRepository: ExpenseRepository,
    private val placeRepository: PlaceRepository,
    private val syncDeletionRepository: SyncDeletionRepository,
    private val firestoreSyncService: FirestoreSyncService,
    private val networkMonitor: NetworkMonitor
) {

    private val _status = MutableStateFlow<SyncStatus>(SyncStatus.Idle)

    /** What the sync is currently doing, for the home screen's indicator. */
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    /** True while a cycle is in flight. One of the three inputs to [status]. */
    private val isSyncing = MutableStateFlow(false)

    /** What the last cycle achieved. */
    private data class CycleOutcome(
        val lastSuccessAt: Long? = null,
        val lastAttemptFailed: Boolean = false
    )

    private val cycleOutcome = MutableStateFlow(CycleOutcome())

    /** Which account is signed in, or null before anyone has signed in. */
    private val currentUserId = MutableStateFlow<Long?>(null)

    /** Serialises [runSyncCycle], so only one is ever in flight. */
    private val syncLock = Mutex()

    /** Records who is signed in, which is what starts syncing for that account. */
    fun onUserSignedIn(userId: Long) {
        currentUserId.value = userId
    }

    /** Starts syncing, and keeps syncing whenever the account's data changes, for as long as the process lives. */
    // flatMapLatest and debounce are both still marked as unstable by the coroutines library.
    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    fun startObserving(scope: CoroutineScope) {
        // Trigger 1: the data changed.
        scope.launch {
            currentUserId
                .filterNotNull()
                .flatMapLatest { userId -> observeLocalChanges(userId) }
                .debounce(CHANGE_DEBOUNCE_MILLIS)
                .collect { userId -> runSyncCycle(userId) }
        }

        // Trigger 2: the device came back online.
        scope.launch { syncOnReconnect() }

        // Not a trigger: keeps the published status in step with what is happening.
        scope.launch { publishStatus() }
    }

    /** Runs a sync whenever connectivity returns. */
    private suspend fun syncOnReconnect() {
        networkMonitor.observeIsOnline()
            .drop(1)
            .filter { isOnline -> isOnline }
            .collect {
                val userId = currentUserId.value ?: return@collect
                Log.d(TAG, "Connectivity returned; syncing pending changes")
                runSyncCycle(userId)
            }
    }

    /** Keeps [status] describing the truth, from three independent facts. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun publishStatus() {
        currentUserId
            .filterNotNull()
            .flatMapLatest { userId ->
                combine(
                    isSyncing,
                    cycleOutcome,
                    observePendingCount(userId)
                ) { syncing, outcome, pendingCount ->
                    statusFor(syncing, outcome, pendingCount)
                }
            }
            .collect { _status.value = it }
    }

    /** How many records are waiting to be uploaded, across everything that can be queued. */
    private fun observePendingCount(userId: Long) = combine(
        listOf(
            petRepository.observePendingPetCount(userId),
            careRoutineRepository.observePendingRoutineCount(userId),
            careTaskRepository.observePendingTaskCount(userId),
            expenseRepository.observePendingExpenseCount(userId),
            placeRepository.observePendingPlaceCount(userId),
            syncDeletionRepository.observePendingCount()
        )
    ) { counts -> counts.sum() }

    /** Turns the three facts into the one thing the user sees. */
    private fun statusFor(
        syncing: Boolean,
        outcome: CycleOutcome,
        pendingCount: Int
    ): SyncStatus = when {
        syncing -> SyncStatus.Syncing
        pendingCount > 0 -> SyncStatus.Pending(pendingCount)
        outcome.lastSuccessAt != null -> SyncStatus.Synced(outcome.lastSuccessAt)
        outcome.lastAttemptFailed -> SyncStatus.Offline
        // Signed in, nothing queued, nothing attempted yet — the brief moment before the first cycle finishes.
        else -> SyncStatus.Idle
    }

    /** Emits the signed-in user's id whenever anything of theirs changes locally. */
    private fun observeLocalChanges(userId: Long) = combine(
        listOf<Flow<Any?>>(
            petRepository.observeLatestPetUpdate(userId),
            careRoutineRepository.observeLatestRoutineUpdate(userId),
            careTaskRepository.observeLatestTaskUpdate(userId),
            expenseRepository.observeLatestExpenseUpdate(userId),
            placeRepository.observeLatestPlaceUpdate(userId),
            syncDeletionRepository.observePendingCount()
        )
    ) { snapshot ->
        // The values are combined only to be compared; what is emitted is simply "this user's data is at this state".
        snapshot.toList()
    }
        .distinctUntilChanged()
        .map { userId }

    /** Runs one full sync for [userId], and reports how it went. */
    private suspend fun runSyncCycle(userId: Long) = syncLock.withLock {
        val user = userRepository.getUser(userId) ?: return@withLock
        val userKey = remoteKeyFor(user) ?: return@withLock

        isSyncing.value = true

        val succeeded = withTimeoutOrNull(SYNC_TIMEOUT_MILLIS) {
            try {
                pushPendingDeletions(userKey)
                syncPets(userKey, userId)
                syncRoutines(userKey, userId)
                syncTasks(userKey, userId)
                syncExpenses(userKey, userId)
                syncPlaces(userKey, userId)
                writeAccountDocument(userKey, user)
                true
            } catch (e: CancellationException) {
                // Never swallowed. A CancellationException is how both the timeout above and the scope being torn down arrive here.
                throw e
            } catch (e: Exception) {
                // Everything else is treated the same way and deliberately kept quiet: offline is the ordinary case.
                Log.d(TAG, "Sync failed, keeping local data queued: ${e.message}")
                false
            }
        } ?: false

        // Recorded rather than published directly: only statusFor sees both the queue and this cycle.
        isSyncing.value = false
        cycleOutcome.value = if (succeeded) {
            CycleOutcome(lastSuccessAt = System.currentTimeMillis(), lastAttemptFailed = false)
        } else {
            cycleOutcome.value.copy(lastAttemptFailed = true)
        }
    }

    // Step 1: deletions.

    /** Removes from Firestore everything that has been deleted on this device. */
    private suspend fun pushPendingDeletions(userKey: String) {
        val deletions = syncDeletionRepository.getPendingDeletions()
        if (deletions.isEmpty()) return

        deletions.groupBy { it.entityType }.forEach { (type, group) ->
            firestoreSyncService.deleteDocuments(
                userKey = userKey,
                collection = FirestoreSyncService.collectionFor(type),
                documentIds = group.map { it.entityId.toString() }
            )

            if (type == SyncEntityType.PET) {
                group.forEach { firestoreSyncService.deleteDocumentsForPet(userKey, it.entityId) }
            }
        }

        syncDeletionRepository.clearDeletions(deletions.map { it.id })
    }

    // Step 2: the five synced tables.

    /** Reconciles pets in both directions. */
    private suspend fun syncPets(userKey: String, userId: Long) {
        val otherAccountsPetIds = petRepository.getPetIdsNotOwnedBy(userId)

        val remote = firestoreSyncService
            .fetchCollection(userKey, FirestoreSyncService.COLLECTION_PETS)
            .mapNotNull { petFromRemote(it, userId) }
            .filterNot { it.id in otherAccountsPetIds }

        // Captured before the rows are read.
        val readAt = System.currentTimeMillis()
        val local = petRepository.getPetsForOwner(userId)
        val localById = local.associateBy { it.id }

        val (toApplyLocally, toPushRemotely) = reconcile(
            local = local,
            remote = remote,
            idOf = Pet::id,
            updatedAtOf = Pet::updatedAt,
            isPendingOf = Pet::isPendingSync
        )

        applyLocally(toApplyLocally.map { it.copy(photoPath = localById[it.id]?.photoPath) }) {
            petRepository.upsertPetsFromSync(it)
        }

        pushRemotely(userKey, FirestoreSyncService.COLLECTION_PETS, toPushRemotely, Pet::id) {
            it.toRemoteMap()
        }

        petRepository.clearPendingPetSync(userId, readAt)
    }

    /** Reconciles routines, skipping any whose pet this device does not have. */
    private suspend fun syncRoutines(userKey: String, userId: Long) {
        val knownPetIds = petRepository.getPetsForOwner(userId).map { it.id }.toSet()
        val otherAccountsRoutineIds = careRoutineRepository.getRoutineIdsNotOwnedBy(userId)

        val remote = firestoreSyncService
            .fetchCollection(userKey, FirestoreSyncService.COLLECTION_ROUTINES)
            .mapNotNull { careRoutineFromRemote(it) }
            .filter { it.petId in knownPetIds }
            .filterNot { it.id in otherAccountsRoutineIds }

        val readAt = System.currentTimeMillis()
        val local = careRoutineRepository.getRoutinesForOwner(userId)

        val (toApplyLocally, toPushRemotely) = reconcile(
            local = local,
            remote = remote,
            idOf = CareRoutine::id,
            updatedAtOf = CareRoutine::updatedAt,
            isPendingOf = CareRoutine::isPendingSync
        )

        applyLocally(toApplyLocally) { careRoutineRepository.upsertRoutinesFromSync(it) }

        pushRemotely(
            userKey,
            FirestoreSyncService.COLLECTION_ROUTINES,
            toPushRemotely,
            CareRoutine::id
        ) { it.toRemoteMap() }

        careRoutineRepository.clearPendingRoutineSync(userId, readAt)
    }

    /** Reconciles tasks, skipping any whose pet — or whose routine, when it names one — is missing. */
    private suspend fun syncTasks(userKey: String, userId: Long) {
        val knownPetIds = petRepository.getPetsForOwner(userId).map { it.id }.toSet()
        val knownRoutineIds = careRoutineRepository.getRoutinesForOwner(userId).map { it.id }.toSet()
        val otherAccountsTaskIds = careTaskRepository.getTaskIdsNotOwnedBy(userId)

        val remote = firestoreSyncService
            .fetchCollection(userKey, FirestoreSyncService.COLLECTION_TASKS)
            .mapNotNull { careTaskFromRemote(it) }
            .filter { it.petId in knownPetIds }
            .filter { task -> task.routineId == null || task.routineId in knownRoutineIds }
            .filterNot { it.id in otherAccountsTaskIds }

        val readAt = System.currentTimeMillis()
        val local = careTaskRepository.getTasksForOwner(userId)

        val (toApplyLocally, toPushRemotely) = reconcile(
            local = local,
            remote = remote,
            idOf = CareTask::id,
            updatedAtOf = CareTask::updatedAt,
            isPendingOf = CareTask::isPendingSync
        )

        applyLocally(toApplyLocally) { careTaskRepository.upsertTasksFromSync(it) }

        pushRemotely(
            userKey,
            FirestoreSyncService.COLLECTION_TASKS,
            toPushRemotely,
            CareTask::id
        ) { it.toRemoteMap() }

        careTaskRepository.clearPendingTaskSync(userId, readAt)
    }

    /** Reconciles expenses, skipping any whose pet is missing — see [syncRoutines]. */
    private suspend fun syncExpenses(userKey: String, userId: Long) {
        val knownPetIds = petRepository.getPetsForOwner(userId).map { it.id }.toSet()
        val otherAccountsExpenseIds = expenseRepository.getExpenseIdsNotOwnedBy(userId)

        val remote = firestoreSyncService
            .fetchCollection(userKey, FirestoreSyncService.COLLECTION_EXPENSES)
            .mapNotNull { expenseFromRemote(it) }
            .filter { it.petId in knownPetIds }
            .filterNot { it.id in otherAccountsExpenseIds }

        val readAt = System.currentTimeMillis()
        val local = expenseRepository.getExpensesForOwner(userId)

        val (toApplyLocally, toPushRemotely) = reconcile(
            local = local,
            remote = remote,
            idOf = Expense::id,
            updatedAtOf = Expense::updatedAt,
            isPendingOf = Expense::isPendingSync
        )

        applyLocally(toApplyLocally) { expenseRepository.upsertExpensesFromSync(it) }

        pushRemotely(
            userKey,
            FirestoreSyncService.COLLECTION_EXPENSES,
            toPushRemotely,
            Expense::id
        ) { it.toRemoteMap() }

        expenseRepository.clearPendingExpenseSync(userId, readAt)
    }

    /** Reconciles saved places. */
    private suspend fun syncPlaces(userKey: String, userId: Long) {
        val otherAccountsPlaceIds = placeRepository.getPlaceIdsNotOwnedBy(userId)

        val remote = firestoreSyncService
            .fetchCollection(userKey, FirestoreSyncService.COLLECTION_PLACES)
            .mapNotNull { placeFromRemote(it, userId) }
            .filterNot { it.id in otherAccountsPlaceIds }

        val readAt = System.currentTimeMillis()
        val local = placeRepository.getPlacesForOwner(userId)

        val (toApplyLocally, toPushRemotely) = reconcile(
            local = local,
            remote = remote,
            idOf = Place::id,
            updatedAtOf = Place::updatedAt,
            isPendingOf = Place::isPendingSync
        )

        applyLocally(toApplyLocally) { placeRepository.upsertPlacesFromSync(it) }

        pushRemotely(
            userKey,
            FirestoreSyncService.COLLECTION_PLACES,
            toPushRemotely,
            Place::id
        ) { it.toRemoteMap() }

        placeRepository.clearPendingPlaceSync(userId, readAt)
    }

    // Step 3: the account document.

    /** Refreshes the account's own document. */
    private suspend fun writeAccountDocument(userKey: String, user: User) {
        firestoreSyncService.writeUserProfile(
            userKey = userKey,
            fields = mapOf(
                "email" to user.email,
                "fullName" to user.fullName,
                "lastSyncedAt" to System.currentTimeMillis()
            )
        )
    }

    // Shared machinery.

    /** The result of comparing one table against its remote collection. */
    private data class Reconciliation<T>(
        val toApplyLocally: List<T>,
        val toPushRemotely: List<T>
    )

    /** Decides, for one table, which records travel in which direction. */
    private fun <T> reconcile(
        local: List<T>,
        remote: List<T>,
        idOf: (T) -> Long,
        updatedAtOf: (T) -> Long,
        isPendingOf: (T) -> Boolean
    ): Reconciliation<T> {
        val localById = local.associateBy(idOf)
        val remoteById = remote.associateBy(idOf)

        return Reconciliation(
            toApplyLocally = remote.filter { remoteRecord ->
                val localRecord = localById[idOf(remoteRecord)]
                localRecord == null || updatedAtOf(remoteRecord) > updatedAtOf(localRecord)
            },
            toPushRemotely = local.filter { localRecord ->
                val remoteRecord = remoteById[idOf(localRecord)]
                when {
                    // Firestore has never seen this row.
                    remoteRecord == null -> true
                    // This device holds the later edit.
                    updatedAtOf(localRecord) > updatedAtOf(remoteRecord) -> true
                    // Firestore holds the later edit; it is pulled down instead.
                    updatedAtOf(localRecord) < updatedAtOf(remoteRecord) -> false
                    // Identical timestamps: send only if this row was never confirmed as sent.
                    else -> isPendingOf(localRecord)
                }
            }
        )
    }

    /** Writes pulled records into Room, doing nothing at all when there are none. */
    private suspend fun <T> applyLocally(records: List<T>, write: suspend (List<T>) -> Unit) {
        if (records.isNotEmpty()) write(records)
    }

    /** Uploads records as documents named by their row id. */
    private suspend fun <T> pushRemotely(
        userKey: String,
        collection: String,
        records: List<T>,
        idOf: (T) -> Long,
        toMap: (T) -> Map<String, Any?>
    ) {
        if (records.isEmpty()) return

        firestoreSyncService.writeDocuments(
            userKey = userKey,
            collection = collection,
            documents = records.associate { idOf(it).toString() to toMap(it) }
        )
    }

    /** The document id that identifies this account's data in Firestore. */
    private fun remoteKeyFor(user: User): String? {
        val key = user.email.trim().lowercase()

        // Firestore forbids "/" in a document id — it is the path separator — and rejects the ids "." and "..".
        val isUsable = key.isNotEmpty() &&
            !key.contains('/') &&
            key != "." &&
            key != ".."

        return if (isUsable) key else null
    }

    private companion object {

        private const val TAG = "SyncManager"

        /** How long to gather local changes before syncing. */
        private const val CHANGE_DEBOUNCE_MILLIS = 1_500L

        /** How long one cycle may take before it is abandoned as unreachable. */
        private const val SYNC_TIMEOUT_MILLIS = 30_000L
    }
}
