package com.prash.petcare.ui.common

import android.view.LayoutInflater
import android.view.View
import androidx.annotation.LayoutRes
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup

/*
 * Binds a single-selection row of filter chips — the category and pet rows on the Tasks tab, the
 * category row on the Places tab, and the pet row on the Money tab.
 *
 * Part of the View layer. Every one of those rows begins with an "All" option that is not a pet or a
 * category at all, which is why the options are generic keys rather than pets. Each option is identified by a string key the caller chooses ("all", "FEEDING",
 * "42"), so this file knows nothing about pets or categories and serves both rows.
 */

/**
 * One chip: the key the caller uses to recognise it, and the text shown on it.
 *
 * @property key Stable and unique within its row. Used to find the chip again, so it must not
 *   change when the label is translated or a pet is renamed.
 */
data class ChipOption(val key: String, val label: CharSequence)

/**
 * Shows [options] as chips, checks the one whose key is [selectedKey], and reports taps.
 *
 * **Chips are only rebuilt when the options actually change.** The screen calls this on every state
 * emission — each tick of a task — and tearing down and re-inflating the row each time would lose
 * its horizontal scroll position and restart the selection animation mid-tap. So the chips on
 * screen are compared with the options asked for, and rebuilt only if they differ.
 *
 * @param chipLayout The layout inflated per chip. Inflated rather than built with `Chip(context)`
 *   because a Material component takes its appearance from the style applied at inflation — a chip
 *   constructed in code gets platform defaults and looks nothing like the app.
 * @param onSelected Called with a key when the user taps a chip — never when this function sets the
 *   selection itself, so redrawing cannot be mistaken for a tap.
 */
fun ChipGroup.bindOptions(
    options: List<ChipOption>,
    selectedKey: String,
    @LayoutRes chipLayout: Int,
    onSelected: (key: String) -> Unit
) {
    if (!matches(options)) rebuild(options, chipLayout)

    // Detached while the selection is applied: otherwise checking a chip here would fire the
    // listener, report a "selection" to the ViewModel, publish new state, and arrive back here.
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

        // A ChipGroup reports its selection by view id, so each chip needs a unique one; the option's
        // key goes in the tag, which is how a checked id is turned back into something meaningful.
        // The id is assigned before the chip is added, so it is never selectable as View.NO_ID.
        chip.id = View.generateViewId()
        chip.tag = option.key
        chip.text = option.label

        addView(chip)
    }
}
