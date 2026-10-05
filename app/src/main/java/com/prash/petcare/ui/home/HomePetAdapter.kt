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

/** Draws the dashboard's sideways row of pet cards (item_home_pet.xml). */
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

    /** Holds one card's views. ViewBinding resolves every id once, when the card is created. */
    class PetCardViewHolder(
        private val binding: ItemHomePetBinding,
        private val photoScope: CoroutineScope,
        private val onPetClick: (petId: Long) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(pet: Pet) {
            val context = binding.root.context

            // Re-set on every bind: cards are recycled.
            binding.root.setOnClickListener { onPetClick(pet.id) }

            binding.petNameText.text = pet.name
            // The species enum holds database values (DOG, CAT); the chip shows the readable label.
            binding.speciesChip.text = context.getString(pet.species.labelRes())

            // The card is one control, so TalkBack reads it as the pet's name.
            binding.root.contentDescription = pet.name

            // Asynchronous, and careful about recycling — see ui/common/PetPhotoBinder.kt.
            binding.petPhotoImage.loadPetPhoto(pet.photoPath, photoScope)
        }
    }

    /** How [ListAdapter] compares two lists of pets: the same pet is the same id. */
    private object PetDiffCallback : DiffUtil.ItemCallback<Pet>() {
        override fun areItemsTheSame(oldItem: Pet, newItem: Pet): Boolean = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Pet, newItem: Pet): Boolean = oldItem == newItem
    }
}
