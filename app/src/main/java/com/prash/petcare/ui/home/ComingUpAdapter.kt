package com.prash.petcare.ui.home

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.prash.petcare.R
import com.prash.petcare.data.local.entity.TaskCategory
import com.prash.petcare.databinding.ItemComingUpBinding
import com.prash.petcare.ui.common.formatTimeOfDay
import java.util.Date
import java.util.Locale

/**
 * Draws the rows of the dashboard's "Coming up this week" card (item_coming_up.xml).
 *
 * Part of the **View** layer: [HomeDashboard.comingUp] has already decided which tasks appear and in
 * what order; this only turns each [ComingUpItem] into text and colour and reports taps.
 *
 * A [ListAdapter] so the minute-by-minute rebuilds of the dashboard's state leave unchanged rows alone —
 * see [HomePetAdapter] for why that matters on this screen.
 *
 * @property onItemClick Called with the routine id of the tapped row, which the fragment opens.
 */
class ComingUpAdapter(
    private val onItemClick: (routineId: Long) -> Unit
) : ListAdapter<ComingUpItem, ComingUpAdapter.RowViewHolder>(ComingUpDiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowViewHolder {
        val binding = ItemComingUpBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return RowViewHolder(binding, onItemClick)
    }

    override fun onBindViewHolder(holder: RowViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    /** Holds one row's views; see [HomePetAdapter.PetCardViewHolder] for why ViewBinding here. */
    class RowViewHolder(
        private val binding: ItemComingUpBinding,
        private val onItemClick: (routineId: Long) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        /**
         * The two date formats for the left-hand column.
         *
         * `android.icu` rather than `java.text`: ICU is part of the platform from API 24 (the app's
         * minimum) and follows the locale's own abbreviations. Created per holder rather than per bind,
         * because formatters are comparatively expensive to build and a holder binds many times.
         */
        private val dayOfMonthFormat = android.icu.text.SimpleDateFormat("d", Locale.getDefault())
        private val weekdayFormat = android.icu.text.SimpleDateFormat("EEE", Locale.getDefault())

        fun bind(item: ComingUpItem) {
            val context = binding.root.context
            val task = item.task
            val day = Date(item.dayStart)

            // Every row shown has a routine — HomeDashboard.comingUp only lists tasks it could find one
            // for — but the id is nullable on the entity, so the tap is simply not attached without it.
            val routineId = task.routineId
            binding.root.setOnClickListener { routineId?.let(onItemClick) }

            binding.dayOfMonthText.text = dayOfMonthFormat.format(day)
            binding.weekdayText.text = weekdayFormat.format(day)

            binding.titleText.text = task.title

            // "Patan Animal Hospital · 11:00 AM" or "Grooming routine · 6:00 PM". The first line of the
            // task's notes is used as its location when there is one — a visit booked from the Places
            // tab starts its notes with where the place is — and the routine's name otherwise. A task
            // with no time of day reads "· due", as the design shows.
            val where = task.notes
                ?.lineSequence()
                ?.map { it.trim() }
                ?.firstOrNull { it.isNotEmpty() }
                ?: item.routineTitle
            val time = task.timeOfDayMinutes?.let { formatTimeOfDay(context, it) }
                ?: context.getString(R.string.home_coming_up_due)
            binding.subtitleText.text =
                listOf(where, time).joinToString(context.getString(R.string.home_meta_separator))

            // The bar's colour says what kind of care it is at a glance. Applied as a tint over bg_pill,
            // so the capsule shape is described once in the drawable.
            binding.categoryBar.backgroundTintList =
                ColorStateList.valueOf(ContextCompat.getColor(context, task.category.barColorRes()))

            // One sentence for TalkBack instead of five fragments: "Coat brush for Krishala, Friday.
            // Open routine." The full weekday is used here because an abbreviation read aloud is
            // noise.
            val spokenDay = android.icu.text.SimpleDateFormat("EEEE", Locale.getDefault()).format(day)
            binding.root.contentDescription =
                context.getString(R.string.cd_coming_up_row, task.title, item.petName, spokenDay)
        }
    }

    /** Same row if it is the same task on the same day; redraw only if anything about it changed. */
    private object ComingUpDiffCallback : DiffUtil.ItemCallback<ComingUpItem>() {
        override fun areItemsTheSame(oldItem: ComingUpItem, newItem: ComingUpItem): Boolean =
            oldItem.task.id == newItem.task.id && oldItem.dayStart == newItem.dayStart

        override fun areContentsTheSame(oldItem: ComingUpItem, newItem: ComingUpItem): Boolean =
            oldItem == newItem
    }
}

/**
 * The colour of the thin bar beside a "coming up" row, by care category — see the care category
 * colours in colors.xml.
 */
@ColorRes
private fun TaskCategory.barColorRes(): Int = when (this) {
    TaskCategory.GROOMING -> R.color.care_category_grooming
    TaskCategory.VET_VISIT -> R.color.care_category_vet
    TaskCategory.MEDICATION -> R.color.care_category_medication
    TaskCategory.WALK -> R.color.care_category_walk
    TaskCategory.FEEDING -> R.color.care_category_feeding
    TaskCategory.TRAINING -> R.color.care_category_training
    TaskCategory.PLAY -> R.color.care_category_play
    TaskCategory.OTHER -> R.color.care_category_other
}
