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
import com.prash.petcare.data.local.SettingsStore
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

/** One line in the conversation. */
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

    /** The reply failed. Shown in the list rather than as a dialog — see [send]. */
    data class Failed(override val id: Long, val reason: AssistantFailure) : AssistantMessage
}

/** Which of the three questions a suggested chip offers. The fragment supplies the wording. */
enum class SuggestionKind { OPEN_TASKS, MONTH_SPEND, PET }

/** One chip under the conversation. */
data class Suggestion(val kind: SuggestionKind, val petName: String? = null)

/** Everything the assistant screen draws. */
data class AssistantUiState(
    val petNames: List<String> = emptyList(),
    val messages: List<AssistantMessage> = emptyList(),
    val suggestions: List<Suggestion> = emptyList(),
    val isReplying: Boolean = false
) {
    /** The chips are an opening offer, not a permanent toolbar. */
    val showSuggestions: Boolean
        get() = suggestions.isNotEmpty() && messages.all { it is AssistantMessage.Greeting }
}

/** Backs the assistant screen. */
class AssistantViewModel(
    userRepository: UserRepository,
    petRepository: PetRepository,
    careRoutineRepository: CareRoutineRepository,
    careTaskRepository: CareTaskRepository,
    expenseRepository: ExpenseRepository,
    private val assistantRepository: AssistantRepository,
    private val settingsStore: SettingsStore,
    userId: Long
) : ViewModel() {

    /** Each pet with its active routines, tasks and expenses — the same shape, built the same way. */
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

    /** What the assistant is allowed to see, kept current as the database changes. */
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
            // Flattened here, where the pet each task belongs to is still in hand.
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
            // Formatted here rather than in the repository, which must not reach up into `ui`.
            monthSpendFormatted = spending?.let {
                formatCurrency(it.total, settingsStore.currency.value.symbol)
            },
            monthSpendChangePercent = spending?.changePercent
        )
    }

    /** The conversation, owned by this class because nothing else produces it. */
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
        // Five seconds of grace keeps the Room queries alive across a rotation.
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AssistantUiState()
    )

    /** Puts the opening greeting in place, exactly once. */
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

    /** The three chips, built from what the account actually holds. */
    private fun suggestionsFor(context: AssistantContext): List<Suggestion> =
        buildList {
            add(Suggestion(SuggestionKind.OPEN_TASKS))
            add(Suggestion(SuggestionKind.MONTH_SPEND))
            context.pets.firstOrNull()?.let { add(Suggestion(SuggestionKind.PET, it.name)) }
        }

    /** Sends a question and appends the answer. */
    fun send(question: String) {
        val asked = question.trim()
        if (asked.isEmpty() || isReplying.value) return

        val thinkingId = nextId()
        val withQuestion = messages.value + AssistantMessage.FromUser(nextId(), asked)
        messages.value = withQuestion + AssistantMessage.Thinking(thinkingId)
        isReplying.value = true

        // The conversation as it was actually spoken, which is not the same as what is on screen.
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
                // The repository classifies everything it can foresee and returns.
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

    /** Ids for messages, counting up from zero. */
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
                    settingsStore = application.settingsStore,
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
