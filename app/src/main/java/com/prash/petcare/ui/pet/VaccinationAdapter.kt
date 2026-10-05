package com.prash.petcare.ui.pet

import android.content.res.ColorStateList
import android.icu.util.TimeZone
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.prash.petcare.R
import com.prash.petcare.databinding.ItemVaccinationBinding
import com.prash.petcare.util.formatDate
import java.util.Date
import java.util.Locale

/** Draws the rows of a pet's vaccination history card (item_vaccination.xml). */
class VaccinationAdapter : ListAdapter<VaccinationRow, VaccinationAdapter.RowViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowViewHolder {
        val binding = ItemVaccinationBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return RowViewHolder(binding)
    }

    override fun onBindViewHolder(holder: RowViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    /** Holds one row's views. ViewBinding, so no findViewById and no chance of a wrong cast. */
    class RowViewHolder(
        private val binding: ItemVaccinationBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        /** The two halves of the date column — "22" and "SEP 25". */
        private val dayFormat = android.icu.text.SimpleDateFormat("d", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
        private val monthFormat = android.icu.text.SimpleDateFormat("MMM yy", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }

        // Note the import above: ICU's formatters take ICU's own TimeZone class, not java.util's.

        fun bind(row: VaccinationRow) {
            val context = binding.root.context
            val record = row.record
            val givenOn = Date(record.dateGiven)

            binding.dayText.text = dayFormat.format(givenOn)
            // The style upper-cases it at draw time, so the formatter's "Sep 25" needs no help here.
            binding.monthText.text = monthFormat.format(givenOn)

            binding.vaccineNameText.text = record.vaccineName
            binding.metaText.text = metaLine(row)

            bindStatus(row)

            // The whole row is described in one sentence rather than as four separate views.
            binding.root.contentDescription = context.getString(
                R.string.vaccination_row_description,
                record.vaccineName,
                formatDate(record.dateGiven),
                row.status.label(context, row.daysUntilDue)
            )
        }

        /** Builds the row's second line. */
        private fun metaLine(row: VaccinationRow): String {
            val context = binding.root.context
            val clinic = row.record.clinic?.takeIf { it.isNotBlank() }
            val dueOn = row.record.dateDue

            return when {
                dueOn != null && clinic != null -> context.getString(
                    R.string.vaccination_meta_next_due,
                    formatDate(dueOn),
                    clinic
                )

                dueOn != null -> context.getString(
                    R.string.vaccination_meta_next_due_no_clinic,
                    formatDate(dueOn)
                )

                clinic != null -> context.getString(R.string.vaccination_meta_one_off, clinic)

                else -> context.getString(R.string.vaccination_meta_one_off_no_clinic)
            }
        }

        /** Colours the bar and the pill from the row's status. */
        private fun bindStatus(row: VaccinationRow) {
            val context = binding.root.context
            val status = row.status

            binding.statusBar.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(context, status.barColorRes)
            )

            binding.statusPill.backgroundTintList = ColorStateList.valueOf(
                MaterialColors.getColor(binding.root, status.pillBackgroundAttr)
            )
            binding.statusPill.setTextColor(
                MaterialColors.getColor(binding.root, status.pillInkAttr)
            )

            binding.statusPill.text = status.label(context, row.daysUntilDue)
        }
    }

    /** Tells DiffUtil which rows are the same row, and which of those have actually changed. */
    private object DiffCallback : DiffUtil.ItemCallback<VaccinationRow>() {

        override fun areItemsTheSame(oldItem: VaccinationRow, newItem: VaccinationRow): Boolean =
            oldItem.record.id == newItem.record.id

        override fun areContentsTheSame(oldItem: VaccinationRow, newItem: VaccinationRow): Boolean =
            oldItem == newItem
    }
}
