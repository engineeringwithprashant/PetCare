package com.prash.petcare.ui.common

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.prash.petcare.R
import com.prash.petcare.data.local.entity.ExpenseCategory
import java.text.NumberFormat

/**
 * Turns spending values into text for the screen.
 *
 * The companion to [CareLabels.kt][labelRes] for the expense side of the app, and it exists for the
 * same reason: labels and icons belong to the UI layer, so the data layer stays free of Android
 * resource references and can be unit-tested on a plain JVM.
 *
 * [ExpenseCategory] is deliberately kept separate from `TaskCategory` even though a few names
 * overlap, so these mappings are separate too — sharing them would couple two enums that answer
 * different questions.
 */

/** Display name for a spending category, e.g. MEDICATION becomes "Medication". */
@StringRes
fun ExpenseCategory.labelRes(): Int = when (this) {
    ExpenseCategory.FOOD -> R.string.expense_category_food
    ExpenseCategory.VET -> R.string.expense_category_vet
    ExpenseCategory.MEDICATION -> R.string.expense_category_medication
    ExpenseCategory.GROOMING -> R.string.expense_category_grooming
    ExpenseCategory.TOYS -> R.string.expense_category_toys
    ExpenseCategory.ACCESSORIES -> R.string.expense_category_accessories
    ExpenseCategory.INSURANCE -> R.string.expense_category_insurance
    ExpenseCategory.TRAINING -> R.string.expense_category_training
    ExpenseCategory.OTHER -> R.string.expense_category_other
}

/**
 * Icon for a spending category.
 *
 * Pairs with [labelRes] so a category can be shown as a symbol alongside its name, which is read
 * far faster than a word when scanning the breakdown for where the money went.
 *
 * Like the labels, this `when` has no `else` branch: Kotlin then requires every constant to be
 * listed, so adding a category will not compile until an icon has been chosen for it.
 */
@DrawableRes
fun ExpenseCategory.iconRes(): Int = when (this) {
    ExpenseCategory.FOOD -> R.drawable.ic_category_feeding
    ExpenseCategory.VET -> R.drawable.ic_category_vet
    ExpenseCategory.MEDICATION -> R.drawable.ic_category_medication
    ExpenseCategory.GROOMING -> R.drawable.ic_category_grooming
    ExpenseCategory.TOYS -> R.drawable.ic_category_toys
    ExpenseCategory.ACCESSORIES -> R.drawable.ic_category_accessories
    ExpenseCategory.INSURANCE -> R.drawable.ic_category_insurance
    ExpenseCategory.TRAINING -> R.drawable.ic_category_training
    ExpenseCategory.OTHER -> R.drawable.ic_category_other
}

/**
 * Formats a stored amount as money for display, e.g. 24.5 becomes "£24.50".
 *
 * [NumberFormat.getCurrencyInstance] is used rather than a hand-written pattern because it follows
 * the device's locale for all three things that vary between countries: the currency symbol, where
 * that symbol sits relative to the number, and which characters separate the thousands and the
 * decimals. A fixed "£%.2f" would be wrong for every user outside one country.
 *
 * The formatter is created per call rather than cached in a property on purpose: [NumberFormat] is
 * not thread-safe, and the locale can change while the app is running — the user switching language
 * in system settings recreates the activity, and a cached formatter built for the old locale would
 * survive that and keep printing the old currency.
 */
fun formatCurrency(amount: Double): String =
    NumberFormat.getCurrencyInstance().format(amount)

/**
 * Formats a stored amount for an editable text field, e.g. 24.5 becomes "24.5" or "24,5".
 *
 * Separate from [formatCurrency] because a form field has different needs from a label. A currency
 * symbol and thousands separators are helpful when reading a total, but in a field they are text
 * the user has to delete before they can correct the figure — so grouping is switched off and no
 * symbol is added.
 *
 * It is still locale-aware, which is the point of not simply calling [Double.toString]: that always
 * emits a full stop, while the `numberDecimal` keypad offers whichever separator the locale uses.
 * A German user would otherwise be shown "24.5" in a field whose keyboard has no full stop on it.
 * It also avoids [Double.toString]'s scientific notation, which would turn a large amount into
 * something like "1.0E7".
 *
 * The two-decimal ceiling matches how money is written; a stored value with more precision than
 * that could only have come from arithmetic the app never does.
 */
fun formatAmountForInput(amount: Double): String {
    val formatter = NumberFormat.getNumberInstance().apply {
        isGroupingUsed = false
        maximumFractionDigits = 2
    }
    return formatter.format(amount)
}
