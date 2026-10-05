package com.prash.petcare.data.remote

import com.prash.petcare.data.local.entity.CareRoutine
import com.prash.petcare.data.local.entity.CareTask
import com.prash.petcare.data.local.entity.Expense
import com.prash.petcare.data.local.entity.ExpenseCategory
import com.prash.petcare.data.local.entity.Pet
import com.prash.petcare.data.local.entity.PetGender
import com.prash.petcare.data.local.entity.PetSpecies
import com.prash.petcare.data.local.entity.Place
import com.prash.petcare.data.local.entity.PlaceCategory
import com.prash.petcare.data.local.entity.RepeatUnit
import com.prash.petcare.data.local.entity.TaskCategory

/** Translates between the app's Room entities and the plain maps stored in Firestore. */

// Field names.

private const val FIELD_ID = "id"
private const val FIELD_OWNER_ID = "ownerId"
private const val FIELD_PET_ID = "petId"
private const val FIELD_ROUTINE_ID = "routineId"
private const val FIELD_NAME = "name"
private const val FIELD_TITLE = "title"
private const val FIELD_SPECIES = "species"
private const val FIELD_GENDER = "gender"
private const val FIELD_BREED = "breed"
private const val FIELD_DATE_OF_BIRTH = "dateOfBirth"
private const val FIELD_WEIGHT_KG = "weightKg"
private const val FIELD_NOTES = "notes"
private const val FIELD_DIETARY_NOTES = "dietaryNotes"
private const val FIELD_ALLERGIES = "allergies"
private const val FIELD_FAVOURITE_TOYS = "favouriteToys"
private const val FIELD_DESCRIPTION = "description"
private const val FIELD_CATEGORY = "category"
private const val FIELD_REPEAT_INTERVAL = "repeatInterval"
private const val FIELD_REPEAT_UNIT = "repeatUnit"
private const val FIELD_START_DATE = "startDate"
private const val FIELD_IS_ACTIVE = "isActive"
private const val FIELD_REQUIRED_SUPPLIES = "requiredSupplies"
private const val FIELD_TIME_OF_DAY_MINUTES = "timeOfDayMinutes"
private const val FIELD_DUE_AT = "dueAt"
private const val FIELD_IS_COMPLETED = "isCompleted"
private const val FIELD_COMPLETED_AT = "completedAt"
private const val FIELD_AMOUNT = "amount"
private const val FIELD_INCURRED_ON = "incurredOn"
private const val FIELD_CREATED_AT = "createdAt"
private const val FIELD_UPDATED_AT = "updatedAt"
private const val FIELD_AREA = "area"
private const val FIELD_LATITUDE = "latitude"
private const val FIELD_LONGITUDE = "longitude"
private const val FIELD_PHONE = "phone"
private const val FIELD_OPENING_MINUTES = "openingMinutes"
private const val FIELD_CLOSING_MINUTES = "closingMinutes"
private const val FIELD_IS_PINNED = "isPinned"

// Reading helpers.

private fun Map<String, Any?>.longOrNull(key: String): Long? = (this[key] as? Number)?.toLong()
private fun Map<String, Any?>.intOrNull(key: String): Int? = (this[key] as? Number)?.toInt()
private fun Map<String, Any?>.doubleOrNull(key: String): Double? = (this[key] as? Number)?.toDouble()
private fun Map<String, Any?>.stringOrNull(key: String): String? = this[key] as? String
private fun Map<String, Any?>.booleanOr(key: String, fallback: Boolean): Boolean =
    this[key] as? Boolean ?: fallback

/** Rebuilds an enum constant from the name stored in the document. */
private inline fun <reified T : Enum<T>> Map<String, Any?>.enumOr(key: String, fallback: T): T {
    val name = stringOrNull(key) ?: return fallback
    return enumValues<T>().firstOrNull { it.name == name } ?: fallback
}

// Pet.

/** Converts a pet into the document written to Firestore. */
fun Pet.toRemoteMap(): Map<String, Any?> = mapOf(
    FIELD_ID to id,
    FIELD_OWNER_ID to ownerId,
    FIELD_NAME to name,
    FIELD_SPECIES to species.name,
    // Written as the enum's name, the same text Room stores, so the document and the row agree.
    FIELD_GENDER to gender.name,
    FIELD_BREED to breed,
    FIELD_DATE_OF_BIRTH to dateOfBirth,
    FIELD_WEIGHT_KG to weightKg,
    FIELD_NOTES to notes,
    FIELD_DIETARY_NOTES to dietaryNotes,
    FIELD_ALLERGIES to allergies,
    FIELD_FAVOURITE_TOYS to favouriteToys,
    FIELD_CREATED_AT to createdAt,
    FIELD_UPDATED_AT to updatedAt
)

/** Rebuilds a pet from a document, or returns null if it cannot be trusted. */
fun petFromRemote(document: Map<String, Any?>, ownerId: Long): Pet? {
    val id = document.longOrNull(FIELD_ID) ?: return null
    val name = document.stringOrNull(FIELD_NAME) ?: return null

    return Pet(
        id = id,
        ownerId = ownerId,
        name = name,
        species = document.enumOr(FIELD_SPECIES, PetSpecies.OTHER),
        // Documents uploaded before gender existed have no such field.
        gender = document.enumOr(FIELD_GENDER, PetGender.UNKNOWN),
        breed = document.stringOrNull(FIELD_BREED),
        dateOfBirth = document.longOrNull(FIELD_DATE_OF_BIRTH),
        weightKg = document.doubleOrNull(FIELD_WEIGHT_KG),
        // Left null here and restored from the local row by the sync layer.
        photoPath = null,
        notes = document.stringOrNull(FIELD_NOTES),
        dietaryNotes = document.stringOrNull(FIELD_DIETARY_NOTES),
        allergies = document.stringOrNull(FIELD_ALLERGIES),
        favouriteToys = document.stringOrNull(FIELD_FAVOURITE_TOYS),
        createdAt = document.longOrNull(FIELD_CREATED_AT) ?: System.currentTimeMillis(),
        updatedAt = document.longOrNull(FIELD_UPDATED_AT) ?: 0L
    )
}

// CareRoutine.

fun CareRoutine.toRemoteMap(): Map<String, Any?> = mapOf(
    FIELD_ID to id,
    FIELD_PET_ID to petId,
    FIELD_TITLE to title,
    FIELD_DESCRIPTION to description,
    FIELD_CATEGORY to category.name,
    FIELD_REPEAT_INTERVAL to repeatInterval,
    FIELD_REPEAT_UNIT to repeatUnit.name,
    FIELD_START_DATE to startDate,
    FIELD_IS_ACTIVE to isActive,
    FIELD_CREATED_AT to createdAt,
    FIELD_UPDATED_AT to updatedAt
)

fun careRoutineFromRemote(document: Map<String, Any?>): CareRoutine? {
    val id = document.longOrNull(FIELD_ID) ?: return null
    // The pet is what the routine hangs off; without it the row could not satisfy its foreign key.
    val petId = document.longOrNull(FIELD_PET_ID) ?: return null
    val title = document.stringOrNull(FIELD_TITLE) ?: return null

    return CareRoutine(
        id = id,
        petId = petId,
        title = title,
        description = document.stringOrNull(FIELD_DESCRIPTION),
        category = document.enumOr(FIELD_CATEGORY, TaskCategory.OTHER),
        // A zero or negative interval would mean "repeats every no time at all" and would send the schedule arithmetic into a loop.
        repeatInterval = (document.intOrNull(FIELD_REPEAT_INTERVAL) ?: 1).coerceAtLeast(1),
        repeatUnit = document.enumOr(FIELD_REPEAT_UNIT, RepeatUnit.DAY),
        startDate = document.longOrNull(FIELD_START_DATE) ?: System.currentTimeMillis(),
        isActive = document.booleanOr(FIELD_IS_ACTIVE, fallback = true),
        createdAt = document.longOrNull(FIELD_CREATED_AT) ?: System.currentTimeMillis(),
        updatedAt = document.longOrNull(FIELD_UPDATED_AT) ?: 0L
    )
}

// CareTask.

fun CareTask.toRemoteMap(): Map<String, Any?> = mapOf(
    FIELD_ID to id,
    FIELD_PET_ID to petId,
    FIELD_ROUTINE_ID to routineId,
    FIELD_TITLE to title,
    FIELD_NOTES to notes,
    FIELD_REQUIRED_SUPPLIES to requiredSupplies,
    FIELD_CATEGORY to category.name,
    FIELD_TIME_OF_DAY_MINUTES to timeOfDayMinutes,
    FIELD_DUE_AT to dueAt,
    FIELD_IS_COMPLETED to isCompleted,
    FIELD_COMPLETED_AT to completedAt,
    FIELD_CREATED_AT to createdAt,
    FIELD_UPDATED_AT to updatedAt
)

fun careTaskFromRemote(document: Map<String, Any?>): CareTask? {
    val id = document.longOrNull(FIELD_ID) ?: return null
    val petId = document.longOrNull(FIELD_PET_ID) ?: return null
    val title = document.stringOrNull(FIELD_TITLE) ?: return null

    return CareTask(
        id = id,
        petId = petId,
        // Genuinely optional, unlike petId.
        routineId = document.longOrNull(FIELD_ROUTINE_ID),
        title = title,
        notes = document.stringOrNull(FIELD_NOTES),
        requiredSupplies = document.stringOrNull(FIELD_REQUIRED_SUPPLIES),
        category = document.enumOr(FIELD_CATEGORY, TaskCategory.OTHER),
        timeOfDayMinutes = document.intOrNull(FIELD_TIME_OF_DAY_MINUTES),
        dueAt = document.longOrNull(FIELD_DUE_AT) ?: System.currentTimeMillis(),
        isCompleted = document.booleanOr(FIELD_IS_COMPLETED, fallback = false),
        completedAt = document.longOrNull(FIELD_COMPLETED_AT),
        createdAt = document.longOrNull(FIELD_CREATED_AT) ?: System.currentTimeMillis(),
        updatedAt = document.longOrNull(FIELD_UPDATED_AT) ?: 0L
    )
}

// Expense.

fun Expense.toRemoteMap(): Map<String, Any?> = mapOf(
    FIELD_ID to id,
    FIELD_PET_ID to petId,
    FIELD_TITLE to title,
    FIELD_AMOUNT to amount,
    FIELD_CATEGORY to category.name,
    FIELD_INCURRED_ON to incurredOn,
    FIELD_NOTES to notes,
    FIELD_CREATED_AT to createdAt,
    FIELD_UPDATED_AT to updatedAt
)

fun expenseFromRemote(document: Map<String, Any?>): Expense? {
    val id = document.longOrNull(FIELD_ID) ?: return null
    val petId = document.longOrNull(FIELD_PET_ID) ?: return null
    val title = document.stringOrNull(FIELD_TITLE) ?: return null
    // An expense with no amount is not a usable spending record.
    val amount = document.doubleOrNull(FIELD_AMOUNT) ?: return null

    return Expense(
        id = id,
        petId = petId,
        title = title,
        amount = amount,
        category = document.enumOr(FIELD_CATEGORY, ExpenseCategory.OTHER),
        incurredOn = document.longOrNull(FIELD_INCURRED_ON) ?: System.currentTimeMillis(),
        notes = document.stringOrNull(FIELD_NOTES),
        createdAt = document.longOrNull(FIELD_CREATED_AT) ?: System.currentTimeMillis(),
        updatedAt = document.longOrNull(FIELD_UPDATED_AT) ?: 0L
    )
}

// Place.

/** Converts a saved place into the document written to Firestore. */
fun Place.toRemoteMap(): Map<String, Any?> = mapOf(
    FIELD_ID to id,
    FIELD_OWNER_ID to ownerId,
    FIELD_NAME to name,
    FIELD_CATEGORY to category.name,
    FIELD_AREA to area,
    FIELD_LATITUDE to latitude,
    FIELD_LONGITUDE to longitude,
    FIELD_PHONE to phone,
    FIELD_OPENING_MINUTES to openingMinutes,
    FIELD_CLOSING_MINUTES to closingMinutes,
    FIELD_NOTES to notes,
    FIELD_IS_PINNED to isPinned,
    FIELD_CREATED_AT to createdAt,
    FIELD_UPDATED_AT to updatedAt
)

/** Rebuilds a place from a document, or returns null if it cannot be trusted. */
fun placeFromRemote(document: Map<String, Any?>, ownerId: Long): Place? {
    val id = document.longOrNull(FIELD_ID) ?: return null
    val name = document.stringOrNull(FIELD_NAME) ?: return null
    val latitude = document.doubleOrNull(FIELD_LATITUDE) ?: return null
    val longitude = document.doubleOrNull(FIELD_LONGITUDE) ?: return null

    return Place(
        id = id,
        ownerId = ownerId,
        name = name,
        // STORE is the least specific category.
        category = document.enumOr(FIELD_CATEGORY, PlaceCategory.STORE),
        area = document.stringOrNull(FIELD_AREA),
        latitude = latitude,
        longitude = longitude,
        phone = document.stringOrNull(FIELD_PHONE),
        openingMinutes = document.intOrNull(FIELD_OPENING_MINUTES)?.takeIf { it in MINUTES_IN_DAY },
        closingMinutes = document.intOrNull(FIELD_CLOSING_MINUTES)?.takeIf { it in MINUTES_IN_DAY },
        notes = document.stringOrNull(FIELD_NOTES),
        isPinned = document.booleanOr(FIELD_IS_PINNED, fallback = false),
        createdAt = document.longOrNull(FIELD_CREATED_AT) ?: System.currentTimeMillis(),
        updatedAt = document.longOrNull(FIELD_UPDATED_AT) ?: 0L
    )
}

/** The valid range for a time of day stored as minutes since midnight. */
private val MINUTES_IN_DAY = 0 until 24 * 60
