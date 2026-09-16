package com.prash.petcare.ui.notifications

import android.content.Context
import android.content.res.ColorStateList
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.R as MaterialR
import com.google.android.material.color.MaterialColors
import com.prash.petcare.R
import com.prash.petcare.data.local.entity.TaskCategory
import com.prash.petcare.databinding.ItemAlertBinding
import com.prash.petcare.databinding.ItemAlertSectionHeaderBinding
import com.prash.petcare.util.CareSchedule
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Draws the notifications list: the "TODAY" and "EARLIER" headings and every alert card under them.
 *
 * Part of the **View** layer, and it is where every alert becomes *words*. [NotificationsViewModel]
 * publishes structured facts — this pet, that task, this many expenses, that many months — and this
 * class turns them into the sentence on the card, the code on its badge and the age beside its type,
 * using string resources and the device's locale. That division is what keeps the ViewModel free of a
 * Context and the wording out of the business rules.
 *
 * It decides nothing else: which alerts exist, what order they are in and whether each has been read
 * are all settled before the list reaches here.
 *
 * Two row layouts share one list through RecyclerView *view types* — [getItemViewType] labels each
 * position and the adapter keeps a separate recycling pool per type, so a heading is never reused as
 * a card.
 *
 * A [ListAdapter], which diffs each new list against the previous one off the main thread and redraws
 * only the rows that changed. That matters more here than on most screens: marking one alert read
 * republishes the whole list, and without diffing every card on screen would flash.
 *
 * @property onAlertClick Called with a tapped alert. A lambda so navigation stays in the fragment.
 */
class NotificationsAdapter(
    private val onAlertClick: (Alert) -> Unit
) : ListAdapter<AlertListItem, RecyclerView.ViewHolder>(AlertDiffCallback) {

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is AlertListItem.SectionHeader -> VIEW_TYPE_SECTION_HEADER
        is AlertListItem.AlertRow -> VIEW_TYPE_ALERT
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)

        return when (viewType) {
            VIEW_TYPE_SECTION_HEADER -> SectionHeaderViewHolder(
                ItemAlertSectionHeaderBinding.inflate(inflater, parent, false)
            )

            VIEW_TYPE_ALERT -> AlertViewHolder(
                ItemAlertBinding.inflate(inflater, parent, false),
                onAlertClick
            )

            // Unreachable: getItemViewType only returns the two constants above. Throwing means a
            // future row type fails loudly here instead of silently drawing as the wrong thing.
            else -> throw IllegalArgumentException("Unknown alert view type: $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is AlertListItem.SectionHeader -> (holder as SectionHeaderViewHolder).bind(item.section)
            is AlertListItem.AlertRow -> (holder as AlertViewHolder).bind(item.alert)
        }
    }

    /** "TODAY" or "EARLIER". */
    class SectionHeaderViewHolder(
        private val binding: ItemAlertSectionHeaderBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(section: AlertSection) {
            binding.alertSectionLabel.setText(
                when (section) {
                    AlertSection.TODAY -> R.string.notifications_section_today
                    AlertSection.EARLIER -> R.string.notifications_section_earlier
                }
            )
        }
    }

    /** One alert card: the type code, the sentence, the meta line and the unread mark. */
    class AlertViewHolder(
        private val binding: ItemAlertBinding,
        private val onAlertClick: (Alert) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(alert: Alert) {
            val context = binding.root.context

            // Re-set on every bind: rows are recycled, and a listener left from a previous bind would
            // open whichever alert used to occupy this card.
            binding.root.setOnClickListener { onAlertClick(alert) }

            val title = titleOf(context, alert.content)
            val typeLabel = context.getString(typeLabelOf(alert.content))
            val meta = context.getString(
                R.string.alert_meta_format,
                typeLabel,
                formatAge(context, alert.occurredAt)
            )

            binding.alertBadgeText.setText(badgeOf(alert.content))
            binding.alertTitleText.text = title
            binding.alertMetaText.text = meta

            applyReadState(alert.isRead)

            // One spoken sentence per card, in place of a code, a title and a meta line read as three
            // unrelated fragments. The unread mark is a shape with no text, so what it means is added
            // here — otherwise a TalkBack user would have no way to tell a new alert from an old one.
            binding.root.contentDescription = listOfNotNull(
                title,
                meta,
                context.getString(R.string.cd_alert_unread).takeIf { !alert.isRead }
            ).joinToString(context.getString(R.string.list_separator))
        }

        /**
         * Colours the card for whether it has been read.
         *
         * An unread card takes the pale accent wash and an accent badge; a read one the plain surface
         * and a grey badge. The same treatment the Places tab gives its featured row, and resolved
         * from theme attributes for the same reason: the wash is a pale yellow in light mode and a
         * deep olive in dark, and hard-coding either would break the other.
         *
         * Every branch sets every property. A view holder is recycled, so leaving one out would let
         * an unread card's yellow badge survive onto the read alert that reuses it.
         */
        private fun applyReadState(isRead: Boolean) {
            val context = binding.root.context

            binding.root.setCardBackgroundColor(
                MaterialColors.getColor(
                    binding.root,
                    if (isRead) MaterialR.attr.colorSurface else MaterialR.attr.colorPrimaryContainer
                )
            )

            if (isRead) {
                binding.alertBadgeText.backgroundTintList = ColorStateList.valueOf(
                    MaterialColors.getColor(binding.root, MaterialR.attr.colorSurfaceVariant)
                )
                binding.alertBadgeText.setTextColor(
                    MaterialColors.getColor(binding.root, MaterialR.attr.colorOnSurfaceVariant)
                )
            } else {
                // The accent is a fixed value rather than a theme role: it is the same yellow in both
                // modes, with the near-black guaranteed legible on it — see colors.xml.
                binding.alertBadgeText.backgroundTintList =
                    ContextCompat.getColorStateList(context, R.color.accent_yellow)
                binding.alertBadgeText.setTextColor(
                    ContextCompat.getColor(context, R.color.on_accent_yellow)
                )
            }

            binding.alertUnreadDot.isVisible = !isRead
        }
    }

    /**
     * Rows are the same row when they have the same [AlertListItem.itemId]; their contents have
     * changed when anything drawn has, which the data classes' generated `equals` covers — including
     * the read flag, so marking one alert read animates that card alone.
     */
    private object AlertDiffCallback : DiffUtil.ItemCallback<AlertListItem>() {
        override fun areItemsTheSame(oldItem: AlertListItem, newItem: AlertListItem): Boolean =
            oldItem.itemId == newItem.itemId

        override fun areContentsTheSame(oldItem: AlertListItem, newItem: AlertListItem): Boolean =
            oldItem == newItem
    }

    companion object {
        private const val VIEW_TYPE_SECTION_HEADER = 0
        private const val VIEW_TYPE_ALERT = 1

        private const val MONTHS_PER_YEAR = 12

        /**
         * The sentence on the card.
         *
         * One exhaustive `when` over [AlertContent], which is what makes adding a sixth kind of alert
         * a compile error here until it has been given words — rather than a card that silently draws
         * blank.
         */
        private fun titleOf(context: Context, content: AlertContent): String = when (content) {
            is AlertContent.CareReminder -> context.getString(
                R.string.alert_title_care_reminder,
                content.petName,
                content.taskTitle,
                duePhrase(context, content.dueAt)
            )

            is AlertContent.MissedTask -> context.getString(
                R.string.alert_title_missed_task,
                content.taskTitle
            )

            is AlertContent.Activity -> context.resources.getQuantityString(
                R.plurals.alert_title_activity,
                content.expenseCount,
                content.expenseCount,
                content.petName
            )

            is AlertContent.Milestone -> context.getString(
                R.string.alert_title_milestone,
                content.petName,
                agePhrase(context, content.months)
            )

            is AlertContent.Tip -> when (content.kind) {
                // The only tip that reads better without a name: it is about the habit, not the pet.
                TipKind.NO_WEIGHT -> context.getString(R.string.alert_tip_no_weight)
                TipKind.NO_DATE_OF_BIRTH ->
                    context.getString(R.string.alert_tip_no_date_of_birth, content.petName)
                TipKind.NO_ROUTINE ->
                    context.getString(R.string.alert_tip_no_routine, content.petName)
                TipKind.NO_PHOTO ->
                    context.getString(R.string.alert_tip_no_photo, content.petName)
            }
        }

        /** The kind of alert, named in the muted line under the title. */
        @StringRes
        private fun typeLabelOf(content: AlertContent): Int = when (content) {
            is AlertContent.CareReminder -> R.string.alert_type_care_reminder
            is AlertContent.MissedTask -> R.string.alert_type_missed_task
            is AlertContent.Activity -> R.string.alert_type_activity
            is AlertContent.Milestone -> R.string.alert_type_milestone
            is AlertContent.Tip -> R.string.alert_type_tip
        }

        /**
         * The short code on the badge.
         *
         * A care reminder takes one of two, chosen by the task's category: the badge is the only place
         * the card says whether this is a jab or a check-up, which is the difference between "VAX" and
         * "VET" being worth drawing at all.
         */
        @StringRes
        private fun badgeOf(content: AlertContent): Int = when (content) {
            is AlertContent.CareReminder ->
                if (content.category == TaskCategory.MEDICATION) R.string.alert_badge_medication
                else R.string.alert_badge_vet

            is AlertContent.MissedTask -> R.string.alert_badge_missed
            is AlertContent.Activity -> R.string.alert_badge_activity
            is AlertContent.Milestone -> R.string.alert_badge_milestone
            is AlertContent.Tip -> R.string.alert_badge_tip
        }

        /**
         * "today", "tomorrow" or "on 22 Sep" — the end of a care reminder's sentence.
         *
         * Compared by *day* rather than by the hours between the two moments, because that is what the
         * words mean: an appointment at 9am tomorrow is "tomorrow" whether it is asked about at
         * midnight or at 8pm tonight, and a 20-hour difference is not what decides it.
         */
        private fun duePhrase(context: Context, dueAt: Long): String {
            val today = CareSchedule.startOfToday()
            val dueDay = CareSchedule.startOfDay(dueAt)

            return when (dueDay) {
                today -> context.getString(R.string.alert_due_today)
                // The next day's start is asked of the calendar rather than assumed to be 24 hours
                // later, so the phrase is still right on the night the clocks change.
                CareSchedule.startOfDay(today + TimeUnit.DAYS.toMillis(1) + HALF_DAY_MILLIS) ->
                    context.getString(R.string.alert_due_tomorrow)

                else -> context.getString(R.string.alert_due_on, formatShortDate(context, dueAt))
            }
        }

        /** "1 month" or "3 years" — see [NotificationsViewModel] for which ages get an alert. */
        private fun agePhrase(context: Context, months: Int): String =
            if (months >= MONTHS_PER_YEAR) {
                val years = months / MONTHS_PER_YEAR
                context.resources.getQuantityString(R.plurals.alert_milestone_years, years, years)
            } else {
                context.resources.getQuantityString(R.plurals.alert_milestone_months, months, months)
            }

        /**
         * How long ago an alert happened: "Just now", "45m ago", "2h ago" — or its date once it is no
         * longer today.
         *
         * **Why the switch to a date, and why at midnight rather than at some number of hours.** A
         * relative age is how a notification is read while it is still current; past that, "51h ago"
         * is arithmetic the user should not have to do. Midnight is the natural place to change over
         * because it is where the list's own grouping changes: every card under "TODAY" reads
         * relatively and every card under "EARLIER" carries a date, so the two never disagree.
         *
         * The date is deliberately year-less — "Sep 10" — because nothing here is older than a
         * fortnight, and a year on every card would be noise.
         */
        private fun formatAge(context: Context, occurredAt: Long): String {
            val now = System.currentTimeMillis()

            if (occurredAt < CareSchedule.startOfToday()) {
                return formatShortDate(context, occurredAt)
            }

            val elapsed = (now - occurredAt).coerceAtLeast(0L)
            val minutes = TimeUnit.MILLISECONDS.toMinutes(elapsed)
            val hours = TimeUnit.MILLISECONDS.toHours(elapsed)

            return when {
                minutes < 1 -> context.getString(R.string.alert_age_just_now)
                hours < 1 -> context.getString(R.string.alert_age_minutes, minutes.toInt())
                else -> context.getString(R.string.alert_age_hours, hours.toInt())
            }
        }

        /**
         * A date with no year, in the user's own convention — "Sep 10", or "10 Sept" where that is
         * how dates are written.
         *
         * [DateFormat.getBestDateTimePattern] is asked for the pattern rather than one being written
         * out, because the *order* of day and month differs by locale and a hard-coded "MMM d" would
         * be wrong in most of the world. The skeleton says which fields are wanted; the locale decides
         * how to arrange them.
         *
         * Formatted in the device's own time zone, unlike
         * [com.prash.petcare.ui.common.formatStoredDate]: these are wall-clock moments — a task's due
         * time, an expense's creation — rather than calendar dates stored at midnight UTC.
         */
        private fun formatShortDate(context: Context, millis: Long): String {
            val locale = Locale.getDefault()
            val pattern = DateFormat.getBestDateTimePattern(locale, DAY_AND_MONTH_SKELETON)
            return DateFormat.format(pattern, Date(millis)).toString()
        }

        /** Day and abbreviated month, in whichever order the locale writes them. */
        private const val DAY_AND_MONTH_SKELETON = "MMMd"

        /**
         * Twelve hours, added before asking for a day's start so that "a day later" lands safely in
         * the middle of tomorrow rather than on its boundary — which is what keeps the calculation
         * right on the 23-hour day when the clocks go forward.
         */
        private val HALF_DAY_MILLIS = TimeUnit.HOURS.toMillis(12)
    }
}
