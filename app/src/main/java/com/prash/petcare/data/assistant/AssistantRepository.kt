package com.prash.petcare.data.assistant

import com.prash.petcare.data.local.entity.Pet
import com.prash.petcare.util.CategorySpend

/**
 * One of today's tasks, as the assistant sees it.
 *
 * Flattened out of the Room rows on purpose: the assistant needs the pet's *name* beside the task,
 * which the task row only holds as an id, and it has no business being handed an entity it could
 * follow into the rest of the database.
 */
data class AssistantTask(
    val title: String,
    val petName: String,
    val isDone: Boolean,
    val timeOfDayMinutes: Int?
)

/**
 * Everything the assistant is given about *this account*, gathered from the user's own records.
 *
 * It exists so the boundary is **explicit**: this is the whole of what the assistant can see of the
 * user's data, and a reader can check that claim by reading one data class rather than auditing every
 * call site. The screen tells the user as much in its header, so the promise printed on screen and the
 * data actually sent are the same list.
 *
 * Note what the boundary is and is not. It bounds the assistant's knowledge of *this user* — it does
 * not bound the subjects it will discuss. The assistant answers questions on anything, and the point
 * of this class is that everything it says about the user's own animals is grounded in these fields
 * rather than invented. See [com.prash.petcare.data.assistant.OpenAiAssistantRepository]'s system
 * prompt, which draws that distinction explicitly.
 *
 * Plain values rather than DAOs or flows: the assistant is handed a *snapshot* to answer from, and
 * cannot reach back into the database to widen it. That matters more now than it did when the
 * answers were composed on-device — this snapshot is what leaves the phone.
 *
 * @property userFirstName Who is asking, for the greeting.
 * @property pets The account's pets. Each carries its own species, date of birth, weight, dietary
 *   notes and allergies, so no separate fields are needed for them here.
 * @property todayTasks Everything due today across every pet, with whether it is ticked off.
 * @property monthSpendTotal This month's total across every pet, unformatted.
 * @property monthSpendByCategory Where that total went, largest first.
 * @property monthSpendFormatted The same total in the app's currency format ("Rs 4,665").
 *
 *   **Why formatted as well as raw.** The app formats money in exactly one place, and that place —
 *   ui.common.formatCurrency — sits in the View layer. A repository reaching up into `ui` would
 *   invert the one-way dependency the project is built on, so the ViewModel formats it on the way in
 *   and the raw figure rides along for anything that needs to do arithmetic with it.
 * @property monthSpendChangePercent How this month compares with last, or null when last month had
 *   no spending to compare against.
 */
data class AssistantContext(
    val userFirstName: String = "",
    val pets: List<Pet> = emptyList(),
    val todayTasks: List<AssistantTask> = emptyList(),
    val monthSpendTotal: Double? = null,
    val monthSpendByCategory: List<CategorySpend> = emptyList(),
    val monthSpendFormatted: String? = null,
    val monthSpendChangePercent: Int? = null
)

/**
 * One turn of the conversation, as the assistant needs to replay it.
 *
 * Deliberately not [com.prash.petcare.ui.assistant.AssistantMessage]: that type also models a
 * loading placeholder and a failure notice, neither of which is anything a language model should be
 * told happened. This is the conversation as it was actually spoken.
 */
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

/**
 * The outcome of asking a question.
 *
 * **Why a result type rather than an exception.** The screen has to *draw* every failure — each one
 * becomes a readable message in the conversation — so the reason has to survive as a value the UI
 * can branch on. An exception would have to be caught and re-classified at the call site, which is
 * exactly the logic that belongs down here with the code that knows what a 429 means.
 *
 * The third state the screen shows, loading, is not here on purpose: a `suspend` function cannot
 * return "still working". It is the window around the call, and [com.prash.petcare.ui.assistant.AssistantViewModel]
 * owns it — see the placeholder message it inserts before awaiting this.
 */
sealed interface AssistantAnswer {
    data class Success(val text: String) : AssistantAnswer
    data class Failure(val reason: AssistantFailure) : AssistantAnswer
}

/**
 * Where the assistant's replies come from.
 *
 * In the MVVM chain this is the **Repository**: [com.prash.petcare.ui.assistant.AssistantViewModel]
 * hands it the conversation and a snapshot of the account's data and gets an answer back, knowing
 * nothing about where that answer was produced.
 *
 * **Why the interface still exists with one implementation.** It is what lets the backend be swapped
 * without touching a view, a ViewModel or a layout — the screen was written against this shape while
 * there was no backend at all, and the one line in [com.prash.petcare.PetCareApplication] that names
 * the implementation is the only thing that changed when there was.
 */
interface AssistantRepository {

    /**
     * Answers the last turn of [history], given what the app currently knows.
     *
     * @param history The whole conversation so far, oldest first, ending with the new question.
     *   Passed in full rather than as a single question so a follow-up ("what about the other one?")
     *   has the turns it refers back to.
     * @param context A snapshot of the account's records to answer from.
     */
    suspend fun answer(history: List<AssistantTurn>, context: AssistantContext): AssistantAnswer
}
