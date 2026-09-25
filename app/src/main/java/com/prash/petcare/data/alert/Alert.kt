package com.prash.petcare.data.alert

import com.prash.petcare.data.local.entity.TaskCategory

/**
 * One alert: what it says, when it became true, and where tapping it goes.
 *
 * **Why these are data classes and not database rows.** Nothing in the app *writes* an alert. Every
 * one of them is a reading of data the user already has — a vet visit coming up, a task whose time
 * passed unticked, an expense added this week, a pet reaching a month old, a record left blank. They
 * are recomputed from those sources whenever the sources change, which is what makes them
 * self-correcting: tick the missed task off and its alert stops existing, with nothing to delete. See
 * [com.prash.petcare.data.repository.AlertRepository] for the rules behind each one.
 *
 * **Why this lives in `data` rather than beside the notifications screen, where it started.** Because
 * that screen stopped being the only thing that needs it. The header on every screen in the app now
 * carries a bell with the unread count on it, which means the alerts have to be derived once,
 * centrally, rather than by whichever ViewModel happens to want them — and a repository in `data`
 * cannot import a model out of `ui` without pointing the app's dependencies backwards. So the model
 * moved to the layer that produces it, and the notifications screen imports it like any other data.
 *
 * Everything here is plain data with no Android types in it, so it can be built without a Context; the
 * wording, the badge codes and the dates are added by
 * [com.prash.petcare.ui.notifications.NotificationsAdapter], which has string resources and the user's
 * locale to do it with.
 */

/**
 * What one alert is about, together with the facts needed to word it.
 *
 * A sealed interface with a payload per kind rather than one class holding a nullable field for every
 * possibility. Each alert carries exactly the data its own sentence needs, the compiler checks that
 * the adapter words every kind, and a sixth kind added later fails to compile until it has been
 * given a sentence — rather than quietly drawing as a blank card.
 */
sealed interface AlertContent {

    /**
     * Health care coming up: a vet visit or a medication dose due in the next few days.
     *
     * @property petName Whose appointment it is.
     * @property taskTitle The task as the user wrote it — "Rabies booster".
     * @property category Which of the two health categories it is, which decides the badge's code.
     * @property dueAt When it falls due, so the card can say "tomorrow" or name the day.
     */
    data class CareReminder(
        val petName: String,
        val taskTitle: String,
        val category: TaskCategory,
        val dueAt: Long
    ) : AlertContent

    /**
     * A task whose time has gone by without it being checked off.
     *
     * @property taskTitle The task as the user wrote it — "Morning meds".
     * @property petName Whose care was missed.
     */
    data class MissedTask(
        val taskTitle: String,
        val petName: String
    ) : AlertContent

    /**
     * Recent activity on a pet's record: expenses added in the last few days.
     *
     * **This is the honest version of the design's "shared care" card.** That card names a person —
     * "Sysy added 2 expenses for Ddjw" — and the app stores no such thing: a pet has a single
     * `ownerId`, no table records who performed an action, and delegation hands a checklist to an SMS
     * or email intent without keeping any record of the recipient. So the card reports what is
     * genuinely known — that the record moved, for which pet, how recently — and names nobody.
     *
     * @property petName Whose record it is.
     * @property expenseCount How many expenses were added in the window.
     */
    data class Activity(
        val petName: String,
        val expenseCount: Int
    ) : AlertContent

    /**
     * A pet reaching a round age.
     *
     * @property petName Whose birthday it is.
     * @property months The age just reached, in whole months. The adapter turns this into "1 month"
     *   or "3 years" — see [com.prash.petcare.data.repository.AlertRepository] for which anniversaries
     *   are worth an alert.
     */
    data class Milestone(
        val petName: String,
        val months: Int
    ) : AlertContent

    /**
     * A suggestion, prompted by something the pet's record is missing.
     *
     * @property kind Which gap was found, which is the whole of the tip's wording.
     * @property petName Whose record has the gap, for the card's supporting line.
     */
    data class Tip(
        val kind: TipKind,
        val petName: String
    ) : AlertContent
}

/**
 * The gaps in a pet's record that are worth a tip.
 *
 * Each constant is one blank column, so every tip the screen shows is a true statement about this
 * user's data rather than a fortune-cookie line shown to everybody. A tip disappears as soon as the
 * gap it names is filled in, which is what stops the screen nagging about something already done.
 */
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

/**
 * Where tapping an alert takes the user.
 *
 * Expressed as *which screen*, not as a navigation id: ids belong to the graph, and data that named
 * one would be deciding navigation on the View's behalf.
 * [com.prash.petcare.ui.notifications.NotificationsFragment] turns these into `navigate` calls.
 */
sealed interface AlertTarget {

    /** One pet's detail screen — for alerts about the animal itself. */
    data class PetDetail(val petId: Long) : AlertTarget

    /** The Tasks tab — for alerts about care that is due, late, or was missed. */
    data object Tasks : AlertTarget
}

/**
 * One alert on the screen.
 *
 * @property id Stable across rebuilds and unique among the alerts on screen. It encodes what the
 *   alert was derived from ("missed:41:1757980800000"), which gives it two jobs at once: DiffUtil
 *   uses it to recognise a card it has already drawn, and [com.prash.petcare.data.local.AlertReadStore]
 *   uses it to remember that this alert has been read. The second is why it has to describe the
 *   *occurrence* and not just the source row — a repeating task missed again next week is a new alert
 *   and should arrive unread.
 * @property content What it says.
 * @property occurredAt When it became true, as a wall-clock timestamp. This is the card's age
 *   ("2h ago", "Sep 10") and what sorts the list and splits it into TODAY and EARLIER.
 * @property isRead Whether the user has already seen it — the pale fill, the badge colour, the red
 *   dot, and the number on the header's bell all follow this.
 * @property target Which screen a tap opens.
 */
data class Alert(
    val id: String,
    val content: AlertContent,
    val occurredAt: Long,
    val isRead: Boolean,
    val target: AlertTarget
)
