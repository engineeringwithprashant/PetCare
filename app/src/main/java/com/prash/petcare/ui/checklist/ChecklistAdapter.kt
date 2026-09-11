package com.prash.petcare.ui.checklist

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.prash.petcare.R
import com.prash.petcare.databinding.ItemChecklistHeaderBinding
import com.prash.petcare.databinding.ItemChecklistTaskBinding
import com.prash.petcare.ui.common.formatTimeOfDay
import com.prash.petcare.ui.common.labelRes

/**
 * Renders today's checklist: category headings interleaved with tickable tasks.
 *
 * Two different row layouts share one list, which RecyclerView supports through *view types*.
 * [getItemViewType] labels each position, [onCreateViewHolder] inflates the matching layout, and
 * the adapter recycles each type into its own pool, so a header is never reused as a task row.
 *
 * The alternative — two RecyclerViews, or one per category — would scroll independently and break
 * the illusion of a single list.
 *
 * @property onTaskChecked Called with the task's id and its new state when a checkbox is toggled.
 */
class ChecklistAdapter(
    private val onTaskChecked: (taskId: Long, isDone: Boolean) -> Unit
) : ListAdapter<ChecklistItem, RecyclerView.ViewHolder>(ChecklistDiffCallback) {

    /** Labels each position so RecyclerView knows which layout and holder pool to use. */
    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is ChecklistItem.CategoryHeader -> VIEW_TYPE_HEADER
        is ChecklistItem.TaskRow -> VIEW_TYPE_TASK
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)

        return when (viewType) {
            VIEW_TYPE_HEADER -> HeaderViewHolder(
                ItemChecklistHeaderBinding.inflate(inflater, parent, false)
            )

            VIEW_TYPE_TASK -> TaskViewHolder(
                ItemChecklistTaskBinding.inflate(inflater, parent, false),
                onTaskChecked
            )

            // Unreachable: getItemViewType only ever returns the two constants above. Throwing
            // rather than returning a default means a future third row type fails loudly here
            // instead of silently rendering as the wrong thing.
            else -> throw IllegalArgumentException("Unknown checklist view type: $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is ChecklistItem.CategoryHeader -> (holder as HeaderViewHolder).bind(item)
            is ChecklistItem.TaskRow -> (holder as TaskViewHolder).bind(item)
        }
    }

    /** A category heading. */
    class HeaderViewHolder(
        private val binding: ItemChecklistHeaderBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: ChecklistItem.CategoryHeader) {
            binding.categoryHeaderText.setText(item.category.labelRes())
        }
    }

    /** A tickable task. */
    class TaskViewHolder(
        private val binding: ItemChecklistTaskBinding,
        private val onTaskChecked: (taskId: Long, isDone: Boolean) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: ChecklistItem.TaskRow) {
            val context = binding.root.context
            val task = item.task

            binding.taskTitleText.text = task.title
            binding.taskTimeText.text = task.timeOfDayMinutes
                ?.let { formatTimeOfDay(context, it) }
                .orEmpty()

            // Supplies are the more useful of the two at a glance, so they win when both exist.
            val detail = task.requiredSupplies?.takeIf { it.isNotBlank() }
                ?.let { context.getString(R.string.task_supplies_format, it) }
                ?: task.notes?.takeIf { it.isNotBlank() }

            binding.taskDetailText.text = detail.orEmpty()
            // Set explicitly both ways, because this row may be recycled from a task that had
            // detail text when this one does not.
            binding.taskDetailText.visibility = if (detail != null) View.VISIBLE else View.GONE

            // The listener is detached before the state is applied and reattached afterwards.
            // Without this, setting `isChecked` during a rebind would fire the listener and write
            // the recycled row's state back to the database — silently ticking off the wrong task.
            binding.taskCheckBox.setOnCheckedChangeListener(null)
            binding.taskCheckBox.isChecked = item.isDoneToday
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
     * Tells [ListAdapter] how to compare two checklists.
     *
     * Identity comes from [ChecklistItem.itemId] rather than list position, so headers and tasks
     * are never confused with one another as the list is regrouped.
     */
    private object ChecklistDiffCallback : DiffUtil.ItemCallback<ChecklistItem>() {

        override fun areItemsTheSame(oldItem: ChecklistItem, newItem: ChecklistItem): Boolean =
            oldItem.itemId == newItem.itemId

        // Both kinds are data classes, so `==` compares every property — including a task's
        // isDoneToday, which is what makes a tick redraw its row.
        override fun areContentsTheSame(oldItem: ChecklistItem, newItem: ChecklistItem): Boolean =
            oldItem == newItem
    }

    companion object {
        private const val VIEW_TYPE_HEADER = 0
        private const val VIEW_TYPE_TASK = 1
    }
}
