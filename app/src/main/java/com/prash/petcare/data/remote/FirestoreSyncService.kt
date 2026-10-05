package com.prash.petcare.data.remote

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import com.prash.petcare.data.local.entity.SyncEntityType
import kotlinx.coroutines.tasks.await

/** The only class in the app that talks to Firestore. */
class FirestoreSyncService(private val firestore: FirebaseFirestore) {

    /** Reads every document in one of a user's collections. */
    suspend fun fetchCollection(userKey: String, collection: String): List<Map<String, Any?>> =
        firestore.collection(USERS_COLLECTION)
            .document(userKey)
            .collection(collection)
            .get(Source.SERVER)
            .await()
            .documents
            .mapNotNull { it.data }

    /** Creates or overwrites documents in one collection, keyed by the id each map carries. */
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
                // set() rather than update(): the document may not exist yet.
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

    /** Removes every routine, task and expense belonging to a pet. */
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

    /** Creates or refreshes the account's own document. */
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

        /** Which collection a deleted row's document lives in. */
        fun collectionFor(type: SyncEntityType): String = when (type) {
            SyncEntityType.PET -> COLLECTION_PETS
            SyncEntityType.CARE_ROUTINE -> COLLECTION_ROUTINES
            SyncEntityType.CARE_TASK -> COLLECTION_TASKS
            SyncEntityType.EXPENSE -> COLLECTION_EXPENSES
            SyncEntityType.PLACE -> COLLECTION_PLACES
        }
    }
}
