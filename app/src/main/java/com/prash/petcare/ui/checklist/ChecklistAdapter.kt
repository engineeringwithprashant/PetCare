package com.prash.petcare.ui.checklist

import android.graphics.Paint
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.prash.petcare.R
import com.prash.petcare.databinding.ItemChecklistTaskBinding
import com.prash.petcare.databinding.ItemTaskSectionHeaderBinding
import com.prash.petcare.ui.common.formatTimeOfDay
import com.prash.petcare.ui.common.emojiRes
import com.prash.petcare.ui.common.labelRes
import java.text.DateFormatSymbols
import java.util.Calendar

/**
 * Renders the Tasks list: time-of-day section headers interleaved with task cards.
 *
 * Part of the **View** layer. It receives ready-grouped [ChecklistItem]s from [ChecklistViewModel]
 * through the fragment, draws them, and reports taps on a card's check button back through
 * [onTaskChecked]; it decides nothing about what a tick means.
 *
 * Two row layouts share one list through RecyclerView *view types*: [getItemViewType] labels each
 * position, [onCreateViewHolder] inflates the matching layout, and each type is recycled into its own
 * pool, so a header is never reused as a card.
 *
 * Extends [ListAdapter], which diffs each new list against the last on a background thread and
 * animates only the rows that changed — ticking one task redraws that card and its section header,
 * not the whole day.
 *
 * @property onTaskChecked Called with a task's id and its new state when its circle is toggled.
 */
class ChecklistAdapter(
    private val onTaskChecked: (taskId: Long, isDone: Boolean) -> Unit
) : ListAdapter<ChecklistItem, RecyclerView.ViewHolder>(ChecklistDiffCallback) {

    /**
     * Whether ticking is allowed — true only while today is the day shown.
     *
     * Held on the adapter rather than inside each item because it is one fact about the whole list.
     * Changing it rebinds every row, so each card's check button enables or disables together; see
     * [ChecklistViewModel.setTaskDone] for why other days are read-only.
     */
    var canCheckOff: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            // Every visible card needs its button state refreshed. A payload-free full rebind is
            // acceptable here because it only happens when the user taps a different day.
            @Suppress("NotifyDataSetChanged")
            notifyDataSetChanged()
        }

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is ChecklistItem.SectionHeader -> VIEW_TYPE_HEADER
        is ChecklistItem.TaskRow -> VIEW_TYPE_TASK
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)

        return when (viewType) {
            VIEW_TYPE_HEADER -> HeaderViewHolder(
                ItemTaskSectionHeaderBinding.inflate(inflater, parent, false)
            )

            VIEW_TYPE_TASK -> TaskViewHolder(
                ItemChecklistTaskBinding.inflate(inflater, parent, false)
            )

            // Unreachable: getItemViewType returns only the constants above. Throwing means a future
            // row type fails loudly here instead of rendering silently as the wrong thing.
            else -> throw IllegalArgumentException("Unknown checklist view type: $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is ChecklistItem.SectionHeader -> (holder as HeaderViewHolder).bind(item)
            is ChecklistItem.TaskRow -> (holder as TaskViewHolder).bind(item, canCheckOff, onTaskChecked)
        }
    }

    /** "MORNING ———— 2/3". */
    class HeaderViewHolder(
        private val binding: ItemTaskSectionHeaderBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: ChecklistItem.SectionHeader) {
            val context = binding.root.context
            binding.sectionLabelText.setText(item.timeOfDay.labelRes)
            binding.sectionCountText.text =
                context.getString(R.string.tasks_section_count, item.doneCount, item.totalCount)
        }
    }

    /**
     * One task card.
     *
     * ViewBinding resolves every view once, when the card is created, so rebinding while scrolling
     * only reassigns text and state.
     */
    class TaskViewHolder(
        private val binding: ItemChecklistTaskBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(
            item: ChecklistItem.TaskRow,
            canCheckOff: Boolean,
            onTaskChecked: (taskId: Long, isDone: Boolean) -> Unit
        ) {
            val context = binding.root.context
            val task = item.task

            bindTime(task.timeOfDayMinutes)

            // The category as a symbol on the title line and as a word on the subtitle. Both, rather
            // than one or the other: the emoji is what makes a day's list scannable, and the word is
            // what makes it unambiguous for anyone who does not read the symbol the same way.
            binding.taskCategoryEmoji.setText(task.category.emojiRes())
            binding.taskTitleText.text = task.title
            binding.taskSubtitleText.text = context.getString(
                R.string.tasks_card_subtitle,
                item.petName,
                context.getString(task.category.labelRes())
            )

            // The supplies tag, only when something was recorded. Set both ways, because this card
            // may be recycled from a task that had supplies.
            val supplies = task.requiredSupplies?.takeIf { it.isNotBlank() }
            binding.taskSuppliesText.text = supplies.orEmpty()
            binding.taskSuppliesText.isVisible = supplies != null

            bindDoneState(item.isDone)

            // The check button. Its listener is removed before the state is applied and re-added
            // after: setting isChecked during a rebind would otherwise fire the listener and write the
            // *previous* occupant's state to the database — ticking off the wrong task.
            val check = binding.taskCheckButton
            check.clearOnCheckedChangeListeners()
            check.isChecked = item.isDone
            check.isEnabled = canCheckOff
            // Names the task, so TalkBack says which card a toggle belongs to rather than a row of
            // identical "Mark task as done" buttons.
            check.contentDescription = context.getString(R.string.cd_task_done_named, task.title)
            check.addOnCheckedChangeListener { _, isChecked -> onTaskChecked(task.id, isChecked) }

            // Tapping anywhere on the card toggles it too — a far easier target than a 40dp circle.
            // Routed through the button so both paths run the same listener. isClickable is set
            // *after* the listener, because setOnClickListener switches clickability back on.
            binding.root.setOnClickListener { if (check.isEnabled) check.toggle() }
            binding.root.isClickable = canCheckOff
        }

        /**
         * Applies "done" to the whole card.
         *
         * `isActivated` on the content row does most of it: the badge fill, the badge text and the
         * title colour all switch through their state lists (see item_checklist_task.xml). What XML
         * cannot do is strike the title through, which is a paint flag, tint the card's outline,
         * which MaterialCardView takes as a plain colour, and fade the category emoji, which is a
         * colour glyph and so ignores the muted text colour the rest of the row takes.
         */
        private fun bindDoneState(isDone: Boolean) {
            binding.taskContent.isActivated = isDone

            // Faded rather than recoloured: an emoji is drawn from the system's colour emoji font and
            // pays no attention to textColor, so a finished task's symbol would otherwise stay at
            // full strength beside its greyed, struck-through title.
            binding.taskCategoryEmoji.alpha = if (isDone) DONE_EMOJI_ALPHA else 1f

            val title = binding.taskTitleText
            title.paintFlags = if (isDone) {
                title.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            } else {
                title.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
            }

            // A faint accent edge on a finished task, the neutral hairline otherwise — resolved from
            // the theme so both follow light and dark mode.
            binding.root.strokeColor = MaterialColors.getColor(
                binding.root,
                if (isDone) {
                    com.google.android.material.R.attr.colorPrimaryContainer
                } else {
                    com.google.android.material.R.attr.colorOutlineVariant
                }
            )
        }

        /**
         * Fills the time badge: "8:00" over "AM" on a 12-hour clock, "08:00" alone on a 24-hour one.
         *
         * The design splits the time from its AM/PM marker to fit a narrow square, which the
         * device's ready-made time format (used everywhere else via [formatTimeOfDay]) cannot do. So
         * the two halves are built separately — but still from the device's own 12/24-hour setting
         * and the locale's own AM/PM words, so nothing is hard-coded to English.
         */
        private fun bindTime(minutesSinceMidnight: Int?) {
            val context = binding.root.context

            if (minutesSinceMidnight == null) {
                binding.taskTimeText.text = ""
                binding.taskMeridiemText.isVisible = false
                return
            }

            val calendar = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, minutesSinceMidnight / 60)
                set(Calendar.MINUTE, minutesSinceMidnight % 60)
            }

            if (DateFormat.is24HourFormat(context)) {
                binding.taskTimeText.text = formatTimeOfDay(context, minutesSinceMidnight)
                binding.taskMeridiemText.isVisible = false
            } else {
                binding.taskTimeText.text = DateFormat.format("h:mm", calendar)
                binding.taskMeridiemText.text =
                    DateFormatSymbols.getInstance().amPmStrings[calendar.get(Calendar.AM_PM)]
                binding.taskMeridiemText.isVisible = true
            }
        }
    }

    /**
     * Tells [ListAdapter] how to compare two lists.
     *
     * Identity comes from [ChecklistItem.itemId], so headers and tasks are never confused as sections
     * gain and lose rows; contents are compared with `==`, which for these data classes includes a
     * task's done state and a header's counts — exactly what should trigger a redraw.
     */
    private object ChecklistDiffCallback : DiffUtil.ItemCallback<ChecklistItem>() {

        override fun areItemsTheSame(oldItem: ChecklistItem, newItem: ChecklistItem): Boolean =
            oldItem.itemId == newItem.itemId

        override fun areContentsTheSame(oldItem: ChecklistItem, newItem: ChecklistItem): Boolean =
            oldItem == newItem
    }

    companion object {
        private const val VIEW_TYPE_HEADER = 0
        private const val VIEW_TYPE_TASK = 1

        /**
         * How far the category emoji is faded on a completed task.
         *
         * Enough to read as "settled" beside the greyed title, not so far that the row's category
         * becomes unidentifiable — a finished task is still one the user may want to find again.
         */
        private const val DONE_EMOJI_ALPHA = 0.45f
    }
}
