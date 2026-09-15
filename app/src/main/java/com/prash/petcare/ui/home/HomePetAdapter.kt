package com.prash.petcare.ui.home

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.prash.petcare.data.local.entity.Pet
import com.prash.petcare.databinding.ItemHomePetBinding
import com.prash.petcare.ui.common.labelRes
import com.prash.petcare.ui.common.loadPetPhoto
import kotlinx.coroutines.CoroutineScope

/**
 * Draws the dashboard's sideways row of pet cards (item_home_pet.xml).
 *
 * Part of the **View** layer: it only puts a pet's photo, species and name onto a card and reports
 * taps back to [HomeFragment], which decides where a tap goes. Every card is the same size, so unlike
 * the old dashboard there is one view type.
 *
 * Extends [ListAdapter], which takes each new list through `submitList` and works out what changed on a
 * background thread. That matters more than usual on this screen: the dashboard's state is rebuilt
 * every minute as the clock ticks, and with a plain adapter each rebuild would redraw every card and
 * decode every photo again. DiffUtil sees the pets are unchanged and leaves the cards alone.
 *
 * @property photoScope The coroutine scope photo loads run in — the fragment's view lifecycle scope, so
 *   leaving the screen cancels decodes still in flight rather than letting them write into dead views.
 * @property onPetClick Called with the tapped pet's id.
 */
class HomePetAdapter(
    private val photoScope: CoroutineScope,
    private val onPetClick: (petId: Long) -> Unit
) : ListAdapter<Pet, HomePetAdapter.PetCardViewHolder>(PetDiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PetCardViewHolder {
        // false: the RecyclerView attaches the view itself once it has measured it.
        val binding = ItemHomePetBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return PetCardViewHolder(binding, photoScope, onPetClick)
    }

    override fun onBindViewHolder(holder: PetCardViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    /**
     * Holds one card's views. ViewBinding resolves every id once, when the card is created, so binding a
     * recycled card only reassigns text and never searches the hierarchy.
     */
    class PetCardViewHolder(
        private val binding: ItemHomePetBinding,
        private val photoScope: CoroutineScope,
        private val onPetClick: (petId: Long) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(pet: Pet) {
            val context = binding.root.context

            // Re-set on every bind: cards are recycled, and a listener left from a previous bind would
            // open whichever pet used to occupy this card.
            binding.root.setOnClickListener { onPetClick(pet.id) }

            binding.petNameText.text = pet.name
            // The species enum holds database values (DOG, CAT); the chip shows the readable label,
            // upper-cased by its style.
            binding.speciesChip.text = context.getString(pet.species.labelRes())

            // The card is one control, so TalkBack reads it as the pet's name rather than stopping on
            // the chip and the photo separately.
            binding.root.contentDescription = pet.name

            // Asynchronous, and careful about recycling — see ui/common/PetPhotoBinder.kt.
            binding.petPhotoImage.loadPetPhoto(pet.photoPath, photoScope)
        }
    }

    /**
     * How [ListAdapter] compares two lists of pets: the same pet is the same id, and a card needs
     * redrawing only if the pet row itself changed. [Pet] is a data class, so `==` compares every field.
     */
    private object PetDiffCallback : DiffUtil.ItemCallback<Pet>() {
        override fun areItemsTheSame(oldItem: Pet, newItem: Pet): Boolean = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Pet, newItem: Pet): Boolean = oldItem == newItem
    }
}
