package com.prash.petcare.ui.common

import android.view.LayoutInflater
import android.view.View
import androidx.annotation.LayoutRes
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup

/* Binds a single-selection row of filter chips — the category and pet rows on the Tasks tab. */

/** One chip: the key the caller uses to recognise it, and the text shown on it. */
data class ChipOption(val key: String, val label: CharSequence)

/** Shows [options] as chips, checks the one whose key is [selectedKey], and reports taps. */
fun ChipGroup.bindOptions(
    options: List<ChipOption>,
    selectedKey: String,
    @LayoutRes chipLayout: Int,
    onSelected: (key: String) -> Unit
) {
    if (!matches(options)) rebuild(options, chipLayout)

    // Detached while the selection is applied: otherwise checking a chip here would fire the listener.
    setOnCheckedStateChangeListener(null)

    val selectedChip = (0 until childCount)
        .map { getChildAt(it) }
        .firstOrNull { it.tag == selectedKey }
    if (selectedChip != null) check(selectedChip.id) else clearCheck()

    setOnCheckedStateChangeListener { group, checkedIds ->
        // The list is briefly empty during a change; the row is selectionRequired, so it settles.
        val checkedId = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
        val key = group.findViewById<View>(checkedId)?.tag as? String
            ?: return@setOnCheckedStateChangeListener
        onSelected(key)
    }
}

/** True when the chips on screen are already these options, in this order, with these labels. */
private fun ChipGroup.matches(options: List<ChipOption>): Boolean {
    if (childCount != options.size) return false

    return options.withIndex().all { (index, option) ->
        val chip = getChildAt(index) as? Chip ?: return false
        chip.tag == option.key && chip.text.toString() == option.label.toString()
    }
}

/** Replaces the row with one chip per option. */
private fun ChipGroup.rebuild(options: List<ChipOption>, @LayoutRes chipLayout: Int) {
    removeAllViews()
    val inflater = LayoutInflater.from(context)

    options.forEach { option ->
        val chip = inflater.inflate(chipLayout, this, false) as Chip

        // A ChipGroup reports its selection by view id, so each chip needs a unique.
        chip.id = View.generateViewId()
        chip.tag = option.key
        chip.text = option.label

        addView(chip)
    }
}
