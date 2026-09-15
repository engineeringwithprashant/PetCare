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

/**
 * Draws a pet's care routine cards on the pet detail screen (res/layout/item_routine.xml).
 *
 * Part of the **View** layer: it receives ready-made [PetRoutine]s — each routine with its task
 * count already worked out by the ViewModel — and turns them into text. It makes no decisions about
 * navigation or data. Every tap is handed back to the fragment through one of the four lambdas, and
 * the fragment in turn forwards the data-changing ones to its ViewModel.
 *
 * Extends [ListAdapter], which diffs each new list against the previous one on a background thread
 * and animates only the cards that actually changed — far better than redrawing everything whenever
 * the database emits, which on this screen happens every time a task is ticked on another tab.
 *
 * @property onRoutineClick The card body was tapped: open the routine.
 * @property onEditClick "Edit" was chosen from the card's menu.
 * @property onToggleActiveClick "Pause" or "Resume" was chosen.
 * @property onDeleteClick "Delete" was chosen. The fragment confirms before anything is removed.
 */
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

    /**
     * Holds one routine card's views, resolved once by ViewBinding when the card is created.
     *
     * Takes the adapter itself rather than four separate lambdas, purely to keep the constructor
     * short; it only ever calls the adapter's callbacks.
     */
    class RoutineViewHolder(
        private val binding: ItemRoutineBinding,
        private val adapter: RoutineAdapter
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: PetRoutine) {
            val context = binding.root.context
            val routine = item.routine

            // Re-set on every bind: cards are recycled, so a listener left from a previous bind would
            // act on whichever routine used to occupy this card.
            binding.root.setOnClickListener { adapter.onRoutineClick(routine) }
            binding.overflowButton.setOnClickListener { showMenu(routine) }

            binding.routineTitleText.text = routine.title

            val scheduleLabel = context.getString(routine.repeatUnit.cardLabelRes())

            // A paused routine stays in the list — pausing is reversible, and hiding it would leave
            // no way to switch it back on — but it has to be obviously inactive, since its tasks do
            // not appear on the checklist.
            binding.routineScheduleText.text = if (routine.isActive) {
                scheduleLabel
            } else {
                context.getString(R.string.routine_paused_format, scheduleLabel)
            }

            // A plurals resource, so "1 task" and "3 tasks" are both right — and right in languages
            // with more than two plural forms.
            binding.taskCountText.text = context.resources.getQuantityString(
                R.plurals.routine_card_task_count,
                item.taskCount,
                item.taskCount
            )

            binding.overflowButton.contentDescription =
                context.getString(R.string.cd_routine_options, routine.title)
        }

        /**
         * Opens the edit / pause-or-resume / delete menu anchored to the "⋮" button.
         *
         * Built fresh on each tap from the routine captured at bind time, so the Pause/Resume wording
         * always matches the state the card is currently showing. A [PopupMenu] rather than a
         * dialog: it appears beside the button that opened it, which is where the user is looking,
         * and dismisses on a tap anywhere else.
         */
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

    /**
     * Tells [ListAdapter] how to compare two lists of routine cards.
     *
     * [areItemsTheSame] asks "is this the same routine?" by id, so a card that moves — a paused
     * routine drops below the active ones — is animated as a move rather than a delete plus an
     * insert. [areContentsTheSame] then decides whether that card needs redrawing at all.
     */
    private object RoutineDiffCallback : DiffUtil.ItemCallback<PetRoutine>() {

        override fun areItemsTheSame(oldItem: PetRoutine, newItem: PetRoutine): Boolean =
            oldItem.routine.id == newItem.routine.id

        // PetRoutine is a data class holding a data class, so `==` compares the routine's every
        // property and the task count together, and stays correct if a property is added later.
        override fun areContentsTheSame(oldItem: PetRoutine, newItem: PetRoutine): Boolean =
            oldItem == newItem
    }
}

/**
 * The short schedule wording a routine card uses: "Every day", "Weekly".
 *
 * Separate from `RepeatUnit.labelRes()` in CareLabels.kt, which says "Every week" for the routine's
 * own screen, because the card has to share its width with the task count and the menu button. The
 * `when` has no `else`, so a new repeat unit will not compile until the card has a word for it.
 */
@StringRes
private fun RepeatUnit.cardLabelRes(): Int = when (this) {
    RepeatUnit.DAY -> R.string.routine_card_schedule_daily
    RepeatUnit.WEEK -> R.string.routine_card_schedule_weekly
    RepeatUnit.MONTH -> R.string.routine_card_schedule_monthly
    RepeatUnit.YEAR -> R.string.routine_card_schedule_yearly
}
