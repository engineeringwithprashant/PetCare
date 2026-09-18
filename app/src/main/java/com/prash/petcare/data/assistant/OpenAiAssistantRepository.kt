package com.prash.petcare.data.assistant

import com.prash.petcare.data.remote.assistant.ChatMessage
import com.prash.petcare.data.remote.assistant.ChatRequest
import com.prash.petcare.data.remote.assistant.OpenAiApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Answers the assistant screen's questions with OpenAI's chat completions endpoint.
 *
 * In the MVVM chain this is the **Repository**, and it owns three jobs the layers above must not:
 * deciding what the model is allowed to talk about, turning the account's records into the context
 * it answers from, and classifying every way the call can fail into something the screen can draw.
 *
 * **What leaves the device.** The system prompt, a compact summary of the account's pets, today's
 * tasks and this month's spending, and the conversation so far. No identifiers, no photographs, no
 * raw database rows — [AssistantContext] is the whole of it, which is what lets the screen's header
 * state plainly what the assistant can see.
 *
 * **Why the model is told to refuse things.** An assistant attached to a pet-care app will be asked
 * to diagnose a sick animal, and answering that is the one genuinely harmful thing it could do. The
 * system prompt therefore closes the subject explicitly and sends the user to a vet, rather than
 * relying on the model's own judgement about where the line is.
 *
 * @param api The endpoint.
 * @param apiKey The key from BuildConfig, which may legitimately be blank — see [answer].
 * @param model Which model to ask. A parameter with a default rather than a hard-coded constant, so
 *   it can be changed in one place without editing this class.
 */
class OpenAiAssistantRepository(
    private val api: OpenAiApi,
    private val apiKey: String,
    private val model: String = DEFAULT_MODEL
) : AssistantRepository {

    /**
     * Asks the model, and returns either its reply or a classified failure.
     *
     * **The missing key is checked first, before anything else happens.** A blank key would
     * otherwise be sent as `Bearer ` and come back as a 401, which reads to the user as "your key is
     * wrong" when the truth is that there is no key at all — two different problems with two
     * different fixes. Catching it here also means no pointless request leaves the device.
     *
     * Every other failure is classified from what the call actually threw:
     *
     * - `IOException` — the request never completed. Offline, DNS, a dropped connection, a timeout.
     * - `HttpException` 401/403 — the service rejected the key.
     * - `HttpException` 429 — rate limited, or the account is out of quota.
     * - anything else thrown — unforeseen, reported as such rather than guessed at.
     *
     * A 200 that carries no usable text is a failure too, and a separate one: the call succeeded, so
     * blaming the network or the key would be wrong.
     *
     * `CancellationException` is rethrown rather than classified, as everywhere else in this app's
     * data layer: it is the coroutine being shut down, not the request failing.
     */
    override suspend fun answer(
        history: List<AssistantTurn>,
        context: AssistantContext
    ): AssistantAnswer {
        if (apiKey.isBlank()) return AssistantAnswer.Failure(AssistantFailure.MISSING_KEY)

        return withContext(Dispatchers.IO) {
            try {
                val response = api.chat(
                    authorization = "Bearer $apiKey",
                    request = ChatRequest(
                        model = model,
                        messages = buildMessages(history, context)
                    )
                )

                val reply = response.choices
                    ?.firstOrNull()
                    ?.message
                    ?.content
                    ?.trim()
                    .orEmpty()

                if (reply.isEmpty()) {
                    AssistantAnswer.Failure(AssistantFailure.BAD_RESPONSE)
                } else {
                    AssistantAnswer.Success(reply)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                // Also covers a body that could not be read to the end, which is why it is caught
                // before the generic clause rather than folded into it.
                AssistantAnswer.Failure(AssistantFailure.NO_NETWORK)
            } catch (e: HttpException) {
                AssistantAnswer.Failure(
                    when (e.code()) {
                        HTTP_UNAUTHORIZED, HTTP_FORBIDDEN -> AssistantFailure.INVALID_KEY
                        HTTP_TOO_MANY_REQUESTS -> AssistantFailure.RATE_LIMITED
                        else -> AssistantFailure.UNKNOWN
                    }
                )
            } catch (e: Exception) {
                // A malformed body surfaces here as Gson's JsonSyntaxException, along with anything
                // else unforeseen. Both are things the user can only retry, so they share a message.
                AssistantAnswer.Failure(AssistantFailure.BAD_RESPONSE)
            }
        }
    }

    /**
     * Assembles the request: the rules, then the data, then the conversation.
     *
     * The context is its own system message rather than being glued onto the instructions, so the
     * two can be read apart — the rules never change, the data changes on every request.
     */
    private fun buildMessages(
        history: List<AssistantTurn>,
        context: AssistantContext
    ): List<ChatMessage> = buildList {
        add(ChatMessage(ROLE_SYSTEM, SYSTEM_PROMPT))
        add(ChatMessage(ROLE_SYSTEM, describe(context)))

        // Only the most recent turns. A conversation left open all day would otherwise grow the
        // request without bound, and the older turns are the least useful part of it.
        history.takeLast(MAX_HISTORY_TURNS).forEach { turn ->
            add(ChatMessage(if (turn.fromUser) ROLE_USER else ROLE_ASSISTANT, turn.text))
        }
    }

    /**
     * The account's records as a compact block of text.
     *
     * **Why it is terse rather than prose.** Every character here is paid for on every request and
     * re-sent on every follow-up, so it is written as labelled lines a model reads easily and a
     * human can still check — not sentences. Empty sections are left out entirely rather than
     * stating "none", except where their absence would be ambiguous: "no tasks due today" is a fact
     * worth asserting, because silence about tasks could be read as no data rather than no work.
     *
     * Ages are given in months and weights in kilograms with their units named, so the model never
     * has to infer a unit. Nothing here is user-facing, so it is plain English rather than string
     * resources — this text is read by the model, never shown on screen.
     */
    private fun describe(context: AssistantContext): String = buildString {
        appendLine("CURRENT APP DATA (the only facts you may use):")

        if (context.pets.isEmpty()) {
            appendLine("Pets: none recorded yet.")
        } else {
            appendLine("Pets:")
            context.pets.forEach { pet ->
                append("- ").append(pet.name)
                append(" (").append(pet.species.name.lowercase(Locale.ROOT)).append(')')
                pet.breed?.takeIf { it.isNotBlank() }?.let { append(", breed ").append(it) }
                pet.dateOfBirth?.let { append(", age ").append(monthsSince(it)).append(" months") }
                pet.weightKg?.let { append(", weight ").append(it).append(" kg") }
                pet.dietaryNotes?.takeIf { it.isNotBlank() }?.let { append(", diet: ").append(it) }
                pet.allergies?.takeIf { it.isNotBlank() }?.let { append(", allergies: ").append(it) }
                appendLine()
            }
        }

        if (context.todayTasks.isEmpty()) {
            appendLine("Today's checklist: nothing due today.")
        } else {
            appendLine("Today's checklist:")
            context.todayTasks.forEach { task ->
                append("- ").append(task.title)
                append(" for ").append(task.petName)
                task.timeOfDayMinutes?.let { append(" at ").append(clockOf(it)) }
                appendLine(if (task.isDone) " [done]" else " [not done]")
            }
        }

        context.monthSpendFormatted?.let { total ->
            append("Spending this month: ").append(total)
            context.monthSpendChangePercent?.let { append(" (").append(it).append("% vs last month)") }
            appendLine()
            context.monthSpendByCategory.take(MAX_SPEND_LINES).forEach { line ->
                appendLine("- ${line.category.name.lowercase(Locale.ROOT)}: ${line.amount}")
            }
        }
    }

    /** Whole months between a date of birth and now — enough precision for "how old is she". */
    private fun monthsSince(dateOfBirthMillis: Long): Long {
        val days = TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis() - dateOfBirthMillis)
        // The division is in Double because the average month is not a whole number of days; the
        // result is floored to whole months, and a date of birth in the future floors to zero
        // rather than reporting a negative age.
        return (days / DAYS_PER_MONTH).toLong().coerceAtLeast(0L)
    }

    /** Minutes past midnight as "18:00" — a 24-hour clock, which needs no locale to be unambiguous. */
    private fun clockOf(timeOfDayMinutes: Int): String =
        String.format(Locale.ROOT, "%02d:%02d", timeOfDayMinutes / 60, timeOfDayMinutes % 60)

    companion object {

        /**
         * The model. `gpt-4o-mini` is chosen as the default because this assistant reads a short
         * context and answers in a few sentences — a task where the larger models cost more without
         * answering it better. Change it here, or pass a different one in from the Application.
         */
        const val DEFAULT_MODEL = "gpt-4o-mini"

        private const val ROLE_SYSTEM = "system"
        private const val ROLE_USER = "user"
        private const val ROLE_ASSISTANT = "assistant"

        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_TOO_MANY_REQUESTS = 429

        /** How many turns of conversation ride along. Enough for a follow-up to make sense. */
        private const val MAX_HISTORY_TURNS = 10

        /** How many spending categories are listed. The long tail is noise at this size. */
        private const val MAX_SPEND_LINES = 5

        /** Average month length, for an age in whole months. */
        private const val DAYS_PER_MONTH = 30.44

        /**
         * What the assistant is, and what it must refuse.
         *
         * Written as rules rather than a persona because the constraints are the point. The last one
         * matters most: this is an app about animals' health, so it will be asked to diagnose, and
         * the only safe answer is to decline and name the vet. The instruction to work only from the
         * supplied data is what keeps it from inventing a weight or a cost that sounds plausible,
         * the failure a chat assistant over private records is most likely to produce.
         *
         * The last rule is a house style rule rather than a safety one: the app's own copy uses no
         * em dashes, and a model left to itself produces them constantly, so its replies would be
         * the one place in the app where they appeared.
         */
        private val SYSTEM_PROMPT = """
            You are the in-app assistant for PetCare, a pet care app. You help one user understand
            their own records.

            You may answer questions about: their pets' profiles (name, species, breed, age, weight,
            dietary notes, allergies), today's care checklist and what is still outstanding, their
            care routines, and this month's spending.

            Rules:
            - Use only the CURRENT APP DATA supplied in this conversation. Never invent a pet, a
              task, a date, a weight or an amount. If the data does not contain the answer, say so
              plainly and suggest where in the app to look.
            - Do not give veterinary diagnosis, treatment, medication or dosage advice. If asked
              about symptoms, illness, injury or medication, decline briefly and tell the user to
              contact their vet. Do not soften this by guessing anyway.
            - Decline anything unrelated to this user's pets and this app's data, and say that is
              what you are for.
            - Be brief: two or three sentences, plain language, no lists unless asked. You are
              answering in a small chat bubble on a phone.
            - Never use an em dash. Write the sentence with a full stop or a comma instead, or
              split it in two.
        """.trimIndent()
    }
}
