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

/** Renders the Tasks list: time-of-day section headers interleaved with task cards. */
class ChecklistAdapter(
    private val onTaskChecked: (taskId: Long, isDone: Boolean) -> Unit
) : ListAdapter<ChecklistItem, RecyclerView.ViewHolder>(ChecklistDiffCallback) {

    /** Whether ticking is allowed — true only while today is the day shown. */
    var canCheckOff: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            // Every visible card needs its button state refreshed.
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

            // Unreachable: getItemViewType returns only the constants above.
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

    /** One task card. */
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

            // The category as a symbol on the title line and as a word on the subtitle.
            binding.taskCategoryEmoji.setText(task.category.emojiRes())
            binding.taskTitleText.text = task.title
            binding.taskSubtitleText.text = context.getString(
                R.string.tasks_card_subtitle,
                item.petName,
                context.getString(task.category.labelRes())
            )

            // The supplies tag, only when something was recorded.
            val supplies = task.requiredSupplies?.takeIf { it.isNotBlank() }
            binding.taskSuppliesText.text = supplies.orEmpty()
            binding.taskSuppliesText.isVisible = supplies != null

            bindDoneState(item.isDone)

            // The check button. Its listener is removed before the state is applied and re-added.
            val check = binding.taskCheckButton
            check.clearOnCheckedChangeListeners()
            check.isChecked = item.isDone
            check.isEnabled = canCheckOff
            // Names the task, so TalkBack says which card a toggle belongs.
            check.contentDescription = context.getString(R.string.cd_task_done_named, task.title)
            check.addOnCheckedChangeListener { _, isChecked -> onTaskChecked(task.id, isChecked) }

            // Tapping anywhere on the card toggles it too — a far easier target than a 40dp circle.
            binding.root.setOnClickListener { if (check.isEnabled) check.toggle() }
            binding.root.isClickable = canCheckOff
        }

        /** Applies "done" to the whole card. */
        private fun bindDoneState(isDone: Boolean) {
            binding.taskContent.isActivated = isDone

            // Faded rather than recoloured.
            binding.taskCategoryEmoji.alpha = if (isDone) DONE_EMOJI_ALPHA else 1f

            val title = binding.taskTitleText
            title.paintFlags = if (isDone) {
                title.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            } else {
                title.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
            }

            // A faint accent edge on a finished task, the neutral hairline otherwise — resolved from the theme.
            binding.root.strokeColor = MaterialColors.getColor(
                binding.root,
                if (isDone) {
                    com.google.android.material.R.attr.colorPrimaryContainer
                } else {
                    com.google.android.material.R.attr.colorOutlineVariant
                }
            )
        }

        /** Fills the time badge: "8:00" over "AM" on a 12-hour clock, "08:00" alone on a 24-hour one. */
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

    /** Tells [ListAdapter] how to compare two lists. */
    private object ChecklistDiffCallback : DiffUtil.ItemCallback<ChecklistItem>() {

        override fun areItemsTheSame(oldItem: ChecklistItem, newItem: ChecklistItem): Boolean =
            oldItem.itemId == newItem.itemId

        override fun areContentsTheSame(oldItem: ChecklistItem, newItem: ChecklistItem): Boolean =
            oldItem == newItem
    }

    companion object {
        private const val VIEW_TYPE_HEADER = 0
        private const val VIEW_TYPE_TASK = 1

        /** How far the category emoji is faded on a completed task. */
        private const val DONE_EMOJI_ALPHA = 0.45f
    }
}
