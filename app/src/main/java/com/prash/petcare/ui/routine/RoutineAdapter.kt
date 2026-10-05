package com.prash.petcare.ui.routine

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.annotation.StringRes
import androidx.appcompat.widget.PopupMenu
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.prash.petcare.R
import com.prash.petcare.data.local.entity.CareRoutine
import com.prash.petcare.data.local.entity.RepeatUnit
import com.prash.petcare.databinding.ItemRoutineBinding
import com.prash.petcare.ui.pet.PetRoutine

/** Draws a pet's care routine cards on the pet detail screen (res/layout/item_routine.xml). */
class RoutineAdapter(
    private val onRoutineClick: (CareRoutine) -> Unit,
    private val onEditClick: (CareRoutine) -> Unit,
    private val onToggleActiveClick: (CareRoutine) -> Unit,
    private val onDeleteClick: (CareRoutine) -> Unit
) : ListAdapter<PetRoutine, RoutineAdapter.RoutineViewHolder>(RoutineDiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RoutineViewHolder {
        val binding = ItemRoutineBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            // false: the RecyclerView attaches the view itself once it has measured it.
            false
        )
        return RoutineViewHolder(binding, this)
    }

    override fun onBindViewHolder(holder: RoutineViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    /** Holds one routine card's views, resolved once by ViewBinding when the card is created. */
    class RoutineViewHolder(
        private val binding: ItemRoutineBinding,
        private val adapter: RoutineAdapter
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: PetRoutine) {
            val context = binding.root.context
            val routine = item.routine

            // Re-set on every bind: cards are recycled.
            binding.root.setOnClickListener { adapter.onRoutineClick(routine) }
            binding.overflowButton.setOnClickListener { showMenu(routine) }

            binding.routineTitleText.text = routine.title

            val scheduleLabel = context.getString(routine.repeatUnit.cardLabelRes())

            // A paused routine stays in the list — pausing is reversible.
            binding.routineScheduleText.text = if (routine.isActive) {
                scheduleLabel
            } else {
                context.getString(R.string.routine_paused_format, scheduleLabel)
            }

            // A plurals resource, so "1 task" and "3 tasks" are both right.
            binding.taskCountText.text = context.resources.getQuantityString(
                R.plurals.routine_card_task_count,
                item.taskCount,
                item.taskCount
            )

            binding.overflowButton.contentDescription =
                context.getString(R.string.cd_routine_options, routine.title)
        }

        /** Opens the edit / pause-or-resume / delete menu anchored to the "⋮" button. */
        private fun showMenu(routine: CareRoutine) {
            val popup = PopupMenu(binding.root.context, binding.overflowButton)
            popup.menuInflater.inflate(R.menu.routine_card_menu, popup.menu)

            popup.menu.findItem(R.id.action_routine_toggle_active)?.setTitle(
                if (routine.isActive) R.string.routine_menu_pause else R.string.routine_menu_resume
            )

            popup.setOnMenuItemClickListener { menuItem ->
                when (menuItem.itemId) {
                    R.id.action_routine_edit -> adapter.onEditClick(routine)
                    R.id.action_routine_toggle_active -> adapter.onToggleActiveClick(routine)
                    R.id.action_routine_delete -> adapter.onDeleteClick(routine)
                    else -> return@setOnMenuItemClickListener false
                }
                true
            }
            popup.show()
        }
    }

    /** Tells [ListAdapter] how to compare two lists of routine cards. */
    private object RoutineDiffCallback : DiffUtil.ItemCallback<PetRoutine>() {

        override fun areItemsTheSame(oldItem: PetRoutine, newItem: PetRoutine): Boolean =
            oldItem.routine.id == newItem.routine.id

        // PetRoutine is a data class holding a data class.
        override fun areContentsTheSame(oldItem: PetRoutine, newItem: PetRoutine): Boolean =
            oldItem == newItem
    }
}

/** The short schedule wording a routine card uses: "Every day", "Weekly". */
@StringRes
private fun RepeatUnit.cardLabelRes(): Int = when (this) {
    RepeatUnit.DAY -> R.string.routine_card_schedule_daily
    RepeatUnit.WEEK -> R.string.routine_card_schedule_weekly
    RepeatUnit.MONTH -> R.string.routine_card_schedule_monthly
    RepeatUnit.YEAR -> R.string.routine_card_schedule_yearly
}
