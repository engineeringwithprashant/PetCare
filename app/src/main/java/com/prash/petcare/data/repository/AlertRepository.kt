package com.prash.petcare.data.repository

import com.prash.petcare.data.alert.Alert
import com.prash.petcare.data.alert.AlertContent
import com.prash.petcare.data.alert.AlertTarget
import com.prash.petcare.data.alert.TipKind
import com.prash.petcare.data.local.AlertReadStore
import com.prash.petcare.data.local.entity.CareRoutine
import com.prash.petcare.data.local.entity.CareTask
import com.prash.petcare.data.local.entity.Expense
import com.prash.petcare.data.local.entity.Pet
import com.prash.petcare.data.local.entity.TaskCategory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import java.util.Calendar
import java.util.TimeZone

/** Works out what the user should be told about, from data they already have. */
class AlertRepository(
    private val petRepository: PetRepository,
    private val careRoutineRepository: CareRoutineRepository,
    private val careTaskRepository: CareTaskRepository,
    private val expenseRepository: ExpenseRepository,
    private val alertReadStore: AlertReadStore
) {

    /** One pet with everything the alert rules ask of it. */
    private data class PetData(
        val pet: Pet,
        val routines: List<CareRoutine>,
        val tasks: List<CareTask>,
        val expenses: List<Expense>
    )

    /** Every alert for an account, newest first and capped — with each one's read state applied. */
    fun observeAlerts(userId: Long): Flow<List<Alert>> =
        combine(observePetData(userId), alertReadStore.readIds) { pets, readIds ->
            buildAlerts(pets, readIds, System.currentTimeMillis())
        }

    /** Tells the read store which alert ids still exist, so it can forget the rest. */
    fun forgetAlertsOtherThan(ids: Set<String>) {
        alertReadStore.keepOnly(ids)
    }

    /** Marks one alert read — what tapping a card does, alongside opening the screen it points at. */
    fun markRead(alertId: String) {
        alertReadStore.markRead(alertId)
    }

    /** Marks a whole set read — the "MARK READ" button. */
    fun markRead(alertIds: Set<String>) {
        alertReadStore.markRead(alertIds)
    }

    /** Each pet with its routines, tasks and expenses. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observePetData(userId: Long): Flow<List<PetData>> =
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
                        ) { routines, tasks, expenses ->
                            PetData(pet, routines, tasks, expenses)
                        }
                    }
                ) { perPet -> perPet.toList() }
            }
        }

    /** Builds every alert for every pet, newest first and capped. */
    private fun buildAlerts(pets: List<PetData>, readIds: Set<String>, now: Long): List<Alert> {
        val alerts = mutableListOf<Alert>()

        pets.forEach { data ->
            alerts += careReminders(data, now)
            alerts += missedTasks(data, now)
            alerts += activity(data, now)
            alerts += milestone(data, now)
        }

        // Tips are added from a pool of their own.
        alerts += tips(pets)

        return alerts
            .sortedByDescending { it.occurredAt }
            .take(MAX_ALERTS)
            .map { alert -> alert.copy(isRead = alert.id in readIds) }
    }

    /** Health care coming up: a vet visit or a medication dose due within the next week. */
    private fun careReminders(data: PetData, now: Long): List<Alert> =
        data.tasks
            .filter { task ->
                !task.isCompleted &&
                    task.category in HEALTH_CATEGORIES &&
                    task.dueAt > now &&
                    task.dueAt <= now + REMINDER_WINDOW_DAYS * MILLIS_PER_DAY
            }
            .map { task ->
                Alert(
                    // The due time is part of the id as well as the task's own.
                    id = "reminder:${task.id}:${task.dueAt}",
                    content = AlertContent.CareReminder(
                        petName = data.pet.name,
                        taskTitle = task.title,
                        category = task.category,
                        dueAt = task.dueAt
                    ),
                    occurredAt = maxOf(
                        task.createdAt,
                        task.dueAt - REMINDER_WINDOW_DAYS * MILLIS_PER_DAY
                    ),
                    isRead = false,
                    target = AlertTarget.Tasks
                )
            }

    /** Care whose time has gone by without it being checked off. */
    private fun missedTasks(data: PetData, now: Long): List<Alert> =
        data.tasks
            .filter { task ->
                !task.isCompleted &&
                    task.dueAt < now &&
                    task.dueAt >= now - HISTORY_WINDOW_DAYS * MILLIS_PER_DAY
            }
            .map { task ->
                Alert(
                    id = "missed:${task.id}:${task.dueAt}",
                    content = AlertContent.MissedTask(
                        taskTitle = task.title,
                        petName = data.pet.name
                    ),
                    // The moment it was missed is the moment it fell due.
                    occurredAt = task.dueAt,
                    isRead = false,
                    target = AlertTarget.Tasks
                )
            }

    /** Recent movement on a pet's record: expenses added in the last few days. */
    private fun activity(data: PetData, now: Long): List<Alert> {
        val recent = data.expenses.filter {
            it.createdAt >= now - ACTIVITY_WINDOW_DAYS * MILLIS_PER_DAY
        }
        val newest = recent.maxByOrNull { it.createdAt } ?: return emptyList()

        return listOf(
            Alert(
                id = "activity:${data.pet.id}:${newest.id}",
                content = AlertContent.Activity(
                    petName = data.pet.name,
                    expenseCount = recent.size
                ),
                occurredAt = newest.createdAt,
                isRead = false,
                target = AlertTarget.PetDetail(data.pet.id)
            )
        )
    }

    /** A pet reaching a round age, from its recorded date of birth. */
    private fun milestone(data: PetData, now: Long): List<Alert> {
        val dateOfBirth = data.pet.dateOfBirth ?: return emptyList()
        val months = completedMonths(dateOfBirth, now)

        // 0 would be "turns 0 months old", which is not a milestone; over a year, only whole years.
        if (months < 1) return emptyList()
        if (months >= MONTHS_PER_YEAR && months % MONTHS_PER_YEAR != 0) return emptyList()

        val anniversary = anniversaryOf(dateOfBirth, months)
        if (anniversary < now - MILESTONE_WINDOW_DAYS * MILLIS_PER_DAY) return emptyList()

        return listOf(
            Alert(
                // The age is part of the id, so next month's milestone is a new alert.
                id = "milestone:${data.pet.id}:$months",
                content = AlertContent.Milestone(petName = data.pet.name, months = months),
                occurredAt = anniversary,
                isRead = false,
                target = AlertTarget.PetDetail(data.pet.id)
            )
        )
    }

    /** Suggestions, each prompted by something a pet's record is missing. */
    private fun tips(pets: List<PetData>): List<Alert> =
        pets.mapNotNull { data ->
            val kind = when {
                data.pet.weightKg == null -> TipKind.NO_WEIGHT
                data.pet.dateOfBirth == null -> TipKind.NO_DATE_OF_BIRTH
                data.routines.isEmpty() -> TipKind.NO_ROUTINE
                data.pet.photoPath.isNullOrBlank() -> TipKind.NO_PHOTO
                else -> null
            } ?: return@mapNotNull null

            Alert(
                id = "tip:${data.pet.id}:${kind.name}",
                content = AlertContent.Tip(kind = kind, petName = data.pet.name),
                occurredAt = data.pet.createdAt,
                isRead = false,
                target = AlertTarget.PetDetail(data.pet.id)
            )
        }
            .sortedByDescending { it.occurredAt }
            .take(MAX_TIPS)

    /** A pet's age in completed months. */
    private fun completedMonths(dateOfBirthMillis: Long, now: Long): Int {
        val utc = TimeZone.getTimeZone("UTC")
        val born = Calendar.getInstance(utc).apply { timeInMillis = dateOfBirthMillis }
        val today = Calendar.getInstance(utc).apply { timeInMillis = now }

        var months = (today.get(Calendar.YEAR) - born.get(Calendar.YEAR)) * MONTHS_PER_YEAR +
            (today.get(Calendar.MONTH) - born.get(Calendar.MONTH))

        if (today.get(Calendar.DAY_OF_MONTH) < born.get(Calendar.DAY_OF_MONTH)) {
            months--
        }

        return months.coerceAtLeast(0)
    }

    /** A pet's Nth-month birthday, as local midnight on that calendar day. */
    private fun anniversaryOf(dateOfBirthMillis: Long, months: Int): Long {
        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            timeInMillis = dateOfBirthMillis
            add(Calendar.MONTH, months)
        }

        return Calendar.getInstance().apply {
            clear()
            set(
                utc.get(Calendar.YEAR),
                utc.get(Calendar.MONTH),
                utc.get(Calendar.DAY_OF_MONTH)
            )
        }.timeInMillis
    }

    private companion object {

        /** The two categories worth being reminded of in advance — see [careReminders]. */
        val HEALTH_CATEGORIES = setOf(TaskCategory.VET_VISIT, TaskCategory.MEDICATION)

        /** How far ahead a vet or medication date is announced. */
        const val REMINDER_WINDOW_DAYS = 7L

        /** How far back missed care is still reported. */
        const val HISTORY_WINDOW_DAYS = 14L

        /** How recently an expense must have been added to count as activity. */
        const val ACTIVITY_WINDOW_DAYS = 7L

        /** How recently a birthday must have passed for the card to say "this week". */
        const val MILESTONE_WINDOW_DAYS = 7L

        /** The most alerts the screen will show, newest first. */
        const val MAX_ALERTS = 20

        /** The most tips shown at once, so they cannot crowd out care — see [tips]. */
        const val MAX_TIPS = 2

        const val MONTHS_PER_YEAR = 12
        const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000
    }
}
