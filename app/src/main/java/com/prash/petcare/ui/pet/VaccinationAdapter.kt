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

/**
 * Draws the rows of a pet's vaccination history card (item_vaccination.xml).
 *
 * Part of the **View** layer, and a deliberately thin one. [PetDetailViewModel] has already decided
 * which records appear, in what order, and what status each is in; this class only turns a
 * [VaccinationRow] into text, a colour and a spoken description. In particular the status rule is
 * *not* re-run here — doing so would give a row a pill whose colour came from one calculation and
 * whose wording came from another, and the two could disagree at a midnight boundary.
 *
 * A [ListAdapter] rather than a plain adapter because the pet detail state re-emits on every change
 * anywhere on the screen — a task ticked on another tab, a routine paused — and DiffUtil is what
 * stops every vaccination row being rebound each time.
 *
 * Nothing is clickable: the history is read-only in this pass, and editing or deleting a record can
 * follow the swipe-and-undo pattern the checklist and expenses lists already use.
 */
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

        /**
         * The two halves of the date column — "22" and "SEP 25".
         *
         * Built per holder rather than per bind, because formatters are comparatively expensive to
         * construct and a holder binds many times; the same arrangement as
         * [com.prash.petcare.ui.home.ComingUpAdapter].
         *
         * `android.icu` is the platform's own formatter from API 24, this app's minimum.
         *
         * **Both are fixed to UTC and to [Locale.US]**, for the reasons set out in
         * [com.prash.petcare.util.formatDate]: a stored date is a *calendar day* kept as midnight
         * UTC, so reading it in local time would print the previous day for anyone west of
         * Greenwich; a locale-driven month abbreviation would put a different language in a column
         * the rest of the card writes in English; and en-US is the one that abbreviates September to
         * three letters, which is what lets "SEP 25" fit the 40dp column that "SEPT 25" does not.
         *
         * "yy" is the two-digit year the design asks for. It is unambiguous here in a way it usually
         * is not, because the column already sits beside a full date on the line to its right.
         */
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

            // The whole row is described in one sentence rather than as four separate views — see the
            // accessibility note in item_vaccination.xml for why its children are hidden from
            // TalkBack. Read out in the order a sighted user takes the row in: what, when, how
            // urgent.
            binding.root.contentDescription = context.getString(
                R.string.vaccination_row_description,
                record.vaccineName,
                formatDate(record.dateGiven),
                row.status.label(context, row.daysUntilDue)
            )
        }

        /**
         * Builds the row's second line.
         *
         * Four shapes rather than one with blanks in it, because a missing clinic has to take the
         * separator with it: "Next due 4 Oct 2026 · " with nothing after the dot reads as text that
         * failed to load rather than as a fact that was never recorded.
         */
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

        /**
         * Colours the bar and the pill from the row's status.
         *
         * The pill's fill and its ink are always taken as a *pair* from the same status — see
         * attrs.xml — so text can never end up on a ground it is not guaranteed to be legible on.
         * They are resolved from the theme here rather than set in the style, because the style is
         * applied at inflation and would paint one state's colour for a frame before this corrected
         * it.
         *
         * The bar's colour is a plain resource (it is the same value in both themes) while the
         * pill's is a theme attribute (it is not) — see ui/pet/VaccinationStatus.kt for why the two
         * marks are declared differently.
         */
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

    /**
     * Tells DiffUtil which rows are the same row, and which of those have actually changed.
     *
     * The id identifies a record; the whole [VaccinationRow] is compared for content, which matters
     * more here than it looks. The row carries its derived status as well as the stored record, so a
     * record that crosses its due date overnight is seen as *changed* even though nothing in the
     * database moved — which is exactly what makes the pill redraw itself the next morning.
     */
    private object DiffCallback : DiffUtil.ItemCallback<VaccinationRow>() {

        override fun areItemsTheSame(oldItem: VaccinationRow, newItem: VaccinationRow): Boolean =
            oldItem.record.id == newItem.record.id

        override fun areContentsTheSame(oldItem: VaccinationRow, newItem: VaccinationRow): Boolean =
            oldItem == newItem
    }
}
