package com.prash.petcare.ui.assistant

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.prash.petcare.PetCareApplication
import com.prash.petcare.data.assistant.AssistantAnswer
import com.prash.petcare.data.assistant.AssistantContext
import com.prash.petcare.data.assistant.AssistantFailure
import com.prash.petcare.data.assistant.AssistantRepository
import com.prash.petcare.data.assistant.AssistantTask
import com.prash.petcare.data.assistant.AssistantTurn
import com.prash.petcare.data.repository.CareRoutineRepository
import com.prash.petcare.data.repository.CareTaskRepository
import com.prash.petcare.data.repository.ExpenseRepository
import com.prash.petcare.data.repository.PetRepository
import com.prash.petcare.data.repository.UserRepository
import com.prash.petcare.ui.common.formatCurrency
import com.prash.petcare.ui.home.HomeDashboard
import com.prash.petcare.ui.home.PetCareData
import com.prash.petcare.util.CareSchedule
import com.prash.petcare.util.MonthlySpending
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * One line in the conversation.
 *
 * A sealed interface rather than a single class with a "who said it" flag, because the five cases
 * are drawn as genuinely different things — a card on the left, a card on the right, a spinner, an
 * apology — and a `when` over a sealed type is what makes the adapter's list of view types provably
 * complete.
 *
 * **None of these carries the app's own sentences.** [Greeting] and [Failed] hold the *facts* their
 * wording needs and nothing more; the fragment turns them into words through string resources, as
 * everywhere else in this app. Only [FromUser] and [FromAssistant] carry text, because in their case
 * the text is the content — what the user typed, and what came back.
 *
 * @property id Stable across rebuilds, so DiffUtil can tell "a new message arrived" from "the same
 *   message again" and the list does not flash on every state change.
 */
sealed interface AssistantMessage {

    val id: Long

    /** The opening line: names the user and states what the assistant can see. */
    data class Greeting(override val id: Long, val firstName: String, val petCount: Int) : AssistantMessage

    /** Something the user asked. Drawn on the right. */
    data class FromUser(override val id: Long, val text: String) : AssistantMessage

    /** An answer. Drawn on the left. */
    data class FromAssistant(override val id: Long, val text: String) : AssistantMessage

    /** Standing in for an answer that is still being produced. */
    data class Thinking(override val id: Long) : AssistantMessage

    /**
     * The reply failed. Shown in the list rather than as a dialog — see [send].
     *
     * Carries *why* rather than a finished sentence: no network, a missing key and a rate limit need
     * three different things from the user, and only the reason survives as something the View layer
     * can turn into the right words. The wording itself is resolved in [AssistantAdapter].
     */
    data class Failed(override val id: Long, val reason: AssistantFailure) : AssistantMessage
}

/** Which of the three questions a suggested chip offers. The fragment supplies the wording. */
enum class SuggestionKind { OPEN_TASKS, MONTH_SPEND, PET }

/**
 * One chip under the conversation.
 *
 * @property petName Set only for [SuggestionKind.PET], naming which animal the question is about.
 */
data class Suggestion(val kind: SuggestionKind, val petName: String? = null)

/**
 * Everything the assistant screen draws.
 *
 * @property petNames The account's pets, for the header's "Ask about …" line.
 * @property messages The conversation so far, oldest first.
 * @property suggestions The chips offered beneath it.
 * @property isReplying True while an answer is in flight, which disables sending.
 */
data class AssistantUiState(
    val petNames: List<String> = emptyList(),
    val messages: List<AssistantMessage> = emptyList(),
    val suggestions: List<Suggestion> = emptyList(),
    val isReplying: Boolean = false
) {
    /**
     * The chips are an opening offer, not a permanent toolbar: once the user has asked anything the
     * row is gone for the rest of the visit. "Anything" means more than the greeting, which is the
     * only message on screen before the conversation starts.
     */
    val showSuggestions: Boolean
        get() = suggestions.isNotEmpty() && messages.all { it is AssistantMessage.Greeting }
}

/**
 * Backs the assistant screen.
 *
 * In MVVM this is the **ViewModel**: it gathers the account's own records from five repositories,
 * hands a snapshot of them to [AssistantRepository] along with the user's question, and publishes
 * the conversation for [AssistantFragment] to draw. It holds no views and no Context, and it never
 * touches a DAO.
 *
 * **Two streams, one state.** The conversation is something the user builds by typing, and the
 * context is something the database publishes; they change for completely unrelated reasons. So the
 * messages live in a [MutableStateFlow] this class owns and the context is derived from Room, and
 * [uiState] combines the two at the end. That separation is what lets a pet being renamed on another
 * screen update the header without disturbing a reply in flight.
 *
 * @param assistantRepository Where answers come from. An interface, so the stub that answers from
 *   the app's own records today can be replaced by a real backend without touching this class.
 */
class AssistantViewModel(
    userRepository: UserRepository,
    petRepository: PetRepository,
    careRoutineRepository: CareRoutineRepository,
    careTaskRepository: CareTaskRepository,
    expenseRepository: ExpenseRepository,
    private val assistantRepository: AssistantRepository,
    userId: Long
) : ViewModel() {

    /**
     * Each pet with its active routines, tasks and expenses — the same shape, built the same way, as
     * [com.prash.petcare.ui.home.HomeViewModel] builds it, and for the same reasons: `flatMapLatest`
     * rebuilds the per-pet queries when the pet list changes and cancels the previous ones, and the
     * empty case is short-circuited because `combine` over an empty list never emits.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val petCareFlow: Flow<List<PetCareData>> =
        petRepository.observePetsForOwner(userId).flatMapLatest { pets ->
            if (pets.isEmpty()) {
                flowOf(emptyList())
            } else {
                combine(
                    pets.map { pet ->
                        combine(
                            careRoutineRepository.observeActiveRoutinesForPet(pet.id),
                            careTaskRepository.observeTasksForPet(pet.id),
                            expenseRepository.observeExpensesForPet(pet.id)
                        ) { routines, tasks, expenses -> PetCareData(pet, routines, tasks, expenses) }
                    }
                ) { perPet -> perPet.toList() }
            }
        }

    /**
     * What the assistant is allowed to see, kept current as the database changes.
     *
     * Today's tasks are worked out by [HomeDashboard.todayTasks] and the month's spending by
     * [MonthlySpending.summarise] — the same functions the dashboard and the Money tab use. Reusing
     * them rather than writing the arithmetic again is what guarantees the assistant can never
     * quote a figure that disagrees with the screen the user just came from.
     */
    private val assistantContext: Flow<AssistantContext> = combine(
        userRepository.observeUser(userId),
        petCareFlow
    ) { user, petCare ->
        val now = System.currentTimeMillis()
        val today = HomeDashboard.todayTasks(
            petCare = petCare,
            todayStart = CareSchedule.startOfDay(now),
            todayEnd = CareSchedule.endOfDay(now)
        )
        val spending = MonthlySpending.summarise(petCare.flatMap { it.expenses }, now)

        AssistantContext(
            userFirstName = firstNameOf(user?.fullName),
            pets = petCare.map { it.pet },
            // Flattened here, where the pet each task belongs to is still in hand: the task row
            // itself only carries an id, and the assistant needs the name.
            todayTasks = today.map { item ->
                AssistantTask(
                    title = item.task.title,
                    petName = item.pet.name,
                    isDone = item.isDone,
                    timeOfDayMinutes = item.task.timeOfDayMinutes
                )
            },
            monthSpendTotal = spending?.total,
            monthSpendByCategory = spending?.breakdown.orEmpty(),
            // Formatted here rather than in the repository, which must not reach up into `ui` —
            // see the note on AssistantContext.monthSpendFormatted.
            monthSpendFormatted = spending?.let { formatCurrency(it.total) },
            monthSpendChangePercent = spending?.changePercent
        )
    }

    /**
     * The conversation, owned by this class because nothing else produces it.
     *
     * Seeded empty and given its greeting by [seedGreeting] once the context has loaded, so the
     * opening line can name the user rather than greeting them as nobody.
     */
    private val messages = MutableStateFlow<List<AssistantMessage>>(emptyList())

    private val isReplying = MutableStateFlow(false)

    /** The latest context, kept so [send] can hand it to the repository without re-querying. */
    private var latestContext = AssistantContext()

    val uiState: StateFlow<AssistantUiState> = combine(
        assistantContext,
        messages,
        isReplying
    ) { context, conversation, replying ->
        latestContext = context
        seedGreeting(context)

        AssistantUiState(
            petNames = context.pets.map { it.name },
            messages = messages.value,
            suggestions = suggestionsFor(context),
            isReplying = replying
        )
    }.stateIn(
        scope = viewModelScope,
        // Five seconds of grace keeps the Room queries alive across a rotation, when the old view
        // stops collecting a moment before the new one starts — so the conversation survives a
        // turn of the phone rather than restarting.
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AssistantUiState()
    )

    /**
     * Puts the opening greeting in place, exactly once.
     *
     * Guarded on the list being empty rather than on a flag, because that is the condition that
     * actually matters and it survives the ViewModel being read twice. It runs from inside the
     * `combine` so it can name the user, which is not known until the first query returns.
     */
    private fun seedGreeting(context: AssistantContext) {
        if (messages.value.isNotEmpty()) return
        messages.value = listOf(
            AssistantMessage.Greeting(
                id = nextId(),
                firstName = context.userFirstName,
                petCount = context.pets.size
            )
        )
    }

    /**
     * The three chips, built from what the account actually holds.
     *
     * Every one is a question the stub can genuinely answer — they are generated from the same three
     * cases it recognises — so a chip never offers something that comes back "I can't answer that".
     * The per-pet chip names the first pet; with no pets there is nothing to ask about and the chip
     * is left out rather than asking about a blank.
     */
    private fun suggestionsFor(context: AssistantContext): List<Suggestion> =
        buildList {
            add(Suggestion(SuggestionKind.OPEN_TASKS))
            add(Suggestion(SuggestionKind.MONTH_SPEND))
            context.pets.firstOrNull()?.let { add(Suggestion(SuggestionKind.PET, it.name)) }
        }

    /**
     * Sends a question and appends the answer.
     *
     * The user's message and a [AssistantMessage.Thinking] placeholder go in together, so the list
     * shows both the question and that something is happening in one frame rather than two. The
     * placeholder is then *replaced* by the answer, which is why it carries an id: the adapter sees
     * one item change rather than one removed and another added, and the list does not jump.
     *
     * **Failure is a message, not a crash.** Every way the call can fail — offline, no key, a bad
     * key, a rate limit, a reply that will not parse — comes back as a classified
     * [AssistantFailure] and becomes a [AssistantMessage.Failed] in the conversation, carrying the
     * reason so the screen can say which of those it was. The user reads it in place and tries
     * again; there is no dialog over the screen, no blank list and no stack trace. Cancellation is
     * rethrown rather than caught, as everywhere else in this app: it is the scope shutting the work
     * down, not the answer failing.
     *
     * Ignores blank input and anything sent while a reply is already in flight, so a double tap on
     * the send button cannot start two conversations at once.
     */
    fun send(question: String) {
        val asked = question.trim()
        if (asked.isEmpty() || isReplying.value) return

        val thinkingId = nextId()
        val withQuestion = messages.value + AssistantMessage.FromUser(nextId(), asked)
        messages.value = withQuestion + AssistantMessage.Thinking(thinkingId)
        isReplying.value = true

        // The conversation as it was actually spoken, which is not the same as what is on screen:
        // the loading placeholder has not happened yet, a previous failure is the app apologising
        // rather than anything the assistant said, and the greeting's words live in the View layer
        // and were never the model's. Sending any of those back would teach it to imitate them.
        val history = withQuestion.mapNotNull { message ->
            when (message) {
                is AssistantMessage.FromUser -> AssistantTurn(fromUser = true, text = message.text)
                is AssistantMessage.FromAssistant -> AssistantTurn(fromUser = false, text = message.text)
                else -> null
            }
        }

        viewModelScope.launch {
            val answer = try {
                assistantRepository.answer(history, latestContext)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The repository classifies everything it can foresee and returns it; this catches
                // only what escapes that, so one unexpected throw cannot take the screen down.
                AssistantAnswer.Failure(AssistantFailure.UNKNOWN)
            }

            val reply = when (answer) {
                is AssistantAnswer.Success -> AssistantMessage.FromAssistant(thinkingId, answer.text)
                is AssistantAnswer.Failure -> AssistantMessage.Failed(thinkingId, answer.reason)
            }

            messages.value = messages.value.map { if (it.id == thinkingId) reply else it }
            isReplying.value = false
        }
    }

    /** The first word of a name, or empty — the same rule the dashboard's greeting uses. */
    private fun firstNameOf(fullName: String?): String =
        fullName.orEmpty().trim().substringBefore(' ')

    /**
     * Ids for messages, counting up from zero.
     *
     * A counter rather than a timestamp: two messages added in the same millisecond — which the
     * question and its placeholder always are — would otherwise share an id and confuse DiffUtil
     * into drawing one of them twice.
     */
    private var lastId = 0L
    private fun nextId(): Long = ++lastId

    companion object {

        /** Builds the ViewModel for the signed-in account, read from the session. */
        fun factory(userId: Long): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application =
                    this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as PetCareApplication
                AssistantViewModel(
                    userRepository = application.userRepository,
                    petRepository = application.petRepository,
                    careRoutineRepository = application.careRoutineRepository,
                    careTaskRepository = application.careTaskRepository,
                    expenseRepository = application.expenseRepository,
                    assistantRepository = application.assistantRepository,
                    userId = userId
                )
            }
        }
    }
}
