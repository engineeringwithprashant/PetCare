package com.prash.petcare.ui.routine

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.prash.petcare.R
import com.prash.petcare.data.local.entity.CareRoutine
import com.prash.petcare.databinding.ItemRoutineBinding
import com.prash.petcare.ui.common.labelRes

/**
 * Lists a pet's care routines on the pet detail screen.
 *
 * Uses [ListAdapter], which diffs each new list against the previous one on a background thread and
 * animates only the rows that actually changed — far better than redrawing everything whenever the
 * database emits.
 *
 * @property onRoutineClick Called with the tapped routine. Kept as a lambda so navigation decisions
 *   stay in the fragment and this adapter remains reusable.
 */
class RoutineAdapter(
    private val onRoutineClick: (CareRoutine) -> Unit
) : ListAdapter<CareRoutine, RoutineAdapter.RoutineViewHolder>(RoutineDiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RoutineViewHolder {
        val binding = ItemRoutineBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return RoutineViewHolder(binding, onRoutineClick)
    }

    override fun onBindViewHolder(holder: RoutineViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    /** Holds one routine row's views, resolved once by ViewBinding when the row is created. */
    class RoutineViewHolder(
        private val binding: ItemRoutineBinding,
        private val onRoutineClick: (CareRoutine) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(routine: CareRoutine) {
            val context = binding.root.context

            // Re-set on every bind: rows are recycled, so a listener left from a previous bind
            // would open whichever routine used to occupy this row.
            binding.root.setOnClickListener { onRoutineClick(routine) }

            binding.routineTitleText.text = routine.title

            val scheduleLabel = context.getString(routine.repeatUnit.labelRes())

            // A paused routine still appears in the list — pausing is reversible, so hiding it
            // would leave the user no way to switch it back on — but it has to be obviously
            // inactive, since its tasks do not show on the checklist.
            binding.routineScheduleText.text = if (routine.isActive) {
                scheduleLabel
            } else {
                context.getString(R.string.routine_paused_format, scheduleLabel)
            }
        }
    }

    /**
     * Tells [ListAdapter] how to compare two lists of routines.
     *
     * [areItemsTheSame] asks "is this the same routine?" by id, so a row that moves is recognised
     * as a move rather than a delete plus an insert. [areContentsTheSame] then decides whether that
     * row needs redrawing at all.
     */
    private object RoutineDiffCallback : DiffUtil.ItemCallback<CareRoutine>() {

        override fun areItemsTheSame(oldItem: CareRoutine, newItem: CareRoutine): Boolean =
            oldItem.id == newItem.id

        // CareRoutine is a data class, so `==` already compares every property, and stays correct
        // automatically if one is added later.
        override fun areContentsTheSame(oldItem: CareRoutine, newItem: CareRoutine): Boolean =
            oldItem == newItem
    }
}
