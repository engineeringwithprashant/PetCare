package com.prash.petcare.ui.home

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.prash.petcare.R
import com.prash.petcare.databinding.ItemDashboardTaskBinding
import com.prash.petcare.ui.common.formatTimeOfDay
import com.prash.petcare.ui.common.iconRes
import com.prash.petcare.ui.common.labelRes

/**
 * Draws the dashboard's "Today's care" checklist — every pet's care for today in one list, each row
 * tickable.
 *
 * Unlike the Tasks tab, this list is **not** grouped under category headings. The dashboard mixes
 * several animals together and is at most a dozen rows long, so ordering it by time of day answers
 * the question actually being asked here — *what is next?* — where grouping by category would
 * scatter the morning across four headings. The category is still shown, as the symbol on each row.
 *
 * @property onTaskChecked Called with the task's id and its new state when a row is ticked. A
 *   lambda rather than the adapter writing to the database itself, which keeps this adapter free of
 *   any dependency on a repository or a ViewModel.
 */
class TodayTaskAdapter(
    private val onTaskChecked: (taskId: Long, isDone: Boolean) -> Unit
) : ListAdapter<TodayTask, TodayTaskAdapter.TodayTaskViewHolder>(TodayTaskDiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TodayTaskViewHolder {
        val binding = ItemDashboardTaskBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return TodayTaskViewHolder(binding, onTaskChecked)
    }

    override fun onBindViewHolder(holder: TodayTaskViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    /** Holds the views for one row. */
    class TodayTaskViewHolder(
        private val binding: ItemDashboardTaskBinding,
        private val onTaskChecked: (taskId: Long, isDone: Boolean) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(row: TodayTask) {
            val context = binding.root.context
            val task = row.task

            binding.taskTitleText.text = task.title

            // The category enum stores database values (VET_VISIT), so it is translated to both a
            // symbol and — for TalkBack, which cannot read a picture — a name.
            binding.categoryIcon.setImageResource(task.category.iconRes())
            binding.categoryIcon.contentDescription = context.getString(
                R.string.cd_task_category,
                context.getString(task.category.labelRes())
            )

            // Which pet, and when. A task with no time recorded shows the pet alone rather than a
            // trailing separator with nothing after it.
            val time = task.timeOfDayMinutes?.let { formatTimeOfDay(context, it) }
            binding.taskSubtitleText.text = if (time != null) {
                context.getString(R.string.home_task_subtitle, row.petName, time)
            } else {
                context.getString(R.string.home_task_subtitle_no_time, row.petName)
            }

            // The listener is detached before the state is applied and reattached afterwards.
            // Without this, setting `isChecked` during a rebind would fire the listener and write
            // the recycled row's state back to the database — silently ticking off the wrong task.
            binding.taskCheckBox.setOnCheckedChangeListener(null)
            binding.taskCheckBox.isChecked = row.isDoneToday
            binding.taskCheckBox.setOnCheckedChangeListener { _, isChecked ->
                onTaskChecked(task.id, isChecked)
            }

            // Tapping anywhere on the row toggles it, which is a far easier target than the box.
            binding.root.setOnClickListener {
                binding.taskCheckBox.isChecked = !binding.taskCheckBox.isChecked
            }
        }
    }

    /**
     * Tells [ListAdapter] how to compare two versions of today's checklist.
     *
     * Identity is the task's database id, so a row that moves as the day is reordered is recognised
     * as a move rather than a delete plus an insert.
     */
    private object TodayTaskDiffCallback : DiffUtil.ItemCallback<TodayTask>() {

        override fun areItemsTheSame(oldItem: TodayTask, newItem: TodayTask): Boolean =
            oldItem.task.id == newItem.task.id

        // A data class, so `==` compares every property — including isDoneToday, which is what
        // makes a tick redraw its row.
        override fun areContentsTheSame(oldItem: TodayTask, newItem: TodayTask): Boolean =
            oldItem == newItem
    }
}
