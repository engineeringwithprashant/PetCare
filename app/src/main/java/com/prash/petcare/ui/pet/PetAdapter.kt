package com.prash.petcare.ui.pet

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.prash.petcare.R
import com.prash.petcare.databinding.ItemPetBinding
import com.prash.petcare.ui.common.formatAgeShort
import com.prash.petcare.ui.common.formatWeightWithUnit
import com.prash.petcare.ui.common.labelRes
import com.prash.petcare.ui.common.loadPetPhoto
import kotlinx.coroutines.CoroutineScope
import java.text.NumberFormat

/** Draws the pet cards on the Pets tab — one tall card per pet (res/layout/item_pet.xml). */
class PetAdapter(
    private val photoScope: CoroutineScope,
    private val onPetClick: (petId: Long) -> Unit
) : ListAdapter<PetCard, PetAdapter.PetViewHolder>(PetCardDiffCallback) {

    /** Creates a card. Called only when there is no recycled view to reuse. */
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PetViewHolder {
        val binding = ItemPetBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            // false: the RecyclerView attaches the view itself once it has measured it.
            false
        )
        return PetViewHolder(binding, photoScope, onPetClick)
    }

    /** Fills a (possibly recycled) card with the pet at [position]. */
    override fun onBindViewHolder(holder: PetViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    /** Holds the views for one card. */
    class PetViewHolder(
        private val binding: ItemPetBinding,
        private val photoScope: CoroutineScope,
        private val onPetClick: (petId: Long) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(card: PetCard) {
            val context = binding.root.context
            val pet = card.pet

            // Re-set on every bind, not just at creation: cards are recycled.
            binding.root.setOnClickListener { onPetClick(pet.id) }

            binding.petNameText.text = pet.name

            // The species enum stores database values (DOG, CAT), so it is translated to a readable label first.
            binding.speciesChip.text = context.getString(pet.species.labelRes())

            // Hidden until the owner's name has loaded, rather than showing "Cared for by" followed by nothing.
            binding.caredForByText.isVisible = card.ownerName.isNotBlank()
            binding.caredForByText.text =
                context.getString(R.string.pets_card_cared_for_by, card.ownerName)

            bindStats(card)

            // Asynchronous, and careful about recycling — see ui/common/PetPhotoBinder.kt.
            binding.petPhotoImage.loadPetPhoto(pet.photoPath, photoScope)
        }

        /** Fills the age, weight and due-today blocks. */
        private fun bindStats(card: PetCard) {
            val context = binding.root.context
            val pet = card.pet

            // The short forms ("5 mo", "3 yrs") because each block is only a third of the card wide.
            binding.ageValueText.showStat(pet.dateOfBirth?.let { formatAgeShort(context, it) })

            // "25 kg" or "55.1 lb". The unit is part of the value because the label underneath says WEIGHT.
            binding.weightValueText.showStat(
                pet.weightKg?.let { formatWeightWithUnit(context, it, card.weightUnit) }
            )

            // NumberFormat rather than toString(), so the digit is in the device's own numerals.
            binding.dueTodayValueText.text = NumberFormat.getInstance().format(card.tasksDueToday)
        }

        /** Shows a stat's value, or a dash when it was never recorded. */
        private fun TextView.showStat(value: String?) {
            if (value != null) {
                text = value
                contentDescription = null
            } else {
                setText(R.string.pets_stat_unknown)
                contentDescription = context.getString(R.string.cd_pets_stat_unknown)
            }
        }
    }

    /** Tells [ListAdapter] how to compare two lists of cards. */
    private object PetCardDiffCallback : DiffUtil.ItemCallback<PetCard>() {

        override fun areItemsTheSame(oldItem: PetCard, newItem: PetCard): Boolean =
            oldItem.pet.id == newItem.pet.id

        // PetCard is a data class holding a data class, so `==` compares the pet.
        override fun areContentsTheSame(oldItem: PetCard, newItem: PetCard): Boolean =
            oldItem == newItem
    }
}
