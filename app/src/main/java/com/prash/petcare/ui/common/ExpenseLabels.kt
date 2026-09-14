package com.prash.petcare.ui.common

import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.prash.petcare.R
import com.prash.petcare.data.local.entity.ExpenseCategory
import java.text.DecimalFormatSymbols
import java.text.NumberFormat
import kotlin.math.abs
import kotlin.math.round

/**
 * Turns spending values into text for the screen.
 *
 * The companion to [CareLabels.kt][labelRes] for the expense side of the app — category names,
 * badges, colours and money — and it exists for the same reason: labels and icons belong to the UI layer, so the data layer stays free of Android
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
 * A spending category's three-or-four letter badge on an expense card: "MED", "FOOD", "GRM".
 *
 * Abbreviated because the badge is a small square, and a column of equal-sized badges scans faster
 * than full names of different lengths would.
 */
@StringRes
fun ExpenseCategory.abbreviationRes(): Int = when (this) {
    ExpenseCategory.FOOD -> R.string.expense_abbr_food
    ExpenseCategory.VET -> R.string.expense_abbr_vet
    ExpenseCategory.MEDICATION -> R.string.expense_abbr_medication
    ExpenseCategory.GROOMING -> R.string.expense_abbr_grooming
    ExpenseCategory.TOYS -> R.string.expense_abbr_toys
    ExpenseCategory.ACCESSORIES -> R.string.expense_abbr_accessories
    ExpenseCategory.INSURANCE -> R.string.expense_abbr_insurance
    ExpenseCategory.TRAINING -> R.string.expense_abbr_training
    ExpenseCategory.OTHER -> R.string.expense_abbr_other
}

/**
 * The colour that stands for a spending category: its segment in the total card's stacked bar, and
 * the dot beside it in the legend.
 *
 * Fixed per category rather than handed out by rank, so "green is grooming" stays true from one
 * month to the next and between pets — a colour that meant medication in September and food in
 * October would make the bar impossible to read at a glance. See the category section of colors.xml
 * for how the values were chosen.
 */
@ColorRes
fun ExpenseCategory.colorRes(): Int = when (this) {
    ExpenseCategory.FOOD -> R.color.expense_category_food
    ExpenseCategory.VET -> R.color.expense_category_vet
    ExpenseCategory.MEDICATION -> R.color.expense_category_medication
    ExpenseCategory.GROOMING -> R.color.expense_category_grooming
    ExpenseCategory.TOYS -> R.color.expense_category_toys
    ExpenseCategory.ACCESSORIES -> R.color.expense_category_accessories
    ExpenseCategory.INSURANCE -> R.color.expense_category_insurance
    ExpenseCategory.TRAINING -> R.color.expense_category_training
    ExpenseCategory.OTHER -> R.color.expense_category_other
}

/**
 * The currency symbol written before every amount in the app: Nepalese rupees.
 *
 * A fixed symbol rather than `NumberFormat.getCurrencyInstance()`, which picks the currency from the
 * device's *region*. The app records what the user spent in rupees; a phone set to English (United
 * States) would otherwise relabel every one of those amounts as dollars without changing a digit —
 * the same numbers, silently claiming to be a different currency.
 *
 * Followed by a non-breaking space ("Rs 4,665"), so a line that wraps can never leave "Rs" stranded
 * at the end of one line and the number at the start of the next. Kept in step with the
 * `currency_symbol` string the expense form uses as its field prefix.
 */
private const val CURRENCY_PREFIX = "Rs\u00A0"

/**
 * Formats a stored amount as money for display: "Rs 4,665", or "Rs 425.50".
 *
 * **Paise only when there are any.** Most pet spending is in whole rupees, and ".00" on every row
 * of a list is noise that makes the figures harder to compare; an amount that does have paise shows
 * both digits so "Rs 425.5" never appears. The total card always shows them — see [splitCurrency].
 *
 * The digits follow the device's locale for grouping and the decimal separator. The formatter is
 * created per call on purpose: [NumberFormat] is not thread-safe, and a formatter cached for the old
 * locale would survive the user changing language.
 */
fun formatCurrency(amount: Double): String {
    val isWhole = abs(amount - round(amount)) < HALF_PAISA
    val format = NumberFormat.getNumberInstance().apply {
        val decimals = if (isWhole) 0 else 2
        minimumFractionDigits = decimals
        maximumFractionDigits = decimals
    }
    return CURRENCY_PREFIX + format.format(amount)
}

/**
 * An amount split into the part before the decimal separator and the part from it onwards:
 * ("Rs 5,240", ".00").
 *
 * For the total card, where the paise are drawn smaller than the rupees. The split is done on the
 * *formatted* string, at the locale's own separator, so rounding is settled first — 5,239.999 becomes
 * "5,240" and ".00" rather than "5,239" and ".100" — and a locale that writes "5.240,00" splits at
 * the comma.
 */
fun splitCurrency(amount: Double): Pair<String, String> {
    val formatted = NumberFormat.getNumberInstance().apply {
        minimumFractionDigits = 2
        maximumFractionDigits = 2
    }.format(amount)

    val separatorIndex = formatted.lastIndexOf(DecimalFormatSymbols.getInstance().decimalSeparator)
    return if (separatorIndex < 0) {
        (CURRENCY_PREFIX + formatted) to ""
    } else {
        (CURRENCY_PREFIX + formatted.substring(0, separatorIndex)) to formatted.substring(separatorIndex)
    }
}

/** Anything closer to a whole rupee than half a paisa prints as one. */
private const val HALF_PAISA = 0.005

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
