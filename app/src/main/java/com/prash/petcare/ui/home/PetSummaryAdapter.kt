package com.prash.petcare.ui.home

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.prash.petcare.R
import com.prash.petcare.databinding.ItemDashboardPetBinding
import com.prash.petcare.ui.common.formatAge
import com.prash.petcare.ui.common.labelRes
import com.prash.petcare.ui.common.loadPetPhoto
import kotlinx.coroutines.CoroutineScope

/**
 * Draws the dashboard's pet cards: a round photo, the name and breed, and pastel pills reporting
 * how each animal's day is going.
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
) : ListAdapter<PetSummary, PetSummaryAdapter.PetSummaryViewHolder>(PetSummaryDiffCallback) {

    /**
     * Creates a card. Called only when there is no recycled view to reuse, which is why inflation
     * lives here and the actual data is set in [onBindViewHolder].
     */
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PetSummaryViewHolder {
        val binding = ItemDashboardPetBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            // false: the RecyclerView attaches the view itself once it has measured it.
            false
        )
        return PetSummaryViewHolder(binding, photoScope, onPetClick)
    }

    override fun onBindViewHolder(holder: PetSummaryViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    /**
     * Holds the views for one card.
     *
     * The point of a ViewHolder is that the lookups happen once, when the card is created. Because
     * ViewBinding resolves every id up front, scrolling only reassigns text and never searches the
     * view hierarchy again.
     */
    class PetSummaryViewHolder(
        private val binding: ItemDashboardPetBinding,
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

            // The species enum stores database values (DOG, CAT), so it is translated to a
            // readable label before display.
            val speciesLabel = context.getString(pet.species.labelRes())

            // Breed is optional, and an empty string in the database should be treated the same as
            // a missing one — otherwise the subtitle would read "Dog · " with nothing after it.
            val breed = pet.breed?.takeIf { it.isNotBlank() }

            binding.petSubtitleText.text = if (breed != null) {
                context.getString(R.string.pet_species_and_breed, speciesLabel, breed)
            } else {
                speciesLabel
            }

            bindTasksPill(summary)
            bindAgePill(summary)

            // Asynchronous, and careful about recycling — see ui/common/PetPhotoBinder.kt.
            binding.petPhotoImage.loadPetPhoto(pet.photoPath, photoScope)
        }

        /**
         * Fills the pill reporting this pet's day.
         *
         * Three distinct messages rather than one count, because "2 left", "all done" and "nothing
         * was scheduled" are different things to tell someone and the last two would both render as
         * "0 left today" — which reads as a bug on a day with no care planned.
         */
        private fun bindTasksPill(summary: PetSummary) {
            val context = binding.root.context

            binding.tasksPill.text = when {
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
         * Fills the age pill, or hides it when the pet has no recorded date of birth.
         *
         * Hidden rather than filled with a placeholder: a rescue animal's age is often genuinely
         * unknown, and a pill reading "Age unknown" would take up the same space as a real fact
         * while telling the user nothing they did not already know.
         *
         * Set explicitly both ways, because this card may be recycled from a pet that had a
         * birthday when this one does not.
         */
        private fun bindAgePill(summary: PetSummary) {
            val age = summary.pet.dateOfBirth?.let { formatAge(binding.root.context, it) }

            binding.agePill.text = age.orEmpty()
            binding.agePill.visibility = if (age != null) View.VISIBLE else View.GONE
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
}
