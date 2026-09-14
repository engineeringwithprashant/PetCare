package com.prash.petcare.data.remote

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import com.prash.petcare.data.local.entity.SyncEntityType
import kotlinx.coroutines.tasks.await

/**
 * The only class in the app that talks to Firestore.
 *
 * It is the remote equivalent of a DAO: it knows how documents are laid out and how to read and
 * write them, and nothing else. Deciding *what* should be written, and which copy of a record wins
 * when two disagree, belongs to [com.prash.petcare.data.sync.SyncManager] — keeping the two apart
 * means the merge rules can be read without wading through Firestore's API, and this file can be
 * swapped for a different backend without touching them.
 *
 * **How the data is laid out.** Everything belonging to one account hangs off one document:
 *
 * ```
 * users/{userKey}                      profile fields, and the parent of everything below
 * users/{userKey}/pets/{petId}
 * users/{userKey}/routines/{routineId}
 * users/{userKey}/tasks/{taskId}
 * users/{userKey}/expenses/{expenseId}
 * users/{userKey}/places/{placeId}
 * ```
 *
 * Subcollections under a per-user document, rather than four top-level collections with an owner
 * field, because it makes "everything for this user" a single path rather than a query — and
 * because it is the shape Firestore security rules are written against, so restricting a user to
 * their own subtree later is one rule rather than four.
 *
 * **Nothing here retries or reports.** Every method throws whatever Firestore threw, most often
 * because the device is offline, and the caller decides what that means. See [SyncManager] for why
 * that is silence rather than an error.
 *
 * @property firestore The Firestore instance, injected so this class creates nothing it depends on.
 */
class FirestoreSyncService(private val firestore: FirebaseFirestore) {

    /**
     * Reads every document in one of a user's collections.
     *
     * [Source.SERVER] is the important argument. Firestore keeps its own local cache and by default
     * will happily answer from it when the device is offline, which would look like a successful
     * sync that had in fact reached nothing. Insisting on the server means an offline device throws
     * here, which is the honest answer and the one the status indicator needs.
     *
     * @return Each document's fields, keyed by field name. Documents are returned as raw maps
     *   rather than typed objects — see [RemoteMappers] for why the conversion is done by hand.
     */
    suspend fun fetchCollection(userKey: String, collection: String): List<Map<String, Any?>> =
        firestore.collection(USERS_COLLECTION)
            .document(userKey)
            .collection(collection)
            .get(Source.SERVER)
            .await()
            .documents
            .mapNotNull { it.data }

    /**
     * Creates or overwrites documents in one collection, keyed by the id each map carries.
     *
     * Written as batches rather than one call per document: a batch is a single round trip and is
     * applied atomically, so a sync cannot leave half a pet's routines uploaded. Firestore caps a
     * batch at 500 operations, hence the chunking — a user with more than 500 tasks would otherwise
     * have the whole write rejected.
     *
     * @param documents The documents to write, keyed by document id.
     */
    suspend fun writeDocuments(
        userKey: String,
        collection: String,
        documents: Map<String, Map<String, Any?>>
    ) {
        if (documents.isEmpty()) return

        val target = firestore.collection(USERS_COLLECTION)
            .document(userKey)
            .collection(collection)

        documents.entries.chunked(MAX_BATCH_SIZE).forEach { chunk ->
            val batch = firestore.batch()
            chunk.forEach { (documentId, fields) ->
                // set() rather than update(): the document may not exist yet, and update() fails
                // on a missing document rather than creating it.
                batch.set(target.document(documentId), fields)
            }
            batch.commit().await()
        }
    }

    /** Removes documents by id. Deleting one that is not there is not an error in Firestore. */
    suspend fun deleteDocuments(userKey: String, collection: String, documentIds: List<String>) {
        if (documentIds.isEmpty()) return

        val target = firestore.collection(USERS_COLLECTION)
            .document(userKey)
            .collection(collection)

        documentIds.chunked(MAX_BATCH_SIZE).forEach { chunk ->
            val batch = firestore.batch()
            chunk.forEach { documentId -> batch.delete(target.document(documentId)) }
            batch.commit().await()
        }
    }

    /**
     * Removes every routine, task and expense belonging to a pet.
     *
     * This reproduces remotely what SQLite's CASCADE rules do locally when a pet is deleted.
     * Firestore has no cascading delete of its own — deleting a document leaves its subcollections
     * and any document referring to it completely untouched — so without this the children of a
     * deleted pet would sit in the database forever and be pulled back down as orphans.
     *
     * The children are found by querying on their `petId` field, which is why they are stored with
     * one.
     */
    suspend fun deleteDocumentsForPet(userKey: String, petId: Long) {
        val userDocument = firestore.collection(USERS_COLLECTION).document(userKey)

        listOf(COLLECTION_ROUTINES, COLLECTION_TASKS, COLLECTION_EXPENSES).forEach { collection ->
            val matches = userDocument.collection(collection)
                .whereEqualTo(FIELD_PET_ID, petId)
                .get(Source.SERVER)
                .await()

            deleteDocuments(userKey, collection, matches.documents.map { it.id })
        }
    }

    /**
     * Creates or refreshes the account's own document.
     *
     * Firestore allows a subcollection to exist beneath a document that was never written, and such
     * a parent shows up as a ghost entry in the console that cannot be opened. Writing real fields
     * here avoids that, and gives somewhere to record when this account last synced.
     *
     * `merge = true` in effect: only these fields are touched, so a field written by a future
     * version of the app is not wiped by this one.
     */
    suspend fun writeUserProfile(userKey: String, fields: Map<String, Any?>) {
        firestore.collection(USERS_COLLECTION)
            .document(userKey)
            .set(fields, com.google.firebase.firestore.SetOptions.merge())
            .await()
    }

    companion object {

        private const val USERS_COLLECTION = "users"

        const val COLLECTION_PETS = "pets"
        const val COLLECTION_ROUTINES = "routines"
        const val COLLECTION_TASKS = "tasks"
        const val COLLECTION_EXPENSES = "expenses"
        const val COLLECTION_PLACES = "places"

        /** The field every child document carries so a deleted pet's children can be found. */
        private const val FIELD_PET_ID = "petId"

        /** Firestore's own limit on how many writes one batch may contain. */
        private const val MAX_BATCH_SIZE = 500

        /**
         * Which collection a deleted row's document lives in.
         *
         * Kept here rather than as a property on [SyncEntityType] because a collection name is a
         * detail of how this class stores things; the enum belongs to the database layer and has no
         * business knowing about Firestore.
         */
        fun collectionFor(type: SyncEntityType): String = when (type) {
            SyncEntityType.PET -> COLLECTION_PETS
            SyncEntityType.CARE_ROUTINE -> COLLECTION_ROUTINES
            SyncEntityType.CARE_TASK -> COLLECTION_TASKS
            SyncEntityType.EXPENSE -> COLLECTION_EXPENSES
            SyncEntityType.PLACE -> COLLECTION_PLACES
        }
    }
}
