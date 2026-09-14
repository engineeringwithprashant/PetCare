package com.prash.petcare.ui.home

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.prash.petcare.R
import com.prash.petcare.databinding.ItemPetHeroBinding
import com.prash.petcare.databinding.ItemPetRowBinding
import com.prash.petcare.ui.common.formatAgeShort
import com.prash.petcare.ui.common.labelRes
import com.prash.petcare.ui.common.loadPetPhoto
import kotlinx.coroutines.CoroutineScope

/**
 * Draws the dashboard's pet cards.
 *
 * **Two sizes from one list.** The first pet is drawn as a large photo card and every pet after it
 * as a shorter row — the same information at two scales rather than two different components. The
 * reason is what a dashboard is for: it is glanced at, and a glance lands on one thing. Giving the
 * first card the photograph and the height makes the page have a subject, where a column of
 * identical cards would leave the eye with nowhere to start. The rest stay compact so that a user
 * with four animals can still see all of them without scrolling.
 *
 * RecyclerView expresses this through **view types**: [getItemViewType] labels each position, and
 * [onCreateViewHolder] is handed that label and inflates accordingly. It matters that this is one
 * adapter rather than two lists stacked in the layout — the first pet becomes the second the moment
 * another is added, and with one adapter that is an ordinary list update that DiffUtil animates,
 * where two lists would mean moving an item between them by hand.
 *
 * Extends [ListAdapter] rather than the plain `RecyclerView.Adapter`. ListAdapter takes a whole new
 * list via `submitList` and works out the difference against the previous one on a background
 * thread, then animates only the rows that actually changed. Calling `notifyDataSetChanged` by hand
 * instead would redraw every visible card on each database update and lose scroll position and
 * animations — which on this screen would mean every photo being decoded again.
 *
 * @property photoScope The coroutine scope photo loads run in. Passed in rather than created here,
 *   because an adapter has no lifecycle of its own: the fragment hands it
 *   `viewLifecycleOwner.lifecycleScope`, so leaving the screen cancels every decode still in flight
 *   instead of letting them finish and write into views that no longer exist.
 * @property onPetClick Called with the tapped pet's id. A lambda rather than having the adapter
 *   navigate by itself, which keeps navigation decisions in the fragment and leaves this adapter
 *   reusable on any screen that lists pets this way.
 */
class PetSummaryAdapter(
    private val photoScope: CoroutineScope,
    private val onPetClick: (petId: Long) -> Unit
) : ListAdapter<PetSummary, RecyclerView.ViewHolder>(PetSummaryDiffCallback) {

    /**
     * Which of the two card layouts this position takes.
     *
     * Decided by position alone rather than by anything about the pet: "the first one is the big
     * one" is a fact about the list, not about the animal. That is what makes the treatment move
     * automatically when the list is reordered — pets are listed newest first, so the most recently
     * added animal is the one that gets the large card.
     */
    override fun getItemViewType(position: Int): Int =
        if (position == 0) VIEW_TYPE_HERO else VIEW_TYPE_ROW

    /**
     * Creates a card of whichever kind [getItemViewType] asked for.
     *
     * Called only when there is no recycled view of that type to reuse, which is why inflation lives
     * here and the actual data is set in [onBindViewHolder]. RecyclerView keeps a separate pool per
     * view type, so a hero card is never handed back for a row position.
     */
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)

        return if (viewType == VIEW_TYPE_HERO) {
            // false: the RecyclerView attaches the view itself once it has measured it.
            HeroViewHolder(ItemPetHeroBinding.inflate(inflater, parent, false), photoScope, onPetClick)
        } else {
            RowViewHolder(ItemPetRowBinding.inflate(inflater, parent, false), photoScope, onPetClick)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val summary = getItem(position)

        // The two holders share no base class, so the type is recovered here. `is` rather than
        // casting on the view type again: the compiler then knows which holder it has and the wrong
        // one cannot silently be passed the wrong data.
        when (holder) {
            is HeroViewHolder -> holder.bind(summary)
            is RowViewHolder -> holder.bind(summary)
        }
    }

    /**
     * Holds the views for a large photo card.
     *
     * The point of a ViewHolder is that the lookups happen once, when the card is created. Because
     * ViewBinding resolves every id up front, scrolling only reassigns text and never searches the
     * view hierarchy again.
     */
    class HeroViewHolder(
        private val binding: ItemPetHeroBinding,
        private val photoScope: CoroutineScope,
        private val onPetClick: (petId: Long) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(summary: PetSummary) {
            val context = binding.root.context
            val pet = summary.pet

            // Re-set on every bind, not just at creation: cards are recycled, so a listener left
            // over from a previous bind would open whichever pet used to occupy this card.
            binding.root.setOnClickListener { onPetClick(pet.id) }

            binding.petNameText.text = pet.name

            // The species enum stores database values (DOG, CAT), so it is translated to a readable
            // label before display. The chip's style upper-cases it at draw time.
            binding.speciesChip.text = context.getString(pet.species.labelRes())

            bindTasksPill(binding.tasksPill, summary)
            bindFactsPill(binding.factsPill, summary)

            // Asynchronous, and careful about recycling — see ui/common/PetPhotoBinder.kt.
            binding.petPhotoImage.loadPetPhoto(pet.photoPath, photoScope)
        }
    }

    /** Holds the views for a compact row card. The counterpart to [HeroViewHolder]. */
    class RowViewHolder(
        private val binding: ItemPetRowBinding,
        private val photoScope: CoroutineScope,
        private val onPetClick: (petId: Long) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(summary: PetSummary) {
            val context = binding.root.context
            val pet = summary.pet

            binding.root.setOnClickListener { onPetClick(pet.id) }

            binding.petNameText.text = pet.name

            val speciesLabel = context.getString(pet.species.labelRes())

            // Breed is optional, and an empty string in the database should be treated the same as a
            // missing one — otherwise the subtitle would read "Dog · " with nothing after it.
            val breed = pet.breed?.takeIf { it.isNotBlank() }

            // The row card has room for the breed where the hero card, whose subtitle position is
            // occupied by the pet's own name across the photo, does not.
            binding.petSubtitleText.text = if (breed != null) {
                context.getString(R.string.pet_species_and_breed, speciesLabel, breed)
            } else {
                speciesLabel
            }

            bindTasksPill(binding.tasksPill, summary)
            bindFactsPill(binding.factsPill, summary)

            binding.petPhotoImage.loadPetPhoto(pet.photoPath, photoScope)
        }
    }

    /**
     * Tells [ListAdapter] how to compare two lists of pet cards.
     *
     * The two checks answer different questions, and both are needed:
     *  - [areItemsTheSame] asks "is this the same pet?", compared by id. This is how a card that
     *    moved is recognised as a move rather than a delete plus an insert — which matters here
     *    because a delete-plus-insert would re-decode the photo.
     *  - [areContentsTheSame] asks "does it still look the same?", deciding whether that card needs
     *    redrawing at all.
     */
    private object PetSummaryDiffCallback : DiffUtil.ItemCallback<PetSummary>() {

        override fun areItemsTheSame(oldItem: PetSummary, newItem: PetSummary): Boolean =
            oldItem.pet.id == newItem.pet.id

        // PetSummary is a data class holding a data class, so `==` already compares the pet and
        // both counts — exactly what is needed here, and it stays correct automatically if a
        // property is added later.
        override fun areContentsTheSame(oldItem: PetSummary, newItem: PetSummary): Boolean =
            oldItem == newItem
    }

    private companion object {

        /** The first pet: a full-width photo card. See [getItemViewType]. */
        const val VIEW_TYPE_HERO = 0

        /** Every pet after the first: a compact row. */
        const val VIEW_TYPE_ROW = 1
    }
}

/**
 * Fills the accent pill reporting one pet's day.
 *
 * A free function taking the [TextView] rather than a method on a holder, because the two card
 * layouts are different binding classes with no common type — and the wording is the same on both,
 * which is the point: the first pet becomes the second when another is added, and its status must
 * not be phrased differently as a result.
 *
 * Three distinct messages rather than one count, because "2 left", "all done" and "nothing was
 * scheduled" are different things to tell someone and the last two would both render as "0 left
 * today" — which reads as a bug on a day with no care planned.
 */
private fun bindTasksPill(pill: TextView, summary: PetSummary) {
    val context = pill.context

    pill.text = when {
        summary.tasksOutstanding > 0 -> context.resources.getQuantityString(
            R.plurals.home_pet_tasks_left,
            summary.tasksOutstanding,
            summary.tasksOutstanding
        )

        summary.tasksDueToday > 0 -> context.getString(R.string.home_pet_all_done)

        else -> context.getString(R.string.home_pet_nothing_due)
    }
}

/**
 * Fills the dark pill carrying the pet's age and weight — "2 yrs · 26 kg" — or hides it when
 * neither has been recorded.
 *
 * **Why one pill rather than two.** Elsewhere in the app age and weight are separate pastel chips.
 * Here they share a capsule because they sit beside the accent status pill on a card that is only so
 * wide, and three pills in a row would wrap. Joining them also states what they are: both are
 * standing facts about the animal, as against the status pill, which is about today.
 *
 * Either half may be missing — a rescue animal's age is often genuinely unknown, and weight is
 * optional — so the two are collected and then joined, rather than being formatted into a fixed
 * pattern that would leave a stray separator behind when one is absent.
 *
 * Hidden entirely rather than filled with a placeholder: "Age unknown" would take up the same space
 * as a real fact while telling the user nothing they did not already know. Set explicitly both ways,
 * because this card may be recycled from a pet that recorded facts this one does not.
 */
private fun bindFactsPill(pill: TextView, summary: PetSummary) {
    val context = pill.context
    val pet = summary.pet

    val age = pet.dateOfBirth?.let { formatAgeShort(context, it) }
    val weight = pet.weightKg?.let {
        context.getString(R.string.pet_detail_weight_format, formatWeight(it))
    }

    val facts = listOfNotNull(age, weight)

    pill.text = when (facts.size) {
        0 -> ""
        1 -> facts.first()
        else -> context.getString(R.string.home_pet_facts, facts[0], facts[1])
    }

    pill.visibility = if (facts.isEmpty()) View.GONE else View.VISIBLE
}

/**
 * Renders a stored weight without a pointless decimal place: 26.0 becomes "26", 4.5 stays "4.5".
 *
 * Weight is stored as a `Double` because half-kilograms matter for a small animal, but most are
 * recorded as whole numbers and `toString()` would put "26.0 kg" on every card. The trailing ".0"
 * costs two characters in a capsule that is already sharing a line with the pet's status.
 *
 * The comparison is against the value truncated to a whole number rather than against a rounded one,
 * so only a genuinely whole weight loses its decimal.
 */
private fun formatWeight(kilograms: Double): String =
    if (kilograms == kilograms.toLong().toDouble()) {
        kilograms.toLong().toString()
    } else {
        kilograms.toString()
    }
