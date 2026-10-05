package com.prash.petcare.data.assistant

import com.prash.petcare.data.local.entity.Pet
import com.prash.petcare.util.CategorySpend

/** One of today's tasks, as the assistant sees it. */
data class AssistantTask(
    val title: String,
    val petName: String,
    val isDone: Boolean,
    val timeOfDayMinutes: Int?
)

/** Everything the assistant is given about this account, gathered from the user's own records. */
data class AssistantContext(
    val userFirstName: String = "",
    val pets: List<Pet> = emptyList(),
    val todayTasks: List<AssistantTask> = emptyList(),
    val monthSpendTotal: Double? = null,
    val monthSpendByCategory: List<CategorySpend> = emptyList(),
    val monthSpendFormatted: String? = null,
    val monthSpendChangePercent: Int? = null
)

/** One turn of the conversation, as the assistant needs to replay it. */
data class AssistantTurn(val fromUser: Boolean, val text: String)

/** Why a reply could not be produced. Each maps to its own sentence on screen. */
enum class AssistantFailure {
    /** No usable connection — the request never reached the service. */
    NO_NETWORK,

    /** No key is configured at all, so no request was attempted. */
    MISSING_KEY,

    /** The service rejected the key (401/403): absent from the account, revoked, or mistyped. */
    INVALID_KEY,

    /** Too many requests, or the account is out of quota (429). */
    RATE_LIMITED,

    /** A reply arrived but was not the shape the app expects, or carried no text. */
    BAD_RESPONSE,

    /** Anything else — a 500, a timeout mid-read, something unforeseen. */
    UNKNOWN
}

/** The outcome of asking a question. */
sealed interface AssistantAnswer {
    data class Success(val text: String) : AssistantAnswer
    data class Failure(val reason: AssistantFailure) : AssistantAnswer
}

/** Where the assistant's replies come from. */
interface AssistantRepository {

    /** Answers the last turn of [history], given what the app currently knows. */
    suspend fun answer(history: List<AssistantTurn>, context: AssistantContext): AssistantAnswer
}
