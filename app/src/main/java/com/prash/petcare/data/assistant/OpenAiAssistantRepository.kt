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

/** Answers the assistant screen's questions with OpenAI's chat completions endpoint. */
class OpenAiAssistantRepository(
    private val api: OpenAiApi,
    private val apiKey: String,
    private val model: String = DEFAULT_MODEL
) : AssistantRepository {

    /** Asks the model, and returns either its reply or a classified failure. */
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
                // Also covers a body that could not be read to the end.
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
                // A malformed body surfaces here as Gson's JsonSyntaxException, along with anything else unforeseen.
                AssistantAnswer.Failure(AssistantFailure.BAD_RESPONSE)
            }
        }
    }

    /** Assembles the request: the rules, then the data, then the conversation. */
    private fun buildMessages(
        history: List<AssistantTurn>,
        context: AssistantContext
    ): List<ChatMessage> = buildList {
        add(ChatMessage(ROLE_SYSTEM, SYSTEM_PROMPT))
        add(ChatMessage(ROLE_SYSTEM, describe(context)))

        // Only the most recent turns. A conversation left open all day would otherwise grow the request without bound.
        history.takeLast(MAX_HISTORY_TURNS).forEach { turn ->
            add(ChatMessage(if (turn.fromUser) ROLE_USER else ROLE_ASSISTANT, turn.text))
        }
    }

    /** The account's records as a compact block of text. */
    private fun describe(context: AssistantContext): String = buildString {
        // The heading the system prompt refers to by name. It says these are the facts about this account.
        appendLine("THE USER'S PET RECORDS (this account's own data, current as of now):")

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
        // The division is in Double because the average month is not a whole number of days.
        return (days / DAYS_PER_MONTH).toLong().coerceAtLeast(0L)
    }

    /** Minutes past midnight as "18:00" — a 24-hour clock, which needs no locale to be unambiguous. */
    private fun clockOf(timeOfDayMinutes: Int): String =
        String.format(Locale.ROOT, "%02d:%02d", timeOfDayMinutes / 60, timeOfDayMinutes % 60)

    companion object {

        /** The model. `gpt-4o-mini` is chosen as the default. */
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

        /** What the assistant is, what it knows, and the one subject it handles carefully. */
        private val SYSTEM_PROMPT = """
            You are the assistant inside PetCare, a pet care app. You are a general assistant who
            also happens to know this user's pets. Answer whatever they ask, on any subject, as
            capably and as fully as you would anywhere else. There is no topic you turn away for
            being off-subject, and you never tell the user that you only handle their app data.

            WHAT YOU KNOW ABOUT THEIR PETS
            A block headed THE USER'S PET RECORDS is supplied on every request: their pets' names,
            species, breed, age, weight, dietary notes and allergies, today's care checklist, their
            routines, and this month's spending. Whenever a question touches their animals, answer
            from those records and name the animal. Say "Momo is eleven months, so she is still on
            puppy food" rather than "for a puppy, generally".

            Those records are the only facts you have about this account. Never invent a pet, a task,
            a date, a weight or an amount that is not in them. If a record would answer the question
            and it is not there, say what is missing and where in the app to add it. That limit
            applies to their records only. Everything you know about the world, animals included, is
            yours to use freely.

            PET CARE
            Be genuinely useful here and go into detail when detail helps. Feeding and nutrition, how
            much to feed for a given species, weight, life stage and activity level, exercise needs,
            grooming and coat care, house training and obedience, behaviour problems, enrichment and
            play, breed traits and what they mean day to day, travelling with an animal, and settling
            a newly arrived one are all yours to answer properly.

            Answer for the animal in front of you. Read the stored allergies, dietary notes and any
            recorded condition before suggesting a food, a treat, a supplement or an activity, and
            say plainly when one of those rules a suggestion out. Base portions on the stored species,
            weight and age, and ask for whatever figure you need if it is missing.

            WHEN AN ANIMAL MAY BE UNWELL
            Symptoms, injury, suspected poisoning and medication are the one subject where you hold
            something back. Being unhelpful is not how you do it, and you never close the subject.

            Do: explain what the sign commonly means and how it is usually approached, say what is
            safe to do right now, describe what to watch for over the next few hours, and set out
            which signs mean the animal must be seen at once rather than tomorrow. Be specific about
            the urgent ones, for example collapse or sudden weakness, laboured or open mouthed
            breathing, pale or blue gums, a swollen or hard abdomen, repeated unproductive retching,
            seizures, bleeding that will not stop, straining to pass urine, repeated vomiting or
            diarrhoea in a small or young animal, a suspected toxin or a swallowed object, and
            anything at all in a very young, very old, pregnant or already ill animal.

            Do not: state a diagnosis as though it were this animal's, tell the user what to treat it
            with, or give a dose of any medicine, prescription or over the counter. You have not
            examined the animal and cannot examine it, so say so when it matters.

            End answers like these by pointing at their vet, or at an emergency clinic or an animal
            poison line when the signs you have listed are urgent. Put it as the next step to take,
            not as a disclaimer standing in place of an answer.

            HOW TO WRITE
            Match the length to the question. A factual one about their records takes a sentence or
            two. A question about feeding, training or settling an animal deserves a full answer, with
            short headings or a numbered list where that makes it easier to follow. You are writing in
            a chat on a phone, so keep paragraphs short and do not pad.

            Never use an em dash. Write the sentence with a full stop or a comma instead, or split it
            in two.
        """.trimIndent()
    }
}
