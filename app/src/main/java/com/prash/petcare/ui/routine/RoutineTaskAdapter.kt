package com.prash.petcare.ui.routine

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.prash.petcare.R
import com.prash.petcare.data.local.entity.CareTask
import com.prash.petcare.databinding.ItemRoutineTaskBinding
import com.prash.petcare.ui.common.formatTimeOfDay
import com.prash.petcare.ui.common.iconRes
import com.prash.petcare.ui.common.labelRes

/** Lists the care tasks that make up one routine. */
class RoutineTaskAdapter(
    private val onTaskClick: (CareTask) -> Unit
) : ListAdapter<CareTask, RoutineTaskAdapter.TaskViewHolder>(TaskDiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TaskViewHolder {
        val binding = ItemRoutineTaskBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return TaskViewHolder(binding, onTaskClick)
    }

    override fun onBindViewHolder(holder: TaskViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class TaskViewHolder(
        private val binding: ItemRoutineTaskBinding,
        private val onTaskClick: (CareTask) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(task: CareTask) {
            val context = binding.root.context

            // Re-set on every bind: rows are recycled.
            binding.root.setOnClickListener { onTaskClick(task) }

            binding.taskTitleText.text = task.title
            binding.taskCategoryText.text = context.getString(task.category.labelRes())
            binding.taskCategoryIcon.setImageResource(task.category.iconRes())

            // Every task created through the app has a time, but the column is nullable.
            binding.taskTimeText.text = task.timeOfDayMinutes
                ?.let { formatTimeOfDay(context, it) }
                .orEmpty()

            // Blank is treated the same as missing.
            val supplies = task.requiredSupplies?.takeIf { it.isNotBlank() }

            if (supplies != null) {
                binding.taskSuppliesText.text =
                    context.getString(R.string.task_supplies_format, supplies)
                binding.taskSuppliesText.visibility = View.VISIBLE
            } else {
                // Explicitly hidden rather than left as-is.
                binding.taskSuppliesText.visibility = View.GONE
            }
        }
    }

    private object TaskDiffCallback : DiffUtil.ItemCallback<CareTask>() {

        override fun areItemsTheSame(oldItem: CareTask, newItem: CareTask): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: CareTask, newItem: CareTask): Boolean =
            oldItem == newItem
    }
}
