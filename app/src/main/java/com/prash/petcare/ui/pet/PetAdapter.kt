package com.prash.petcare.ui.pet

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.prash.petcare.R
import com.prash.petcare.data.local.entity.Pet
import com.prash.petcare.databinding.ItemPetBinding
import com.prash.petcare.ui.common.formatAge
import com.prash.petcare.ui.common.labelRes
import com.prash.petcare.ui.common.loadPetPhoto
import kotlinx.coroutines.CoroutineScope

/**
 * Displays the user's pets in the Pets tab.
 *
 * Extends [ListAdapter] rather than the plain `RecyclerView.Adapter`. ListAdapter takes a whole
 * new list via `submitList` and works out the difference against the previous one on a background
 * thread, then animates only the rows that actually changed. Calling `notifyDataSetChanged` by
 * hand instead would redraw every visible row on each database update and lose scroll position
 * and animations.
 *
 * @property photoScope The coroutine scope photo loads run in. Passed in rather than created here,
 *   because an adapter has no lifecycle of its own: the fragment hands it
 *   `viewLifecycleOwner.lifecycleScope`, so leaving the screen cancels every decode still in flight.
 * @property onPetClick Called with the tapped pet. Passed in as a lambda rather than having the
 *   adapter navigate by itself, which keeps navigation decisions in the fragment and leaves this
 *   adapter reusable on any screen that lists pets.
 */
class PetAdapter(
    private val photoScope: CoroutineScope,
    private val onPetClick: (Pet) -> Unit
) : ListAdapter<Pet, PetAdapter.PetViewHolder>(PetDiffCallback) {

    /**
     * Creates a row. Called only when there is no recycled view to reuse, which is why inflation
     * lives here and the actual data is set in [onBindViewHolder].
     */
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PetViewHolder {
        val binding = ItemPetBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            // false: the RecyclerView attaches the view itself once it has measured it.
            false
        )
        return PetViewHolder(binding, photoScope, onPetClick)
    }

    /** Fills a (possibly recycled) row with the pet at [position]. */
    override fun onBindViewHolder(holder: PetViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    /**
     * Holds the views for one row.
     *
     * The point of a ViewHolder is that the lookups happen once, when the row is created. Because
     * ViewBinding resolves every id up front, scrolling only reassigns text and never searches the
     * view hierarchy again.
     */
    class PetViewHolder(
        private val binding: ItemPetBinding,
        private val photoScope: CoroutineScope,
        private val onPetClick: (Pet) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(pet: Pet) {
            val context = binding.root.context

            // Re-set on every bind, not just at creation: rows are recycled, so a listener left
            // over from a previous bind would open whichever pet used to occupy this row.
            binding.root.setOnClickListener { onPetClick(pet) }

            binding.petNameText.text = pet.name

            // The species enum stores database values (DOG, CAT), so it is translated to a
            // readable label before display.
            val speciesLabel = context.getString(pet.species.labelRes())

            // Breed is optional, and an empty string in the database should be treated the same
            // as a missing one — otherwise the subtitle would read "Dog · " with nothing after it.
            val breed = pet.breed?.takeIf { it.isNotBlank() }

            binding.petSubtitleText.text = if (breed != null) {
                context.getString(R.string.pet_species_and_breed, speciesLabel, breed)
            } else {
                speciesLabel
            }

            bindPills(pet)

            // Asynchronous, and careful about recycling — see ui/common/PetPhotoBinder.kt.
            binding.petPhotoImage.loadPetPhoto(pet.photoPath, photoScope)
        }

        /**
         * Fills the age and weight pills, hiding whichever facts were never recorded.
         *
         * Hidden rather than filled with a placeholder: a rescue animal's birthday is often
         * genuinely unknown, and a pill reading "Age unknown" would take up the same space as a real
         * fact while telling the user nothing.
         *
         * When neither is known, the whole row goes — otherwise the card would carry a band of empty
         * padding under the breed that looks like something failed to load.
         *
         * Every visibility is set explicitly both ways, because this row may be recycled from a pet
         * that recorded a fact this one does not.
         */
        private fun bindPills(pet: Pet) {
            val context = binding.root.context

            val age = pet.dateOfBirth?.let { formatAge(context, it) }
            binding.agePill.text = age.orEmpty()
            binding.agePill.visibility = if (age != null) View.VISIBLE else View.GONE

            val weight = pet.weightKg
                ?.let { context.getString(R.string.pet_detail_weight_format, it.toString()) }
            binding.weightPill.text = weight.orEmpty()
            binding.weightPill.visibility = if (weight != null) View.VISIBLE else View.GONE

            binding.pillRow.visibility =
                if (age != null || weight != null) View.VISIBLE else View.GONE
        }
    }

    /**
     * Tells [ListAdapter] how to compare two lists of pets.
     *
     * The two checks answer different questions, and both are needed:
     *  - [areItemsTheSame] asks "is this the same pet?", compared by id. This is how a row that
     *    moved is recognised as a move rather than a delete plus an insert — which matters here
     *    because a delete-plus-insert would re-decode the photo.
     *  - [areContentsTheSame] asks "does it still look the same?", deciding whether that row needs
     *    redrawing at all.
     */
    private object PetDiffCallback : DiffUtil.ItemCallback<Pet>() {

        override fun areItemsTheSame(oldItem: Pet, newItem: Pet): Boolean =
            oldItem.id == newItem.id

        // Pet is a data class, so `==` already compares every property — exactly what is needed
        // here, and it stays correct automatically if a property is added later.
        override fun areContentsTheSame(oldItem: Pet, newItem: Pet): Boolean =
            oldItem == newItem
    }
}
