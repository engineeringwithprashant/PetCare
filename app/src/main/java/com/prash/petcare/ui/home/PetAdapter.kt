package com.prash.petcare.ui.home

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.annotation.StringRes
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.prash.petcare.R
import com.prash.petcare.data.local.entity.Pet
import com.prash.petcare.data.local.entity.PetSpecies
import com.prash.petcare.databinding.ItemPetBinding

/**
 * Displays the user's pets in the home screen's RecyclerView.
 *
 * Extends [ListAdapter] rather than the plain `RecyclerView.Adapter`. ListAdapter takes a whole
 * new list via `submitList` and works out the difference against the previous one on a background
 * thread, then animates only the rows that actually changed. Calling `notifyDataSetChanged` by
 * hand instead would redraw every visible row on each database update and lose scroll position
 * and animations.
 */
class PetAdapter : ListAdapter<Pet, PetAdapter.PetViewHolder>(PetDiffCallback) {

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
        return PetViewHolder(binding)
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
        private val binding: ItemPetBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(pet: Pet) {
            val context = binding.root.context

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
        }
    }

    /**
     * Tells [ListAdapter] how to compare two lists of pets.
     *
     * The two checks answer different questions, and both are needed:
     *  - [areItemsTheSame] asks "is this the same pet?", compared by id. This is how a row that
     *    moved is recognised as a move rather than a delete plus an insert.
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

/**
 * Maps a [PetSpecies] to its user-facing label.
 *
 * Kept in the UI layer, not on the enum itself, so the data layer stays free of Android resource
 * references and remains testable on a plain JVM.
 *
 * The `when` has no `else` branch on purpose: Kotlin then requires every constant to be listed, so
 * adding a species to the enum will not compile until a label is written for it. An `else` would
 * silently show the wrong name instead.
 */
@StringRes
private fun PetSpecies.labelRes(): Int = when (this) {
    PetSpecies.DOG -> R.string.species_dog
    PetSpecies.CAT -> R.string.species_cat
    PetSpecies.BIRD -> R.string.species_bird
    PetSpecies.RABBIT -> R.string.species_rabbit
    PetSpecies.FISH -> R.string.species_fish
    PetSpecies.REPTILE -> R.string.species_reptile
    PetSpecies.OTHER -> R.string.species_other
}
