package com.prash.petcare.data.alert

import com.prash.petcare.data.local.entity.TaskCategory

/** One alert: what it says, when it became true, and where tapping it goes. */

/** What one alert is about, together with the facts needed to word it. */
sealed interface AlertContent {

    /** Health care coming up: a vet visit or a medication dose due in the next few days. */
    data class CareReminder(
        val petName: String,
        val taskTitle: String,
        val category: TaskCategory,
        val dueAt: Long
    ) : AlertContent

    /** A task whose time has gone by without it being checked off. */
    data class MissedTask(
        val taskTitle: String,
        val petName: String
    ) : AlertContent

    /** Recent activity on a pet's record: expenses added in the last few days. */
    data class Activity(
        val petName: String,
        val expenseCount: Int
    ) : AlertContent

    /** A pet reaching a round age. */
    data class Milestone(
        val petName: String,
        val months: Int
    ) : AlertContent

    /** A suggestion, prompted by something the pet's record is missing. */
    data class Tip(
        val kind: TipKind,
        val petName: String
    ) : AlertContent
}

/** The gaps in a pet's record that are worth a tip. */
enum class TipKind {
    /** No weight has ever been recorded, so nothing can be charted over time. */
    NO_WEIGHT,

    /** No date of birth, so the pet has no age, no milestones and no age-appropriate care. */
    NO_DATE_OF_BIRTH,

    /** No care routine, so the Tasks tab has nothing to schedule for this pet. */
    NO_ROUTINE,

    /** No photograph, so every card for this pet draws the placeholder. */
    NO_PHOTO
}

/** Where tapping an alert takes the user. */
sealed interface AlertTarget {

    /** One pet's detail screen — for alerts about the animal itself. */
    data class PetDetail(val petId: Long) : AlertTarget

    /** The Tasks tab — for alerts about care that is due, late, or was missed. */
    data object Tasks : AlertTarget
}

/** One alert on the screen. */
data class Alert(
    val id: String,
    val content: AlertContent,
    val occurredAt: Long,
    val isRead: Boolean,
    val target: AlertTarget
)
