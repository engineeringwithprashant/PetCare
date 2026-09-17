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

/**
 * Keeps one account's data mirrored between Room and Firestore.
 *
 * **Room is the source of truth, and this class does not change that.** Every screen in the app
 * still reads from Room and only from Room, so the app behaves identically whether or not it has
 * ever reached the network — offline is the normal case here, not an error state. All this class
 * does is move rows in and out at the edges: what is on the device is uploaded, what is in
 * Firestore and newer is written into Room, and the screens then update themselves because their
 * Room queries re-emit. Nothing in the UI layer knows this class exists.
 *
 * **Resolving disagreements: last write wins.** Every synced row carries an `updatedAt` stamped by
 * its repository on each write. When the same row exists in both places the later timestamp is
 * taken and the other copy is overwritten; when the timestamps are equal, nothing happens, which is
 * what lets a settled account sync repeatedly without writing anything. It is the simplest rule
 * that always terminates, and its cost is honest: an edit made on one device can be overwritten by
 * a later edit on another, because nothing here merges two versions of a row field by field.
 *
 * **The cycle**, in this order, and the order matters:
 *  1. **deletions are pushed first**, so a row deleted on this device is gone from Firestore before
 *     step 2 reads it — otherwise the pull would find it, see no local row, and put it straight
 *     back;
 *  2. **pets, then routines, then tasks, then expenses, then places** are each fetched, compared and
 *     written in both directions. Parents before children, because Room's foreign keys reject a task
 *     whose pet has not been inserted yet. Places hang off the account rather than a pet, so they
 *     could go anywhere in the order; last keeps the pet-shaped tables together;
 *  3. the account's own document is refreshed so it records when this last happened.
 *
 * **Failure is silence, but never forgetfulness.** Any exception — and offline is by far the
 * likeliest — ends the cycle quietly and leaves Room untouched. Nothing is shown to the user,
 * because the data is safe on the device and there is nothing for them to do. What is *not* left to
 * chance is the retry: every local write marks its row `isPendingSync`, a flag that is cleared only
 * once Firestore has confirmed the row, so an unsent change survives the failure, the app being
 * closed, and the device being restarted. Three things then bring it back:
 *  - connectivity returning, watched by [NetworkMonitor] — the one that matters, since it needs no
 *    action from the user at all;
 *  - any further local change;
 *  - the next sign-in.
 *
 * **Scoped to one account, at both ends.** Remotely, everything hangs off a document keyed by the
 * user's email address — see [remoteKeyFor] for why the email and not the row id. Locally, every
 * pull also skips ids that belong to another account on this device: two people can share a phone,
 * and SQLite hands out row ids per device, so one account's pet 3 could otherwise be overwritten by
 * another account's pet 3 arriving from Firestore.
 *
 * **What this is not.** Those same per-device ids mean two devices independently creating a pet
 * would both call it id 1, and the later sync would take them for the same animal. This layer is
 * therefore a faithful backup-and-restore of one account rather than a true multi-device merge;
 * that would need globally unique ids (a UUID per row) assigned at creation, which is a change to
 * how every row is made rather than to this class.
 *
 * @param userRepository Used to read the signed-in account, whose email identifies the remote data.
 * @param petRepository Local pets, and where pulled pets are written.
 * @param careRoutineRepository Local routines, and where pulled routines are written.
 * @param careTaskRepository Local tasks, and where pulled tasks are written.
 * @param expenseRepository Local expenses, and where pulled expenses are written.
 * @param placeRepository Local saved places, and where pulled places are written.
 * @param syncDeletionRepository The queue of deletions still to be sent.
 * @param firestoreSyncService The only route to Firestore; see it for the remote layout.
 * @param networkMonitor Tells this class when the device comes back online, so a change made with
 *   no signal is uploaded by itself rather than waiting for the user to do something else.
 */
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

    /**
     * What the sync is currently doing, for the home screen's indicator.
     *
     * A [StateFlow] rather than an event stream because it describes a *condition* rather than
     * something that happened: a screen opening part-way through a sync should be able to ask what
     * is going on and get an answer, which a one-shot event could not give it.
     *
     * Its value is *derived* rather than assigned at each step of a cycle — see [statusFor]. The
     * reason is that the most important thing it reports, whether anything is still waiting to go
     * up, is not something this class knows from having run a sync: it is a fact about the
     * database, true or false whether or not a sync has ever run in this session.
     */
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    /** True while a cycle is in flight. One of the three inputs to [status]. */
    private val isSyncing = MutableStateFlow(false)

    /**
     * What the last cycle achieved.
     *
     * @property lastSuccessAt When a cycle last completed, or null if none has in this session.
     * @property lastAttemptFailed Whether the most recent attempt failed, which is what separates
     *   "offline" from "not started".
     */
    private data class CycleOutcome(
        val lastSuccessAt: Long? = null,
        val lastAttemptFailed: Boolean = false
    )

    private val cycleOutcome = MutableStateFlow(CycleOutcome())

    /**
     * Which account is signed in, or null before anyone has signed in.
     *
     * Held as a flow rather than a plain field so [startObserving] can begin watching for local
     * changes the moment it is set — the observation has to be attached to a particular user's
     * data, and at app start there is no user to attach it to yet.
     */
    private val currentUserId = MutableStateFlow<Long?>(null)

    /**
     * Serialises [runSyncCycle], so only one is ever in flight.
     *
     * Without it a sync started by the app opening and another started by the user's first edit
     * could overlap, each reading the database, then each writing back a picture of it taken before
     * the other's changes landed. A [Mutex] rather than `synchronized` because this is coroutine
     * code: it parks the waiting coroutine instead of blocking the thread.
     */
    private val syncLock = Mutex()

    /**
     * Records who is signed in, which is what starts syncing for that account.
     *
     * Called when the user reaches the home screen. The app has no persisted session — it opens on
     * the sign-in screen every time — so "on app start" and "once the account is known" are the
     * same moment in practice, and this is it.
     *
     * Setting the same id again does nothing: the flow only emits on a change, so returning to the
     * home screen from a pet's page does not kick off a fresh sync.
     */
    fun onUserSignedIn(userId: Long) {
        currentUserId.value = userId
    }

    /**
     * Starts syncing, and keeps syncing whenever the account's data changes, for as long as the
     * process lives.
     *
     * This is what makes the requirement "sync on app start and after local writes" true without a
     * single screen calling anything. The app's other background job is arranged the same way — see
     * [com.prash.petcare.reminder.ReminderManager] — and for the same reason: a screen that saves a
     * pet is finished when the row is written, and should not also have to remember to tell the
     * sync about it.
     *
     * Reading the chain from the top:
     *  - `filterNotNull` waits for a sign-in, since there is nothing to sync before one;
     *  - `flatMapLatest` switches to watching that user's data, abandoning any previous user's;
     *  - the combined signal emits once immediately on subscribing, which *is* the sync at start-up,
     *    and again on every later change;
     *  - `debounce` collapses a burst into one cycle. Saving a routine with three tasks is four
     *    writes in quick succession, and syncing four times would upload the same state three times
     *    over.
     *
     * @param scope A process-lifetime scope, supplied by
     *   [com.prash.petcare.PetCareApplication]. Deliberately not a ViewModel's or a fragment's: a
     *   sync interrupted half-way by the user leaving a screen would be worse than one that
     *   finishes.
     */
    // flatMapLatest and debounce are both still marked as unstable by the coroutines library. Opted
    // into deliberately rather than worked around: both do exactly what is needed here, and hand
    // rolling either — a switching collector, or a timer that cancels and restarts — would be more
    // code and more ways to get it wrong.
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

    /**
     * Runs a sync whenever connectivity returns.
     *
     * This is the fix for a change made offline never arriving. Before it, a failed push was only
     * retried when something *else* happened — another edit, or the app being reopened — so an edit
     * made on the train could sit on the device indefinitely while the phone had a perfectly good
     * connection.
     *
     * `drop(1)` is what makes this mean "came back online" rather than "is online".
     * [NetworkMonitor] emits the current state first so a collector is never left uninformed, and
     * acting on that first value would start a second, redundant cycle every time the app opened
     * with a connection — the sign-in has already started one. Dropping it leaves only genuine
     * transitions, and `filter` narrows those to the one direction worth reacting to.
     *
     * Nothing happens before sign-in. Connectivity returning with no account signed in has nothing
     * to sync, and the sign-in itself will start a cycle moments later.
     */
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

    /**
     * Keeps [status] describing the truth, from three independent facts.
     *
     * The pending count is the interesting one: it comes from the database, so the indicator can
     * say "3 changes waiting" while offline, after a restart, and without a single network call —
     * none of which would be possible if the status were only ever set by a cycle finishing.
     *
     * `flatMapLatest` re-points the count at whichever account is signed in, since "waiting to
     * upload" is a question about one user's rows.
     */
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

    /**
     * How many records are waiting to be uploaded, across everything that can be queued.
     *
     * Deletions are counted alongside the five tables of live rows: a pet the user deleted with no
     * connection is every bit as unsynced as one they renamed, and reporting "nothing waiting"
     * while a deletion sat in the queue would be a lie.
     *
     * The list form of `combine` is used because there are now six sources, and the typed overloads
     * of `combine` stop at five. Every source here is a count, so the values arrive as one array of
     * the same type and simply add up.
     */
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

    /**
     * Turns the three facts into the one thing the user sees.
     *
     * The order of the branches is the priority order. Anything outstanding outranks a past
     * success, which is why [SyncStatus.Synced] is only ever reached with an empty queue — it has
     * to mean "everything is up there", or it is worse than saying nothing.
     */
    private fun statusFor(
        syncing: Boolean,
        outcome: CycleOutcome,
        pendingCount: Int
    ): SyncStatus = when {
        syncing -> SyncStatus.Syncing
        pendingCount > 0 -> SyncStatus.Pending(pendingCount)
        outcome.lastSuccessAt != null -> SyncStatus.Synced(outcome.lastSuccessAt)
        outcome.lastAttemptFailed -> SyncStatus.Offline
        // Signed in, nothing queued, nothing attempted yet — the brief moment before the first
        // cycle finishes.
        else -> SyncStatus.Idle
    }

    /**
     * Emits the signed-in user's id whenever anything of theirs changes locally.
     *
     * Each source is a single number — the newest `updatedAt` in one table, and the number of
     * queued deletions — rather than the rows themselves. See
     * [com.prash.petcare.data.local.dao.PetDao.observeLatestUpdateForOwner] for why that matters:
     * observing the rows would have this fire again for every row the sync itself wrote, and each
     * of those would start another sync.
     *
     * [distinctUntilChanged] over the combined tuple is the other half of that. Room invalidates
     * queries by table, so it re-runs all of these on any write to the table; comparing the results
     * is what turns "the table was touched" into "something actually changed".
     *
     * Six sources, so the list form of `combine` — see [observePendingCount]. The sources mix
     * nullable timestamps with a count, so they are gathered as `Flow<Any?>`: nothing here reads the
     * values as numbers, it only compares one snapshot with the next.
     */
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
        // The values are combined only to be compared; what is emitted is simply "this user's data
        // is at this state", and the id is what the collector needs to act on it.
        snapshot.toList()
    }
        .distinctUntilChanged()
        .map { userId }

    /**
     * Runs one full sync for [userId], and reports how it went.
     *
     * The timeout exists because Firestore's own offline queue can leave a call pending
     * indefinitely rather than failing: a write made with no connection is held until there is one.
     * Without a deadline the status would sit on "syncing" forever on a flaky connection instead of
     * settling honestly on [SyncStatus.Offline].
     */
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
                // Never swallowed. A CancellationException is how both the timeout above and the
                // scope being torn down arrive here, and catching it as an ordinary failure would
                // stop the timeout working and keep a cancelled coroutine running.
                throw e
            } catch (e: Exception) {
                // Everything else is treated the same way and deliberately kept quiet: offline is
                // the ordinary case, the user's data is safe in Room either way, and there is
                // nothing for them to do about it. Logged only, so a failure can still be
                // diagnosed during development.
                Log.d(TAG, "Sync failed, keeping local data queued: ${e.message}")
                false
            }
        } ?: false

        // Recorded rather than published directly. Whether the user is told "backed up" or
        // "3 changes waiting" depends on what is left in the queue as well as on how this cycle
        // went, and only [statusFor] sees both. A failure deliberately leaves lastSuccessAt alone:
        // an earlier successful upload is still a fact, and forgetting it would make the indicator
        // claim less than it knows.
        isSyncing.value = false
        cycleOutcome.value = if (succeeded) {
            CycleOutcome(lastSuccessAt = System.currentTimeMillis(), lastAttemptFailed = false)
        } else {
            cycleOutcome.value.copy(lastAttemptFailed = true)
        }
    }

    // ---------------------------------------------------------------------------------------
    // Step 1: deletions
    // ---------------------------------------------------------------------------------------

    /**
     * Removes from Firestore everything that has been deleted on this device.
     *
     * A deleted pet also takes its routines, tasks and expenses with it, mirroring the CASCADE
     * rules SQLite applied locally — see [FirestoreSyncService.deleteDocumentsForPet].
     *
     * Tombstones are cleared only after their deletions have gone through. If this throws part-way,
     * the ones already sent stay in the queue and are re-sent next time, which is harmless:
     * deleting a document that is already gone is not an error.
     */
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

    // ---------------------------------------------------------------------------------------
    // Step 2: the five synced tables
    // ---------------------------------------------------------------------------------------

    /**
     * Reconciles pets in both directions.
     *
     * The one special case in the whole sync is [Pet.photoPath]. A photo lives in this device's
     * internal storage and its path is meaningless anywhere else, so it is never uploaded — which
     * means a pet pulled back down carries no photo at all. Copying the local row's path onto the
     * incoming one is what stops an edit made on another device from blanking a photo that is
     * sitting on this one.
     */
    private suspend fun syncPets(userKey: String, userId: Long) {
        val otherAccountsPetIds = petRepository.getPetIdsNotOwnedBy(userId)

        val remote = firestoreSyncService
            .fetchCollection(userKey, FirestoreSyncService.COLLECTION_PETS)
            .mapNotNull { petFromRemote(it, userId) }
            .filterNot { it.id in otherAccountsPetIds }

        // Captured before the rows are read, and used afterwards to decide which of them this cycle
        // is entitled to mark as uploaded — see [clearPendingAfterPush].
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

    /**
     * Reconciles routines, skipping any whose pet this device does not have.
     *
     * A routine's foreign key requires its pet to exist, so inserting one for a missing pet would
     * be rejected by SQLite and take the rest of the batch down with it. A pet can legitimately be
     * missing: it may have been deleted here, with the deletion already pushed, while its routines
     * were still in Firestore. Skipping leaves the stale document to be cleared by the cascade the
     * next time that pet's tombstone is sent.
     */
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

    /**
     * Reconciles tasks, skipping any whose pet — or whose routine, when it names one — is missing.
     *
     * Two foreign keys to satisfy here rather than one. A null `routineId` is fine and common: the
     * SET_NULL rule means a task outlives the routine that made it.
     */
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

    /**
     * Reconciles saved places.
     *
     * The simplest of the five, because a place's only parent is the account itself: there is no pet
     * whose absence could break a foreign key, so nothing has to be filtered out for that reason.
     * The owner id is overwritten with this session's account on the way in, exactly as for pets —
     * see [petFromRemote].
     */
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

    // ---------------------------------------------------------------------------------------
    // Step 3: the account document
    // ---------------------------------------------------------------------------------------

    /**
     * Refreshes the account's own document.
     *
     * Only what identifies the account to a person reading their own data goes up. There is no
     * credential to include: the password lives in Firebase Authentication, never in this row.
     */
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

    // ---------------------------------------------------------------------------------------
    // Shared machinery
    // ---------------------------------------------------------------------------------------

    /**
     * The result of comparing one table against its remote collection.
     *
     * @property toApplyLocally Remote records that are new here, or newer than the local copy.
     * @property toPushRemotely Local records missing from Firestore, or newer than what is there.
     */
    private data class Reconciliation<T>(
        val toApplyLocally: List<T>,
        val toPushRemotely: List<T>
    )

    /**
     * Decides, for one table, which records travel in which direction.
     *
     * This is last-write-wins written once and reused by all five tables rather than repeated with
     * a different type each time — the rule is the same regardless of what is being compared, and
     * four copies of it would be four chances for one to drift.
     *
     * The pending flag settles the one case timestamps cannot: a local row whose remote copy has
     * *exactly* the same `updatedAt`. Normally that means the two are already identical and there
     * is nothing to do. But a row marked pending has never been confirmed as uploaded, and
     * equal-looking timestamps are not proof it arrived — it is cheaper to send it again than to
     * decide from here that it must be fine.
     *
     * A newer remote copy beats a pending local one, and that is not a contradiction: the pull in
     * the other direction is about to overwrite the local row with that newer version, after which
     * there is nothing left to send.
     *
     * Anything already agreed on both sides appears in neither list. That is what makes the sync
     * settle: once a cycle has run, the next one has nothing to do and writes nothing, so it cannot
     * set off another.
     */
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

    /**
     * Writes pulled records into Room, doing nothing at all when there are none.
     *
     * The empty check is not just tidiness. An upsert of an empty list is still a transaction, and
     * Room would tell every observer of those tables that they had changed — including the change
     * signal this class listens to, which would start another sync, which would find nothing and
     * write another empty transaction.
     */
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

    /**
     * The document id that identifies this account's data in Firestore.
     *
     * The email address, not [User.id]. A row id is assigned by whichever device created the
     * account, so the first account made on any install is id 1 — two different people would both
     * key their data to "1" and each would download the other's pets. An email address is unique by
     * definition here (the `users` table enforces it with a unique index) and is the same wherever
     * that person signs in, which is exactly what a remote key has to be.
     *
     * Lower-cased so that the same address typed with different capitalisation reaches the same
     * document. Returns null for an address that cannot be used as a document id, in which case
     * that account simply does not sync rather than writing somewhere unpredictable.
     */
    private fun remoteKeyFor(user: User): String? {
        val key = user.email.trim().lowercase()

        // Firestore forbids "/" in a document id — it is the path separator — and rejects the ids
        // "." and "..". An email cannot contain a slash, but a blank or malformed address stored by
        // some earlier version of the app could still fail these.
        val isUsable = key.isNotEmpty() &&
            !key.contains('/') &&
            key != "." &&
            key != ".."

        return if (isUsable) key else null
    }

    private companion object {

        private const val TAG = "SyncManager"

        /**
         * How long to gather local changes before syncing.
         *
         * Long enough to collapse the handful of writes one user action produces — creating a
         * routine with its tasks, or a screen saving several rows — into a single upload, and short
         * enough that a change is on its way up before the user has finished looking at the screen
         * they made it on.
         */
        private const val CHANGE_DEBOUNCE_MILLIS = 1_500L

        /** How long one cycle may take before it is abandoned as unreachable. */
        private const val SYNC_TIMEOUT_MILLIS = 30_000L
    }
}
