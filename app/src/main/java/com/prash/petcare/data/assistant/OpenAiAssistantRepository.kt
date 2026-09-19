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
 * how the model is instructed to behave, turning the account's records into the context it answers
 * from, and classifying every way the call can fail into something the screen can draw.
 *
 * **What the assistant is.** A general assistant that happens to know this user's animals. It
 * answers whatever is asked, on any subject, and reaches for the account's records whenever the
 * question is about the user's own pets, so "how much should she be eating" is answered about the
 * eleven-month-old spaniel on file rather than about spaniels in the abstract. On pet care it is
 * meant to be properly useful: feeding, portions, exercise, grooming, training, behaviour,
 * enrichment, breed traits, travel and settling a new arrival are all subjects it answers in full.
 *
 * **What leaves the device.** The system prompt, a compact summary of the account's pets, today's
 * tasks and this month's spending, and the conversation so far. No identifiers, no photographs, no
 * raw database rows — [AssistantContext] is the whole of it, which is what lets the screen's header
 * state plainly what the assistant can see.
 *
 * **The one subject handled carefully.** An assistant attached to a pet care app will be asked about
 * a sick animal, and the useful answer and the harmful one are not the same thing. Diagnosing, and
 * naming a treatment or a dose, is the harmful part: this has examined nothing. Explaining what a
 * sign usually means, what is safe to do now, and which signs mean the animal needs seeing tonight
 * rather than next week is the useful part, and refusing that outright — which is what this used to
 * do — helps nobody at eleven at night. The system prompt draws the line at exactly that point and
 * ends those answers by naming the vet or the emergency clinic. See [SYSTEM_PROMPT].
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
     * **Why the allergy and diet lines earn their place.** They were once just profile fields being
     * read back. Now the assistant gives real feeding and care advice, so they are what makes that
     * advice safe for this particular animal: the prompt requires them to be checked before any food,
     * treat or supplement is suggested, and a recommendation that ignored a recorded allergy would be
     * the worst thing this feature could produce.
     *
     * Ages are given in months and weights in kilograms with their units named, so the model never
     * has to infer a unit. Nothing here is user-facing, so it is plain English rather than string
     * resources — this text is read by the model, never shown on screen.
     */
    private fun describe(context: AssistantContext): String = buildString {
        // The heading the system prompt refers to by name. It says these are the facts about *this
        // account* rather than "the only facts you may use", which is what the old wording said and
        // what made the assistant refuse to answer anything it could not read out of a row. The
        // distinction the prompt then draws is between this account's records, which it must not
        // invent, and general knowledge, which it is free to use.
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
         * What the assistant is, what it knows, and the one subject it handles carefully.
         *
         * **Why it is this long.** A short prompt was what produced the behaviour this replaces: an
         * assistant that turned away anything not already in the database, including ordinary
         * questions about feeding and training that a pet care app is the obvious place to ask. The
         * length here is almost entirely *permission* — naming the subjects it should engage with, so
         * that a model which defaults to caution on anything touching an animal's health does not
         * quietly narrow itself back down to reading rows aloud.
         *
         * **Three ideas, in the order they matter.**
         *
         * 1. *General assistant first.* It answers whatever is asked, on any subject. Refusing a
         *    question for being off-topic was never protecting anything; it only made the feature
         *    less useful than the chat app already on the phone.
         *
         * 2. *Grounded in this account's records.* The pet block is supplied fresh on every request,
         *    and the prompt insists that answers about the user's animals are about *their* animals
         *    by name. It also draws the line the other way round, which is the part worth being
         *    precise about: the records are the only facts it has **about this account**, and it must
         *    not invent a weight or a cost. Ordinary world knowledge is not covered by that rule, and
         *    saying so explicitly is what stops "do not invent data" being read as "do not know
         *    anything".
         *
         * 3. *Illness is handled, not dodged.* This is an app about animals' health, so it will be
         *    asked about symptoms, and the previous flat refusal was the wrong answer to that: an
         *    owner at eleven at night with a retching dog is not helped by being told the subject is
         *    closed. What it must not do is narrower than the whole subject. It must not name a
         *    diagnosis, and it must not give a treatment or a dose, because it has not examined the
         *    animal. Everything around that is genuinely useful and is asked for here: what a sign
         *    commonly means, what is safe to do now, and above all which signs mean the animal needs
         *    seeing at once. The urgent signs are listed by name rather than left to the model, since
         *    "see a vet if worried" is advice that has never once told anybody anything.
         *
         * The last rule is house style rather than safety: the app's own copy uses no em dashes, and
         * a model left to itself produces them constantly, so its replies would be the one place in
         * the app where they appeared.
         */
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
