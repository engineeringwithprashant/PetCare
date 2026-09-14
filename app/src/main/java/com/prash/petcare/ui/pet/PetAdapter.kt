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
import com.prash.petcare.ui.common.formatWeight
import com.prash.petcare.ui.common.labelRes
import com.prash.petcare.ui.common.loadPetPhoto
import kotlinx.coroutines.CoroutineScope
import java.text.NumberFormat

/**
 * Draws the pet cards on the Pets tab — one tall card per pet (res/layout/item_pet.xml).
 *
 * Part of the **View** layer. It receives ready-made [PetCard]s from [PetsViewModel] via the
 * fragment and only turns them into text and images; it performs no queries and makes no
 * navigation decisions.
 *
 * It is the middle of three adapters the fragment joins with a ConcatAdapter — the headline above
 * it and the add row below. Keeping the cards in an adapter of their own means this class knows
 * nothing about either, and DiffUtil animates pets being added or removed without the headline or
 * the add row being rebound.
 *
 * Extends [ListAdapter] rather than the plain `RecyclerView.Adapter`. ListAdapter takes a whole new
 * list via `submitList`, works out the difference against the previous one on a background thread,
 * and animates only the cards that actually changed. Calling `notifyDataSetChanged` by hand would
 * redraw — and re-decode the photo of — every visible card on each database update.
 *
 * @property photoScope The coroutine scope photo loads run in. Passed in rather than created here,
 *   because an adapter has no lifecycle of its own: the fragment hands it
 *   `viewLifecycleOwner.lifecycleScope`, so leaving the screen cancels every decode still in flight.
 * @property onPetClick Called with the tapped pet's id. A lambda rather than having the adapter
 *   navigate by itself, which keeps navigation in the fragment.
 */
class PetAdapter(
    private val photoScope: CoroutineScope,
    private val onPetClick: (petId: Long) -> Unit
) : ListAdapter<PetCard, PetAdapter.PetViewHolder>(PetCardDiffCallback) {

    /**
     * Creates a card. Called only when there is no recycled view to reuse, which is why inflation
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

    /** Fills a (possibly recycled) card with the pet at [position]. */
    override fun onBindViewHolder(holder: PetViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    /**
     * Holds the views for one card.
     *
     * The point of a ViewHolder is that view lookups happen once, when the card is created. Because
     * ViewBinding resolves every id up front, scrolling only reassigns text and never searches the
     * view hierarchy again.
     */
    class PetViewHolder(
        private val binding: ItemPetBinding,
        private val photoScope: CoroutineScope,
        private val onPetClick: (petId: Long) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(card: PetCard) {
            val context = binding.root.context
            val pet = card.pet

            // Re-set on every bind, not just at creation: cards are recycled, so a listener left
            // over from a previous bind would open whichever pet used to occupy this card.
            binding.root.setOnClickListener { onPetClick(pet.id) }

            binding.petNameText.text = pet.name

            // The species enum stores database values (DOG, CAT), so it is translated to a readable
            // label first. The chip's style upper-cases it at draw time.
            binding.speciesChip.text = context.getString(pet.species.labelRes())

            // Hidden until the owner's name has loaded, rather than showing "Cared for by" followed
            // by nothing. Set both ways because this card may be recycled from one that had a name.
            binding.caredForByText.isVisible = card.ownerName.isNotBlank()
            binding.caredForByText.text =
                context.getString(R.string.pets_card_cared_for_by, card.ownerName)

            bindStats(card)

            // Asynchronous, and careful about recycling — see ui/common/PetPhotoBinder.kt.
            binding.petPhotoImage.loadPetPhoto(pet.photoPath, photoScope)
        }

        /**
         * Fills the age, weight and due-today blocks.
         *
         * Unlike the old list row, which hid an unrecorded fact, every block is always shown and a
         * missing age or weight becomes a dash. The three blocks are a fixed grid here: hiding one
         * would leave the other two spread across thirds that no longer line up with the cards
         * above and below, which reads as a layout bug rather than as missing information.
         */
        private fun bindStats(card: PetCard) {
            val context = binding.root.context
            val pet = card.pet

            // The short forms ("5 mo", "3 yrs") because each block is only a third of the card wide.
            binding.ageValueText.showStat(pet.dateOfBirth?.let { formatAgeShort(context, it) })

            binding.weightValueText.showStat(
                pet.weightKg?.let {
                    context.getString(R.string.pet_detail_weight_format, formatWeight(it))
                }
            )

            // NumberFormat rather than toString(), so the digit is in the device's own numerals.
            binding.dueTodayValueText.text = NumberFormat.getInstance().format(card.tasksDueToday)
        }

        /**
         * Shows a stat's value, or a dash when it was never recorded.
         *
         * The content description is only set for the dash, which a screen reader would otherwise
         * read out as a punctuation mark; for a real value it is cleared so the text itself is read.
         * Both branches assign it because this view may be recycled from the other case.
         */
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

    /**
     * Tells [ListAdapter] how to compare two lists of cards.
     *
     * The two checks answer different questions, and both are needed:
     *  - [areItemsTheSame] asks "is this the same pet?", compared by id. This is how a card that
     *    moved is recognised as a move rather than a delete plus an insert — which matters here
     *    because a delete-plus-insert would re-decode the photo.
     *  - [areContentsTheSame] asks "does it still look the same?", deciding whether that card needs
     *    redrawing at all.
     */
    private object PetCardDiffCallback : DiffUtil.ItemCallback<PetCard>() {

        override fun areItemsTheSame(oldItem: PetCard, newItem: PetCard): Boolean =
            oldItem.pet.id == newItem.pet.id

        // PetCard is a data class holding a data class, so `==` compares the pet, the owner's name
        // and the due count together — and stays correct automatically if a property is added.
        override fun areContentsTheSame(oldItem: PetCard, newItem: PetCard): Boolean =
            oldItem == newItem
    }
}
