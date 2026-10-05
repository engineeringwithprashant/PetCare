package com.prash.petcare.ui.notifications

import com.prash.petcare.data.alert.Alert
import com.prash.petcare.data.alert.AlertContent
import com.prash.petcare.data.alert.TipKind
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

/** Draws the notifications list: the "TODAY" and "EARLIER" headings and every alert card under them. */
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

            // Unreachable: getItemViewType only returns the two constants above.
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

            // Re-set on every bind: rows are recycled.
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

            // One spoken sentence per card, in place of a code.
            binding.root.contentDescription = listOfNotNull(
                title,
                meta,
                context.getString(R.string.cd_alert_unread).takeIf { !alert.isRead }
            ).joinToString(context.getString(R.string.list_separator))
        }

        /** Colours the card for whether it has been read. */
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
                // The accent is a fixed value rather than a theme role: it is the same yellow in both modes.
                binding.alertBadgeText.backgroundTintList =
                    ContextCompat.getColorStateList(context, R.color.accent_yellow)
                binding.alertBadgeText.setTextColor(
                    ContextCompat.getColor(context, R.color.on_accent_yellow)
                )
            }

            binding.alertUnreadDot.isVisible = !isRead
        }
    }

    /** Rows are the same row when they have the same [AlertListItem.itemId]; their contents have changed. */
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

        /** The sentence on the card. */
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

        /** The short code on the badge. */
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

        /** "today", "tomorrow" or "on 22 Sep" — the end of a care reminder's sentence. */
        private fun duePhrase(context: Context, dueAt: Long): String {
            val today = CareSchedule.startOfToday()
            val dueDay = CareSchedule.startOfDay(dueAt)

            return when (dueDay) {
                today -> context.getString(R.string.alert_due_today)
                // The next day's start is asked of the calendar rather than assumed to be 24 hours later.
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

        /** How long ago an alert happened: "Just now", "45m ago", "2h ago". */
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

        /** A date with no year, in the user's own convention — "Sep 10". */
        private fun formatShortDate(context: Context, millis: Long): String {
            val locale = Locale.getDefault()
            val pattern = DateFormat.getBestDateTimePattern(locale, DAY_AND_MONTH_SKELETON)
            return DateFormat.format(pattern, Date(millis)).toString()
        }

        /** Day and abbreviated month, in whichever order the locale writes them. */
        private const val DAY_AND_MONTH_SKELETON = "MMMd"

        /** Twelve hours, added before asking for a day's start. */
        private val HALF_DAY_MILLIS = TimeUnit.HOURS.toMillis(12)
    }
}
