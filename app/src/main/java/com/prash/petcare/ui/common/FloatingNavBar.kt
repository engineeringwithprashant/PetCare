package com.prash.petcare.ui.common

import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.annotation.DrawableRes
import androidx.annotation.IdRes
import androidx.annotation.StringRes
import androidx.appcompat.widget.TooltipCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import com.prash.petcare.R
import com.prash.petcare.databinding.ViewNavTabBinding
import java.text.NumberFormat

/** The five tabs of the app's bottom navigation bar, in the order they are drawn. */
// The annotations are targeted at the getters rather than left to default to the constructor parameters.
enum class NavTab(
    @get:IdRes val destinationId: Int,
    @get:StringRes val labelRes: Int,
    @get:DrawableRes val iconRes: Int,
    @get:DrawableRes val activeIconRes: Int,
    private vararg val subDestinations: Int
) {

    /** The dashboard. Nothing sits under it: everything it links to belongs to another tab. */
    HOME(
        R.id.homeFragment,
        R.string.nav_home,
        R.drawable.ic_tab_home,
        R.drawable.ic_tab_home_active
    ),

    /** The pet list, and everything about one pet: its page, the add/edit form. */
    PETS(
        R.id.petsFragment,
        R.string.nav_pets,
        R.drawable.ic_tab_pets,
        R.drawable.ic_tab_pets_active,
        R.id.petDetailFragment,
        R.id.addEditPetFragment,
        R.id.addRoutineFragment,
        R.id.routineDetailFragment,
        R.id.addVaccinationFragment
    ),

    /** Today's care checklist, and the form for adding or editing a task on it. */
    TASKS(
        R.id.checklistFragment,
        R.string.nav_tasks,
        R.drawable.ic_tab_tasks,
        R.drawable.ic_tab_tasks_active,
        R.id.addEditTaskFragment
    ),

    /** Saved vets, groomers, parks and shops, the screen for saving another, and the form for changing one. */
    PLACES(
        R.id.placesFragment,
        R.string.nav_places,
        R.drawable.ic_tab_places,
        R.drawable.ic_tab_places_active,
        R.id.addPlaceFragment,
        R.id.addEditPlaceFragment
    ),

    /** Spending, and the form for logging an expense. */
    MONEY(
        R.id.expensesFragment,
        R.string.nav_money,
        R.drawable.ic_tab_money,
        R.drawable.ic_tab_money_active,
        R.id.addEditExpenseFragment
    );

    companion object {

        /** The tab a destination belongs to, or null if it belongs to none. */
        fun forDestination(@IdRes destinationId: Int): NavTab? = values().firstOrNull { tab ->
            destinationId == tab.destinationId || destinationId in tab.subDestinations
        }
    }
}

/** The row of tabs inside the app's floating bottom navigation bar. */
class FloatingNavBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    /** Called when the user taps a tab. Set by MainActivity, which does the navigating. */
    var onTabSelected: ((NavTab) -> Unit)? = null

    /** Each tab's inflated views, in the enum's order, which is the order they are laid out in. */
    private val tabs: Map<NavTab, ViewNavTabBinding>

    /** Which tab is currently lit, or null before the first destination arrives. */
    private var selectedTab: NavTab? = null

    /** The running width animation, kept so a fast second tap can cancel it rather than fight it. */
    private var flexAnimator: ValueAnimator? = null

    private val activePadding =
        resources.getDimensionPixelSize(R.dimen.nav_tab_active_padding_horizontal)
    private val badgeMarginEnd = resources.getDimensionPixelSize(R.dimen.nav_badge_margin_end)
    private val badgeMarginEndActive =
        resources.getDimensionPixelSize(R.dimen.nav_badge_margin_end_active)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        // The tabs have no text to line up at rest.
        isBaselineAligned = false

        val inflater = LayoutInflater.from(context)
        val gap = resources.getDimensionPixelSize(R.dimen.nav_tab_gap)

        tabs = NavTab.values().associateWith { tab ->
            val binding = ViewNavTabBinding.inflate(inflater, this, true)
            val label = context.getString(tab.labelRes)

            binding.root.apply {
                updateLayoutParams<LayoutParams> {
                    // Zero width plus a weight: the row's space is shared in proportion to the weights.
                    width = 0
                    weight = INACTIVE_FLEX
                    // LinearLayout has no gap property.
                    marginStart = if (tab.ordinal == 0) 0 else gap
                }

                // All five tabs are inflated from the same layout and so share its view ids.
                isSaveFromParentEnabled = false

                contentDescription = label
                setOnClickListener { onTabSelected?.invoke(tab) }
            }

            TooltipCompat.setTooltipText(binding.root, label)
            binding.navTabIcon.setImageResource(tab.iconRes)
            binding.navTabLabel.text = label

            binding
        }
    }

    /** Lights [tab] and unlights whichever was lit before, animating the change. */
    fun selectTab(tab: NavTab) {
        if (tab == selectedTab) return

        val previous = selectedTab
        selectedTab = tab

        tabs.forEach { (candidate, binding) ->
            val isActive = candidate == tab

            binding.root.isSelected = isActive
            // The active icon is the same outline at a heavier stroke.
            binding.navTabIcon.setImageResource(
                if (isActive) candidate.activeIconRes else candidate.iconRes
            )
            // Made visible at the start of the widening, not the end.
            binding.navTabLabel.isVisible = isActive
            binding.navTabBadge.updateLayoutParams<FrameLayout.LayoutParams> {
                marginEnd = if (isActive) badgeMarginEndActive else badgeMarginEnd
            }
            applyContentInsets(binding, isActive)
        }

        animateFlex(previous, tab)
    }

    /** Insets the icon and label inside the tab, leaving the badge its corner. */
    private fun applyContentInsets(binding: ViewNavTabBinding, isActive: Boolean) {
        val start = if (isActive) activePadding else 0
        val end = if (isActive) start + badgeWidthOf(binding) else 0

        binding.navTabContent.setPaddingRelative(start, 0, end, 0)
    }

    /** How much room the badge needs, or zero when it is not showing. */
    private fun badgeWidthOf(binding: ViewNavTabBinding): Int {
        val badge = binding.navTabBadge
        if (!badge.isVisible) return 0

        val unbounded = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        badge.measure(unbounded, unbounded)

        return badge.measuredWidth
    }

    /** Shows the number of care tasks still open today on the Tasks tab, or hides the badge at zero. */
    fun setOpenTaskCount(count: Int) {
        val binding = tabs.getValue(NavTab.TASKS)
        val label = context.getString(NavTab.TASKS.labelRes)

        binding.navTabBadge.isVisible = count > 0
        // NumberFormat rather than toString(): toString() always writes Western Arabic digits, and a device set.
        binding.navTabBadge.text = NumberFormat.getInstance().format(count)
        binding.root.contentDescription = if (count > 0) {
            resources.getQuantityString(R.plurals.nav_tab_open_tasks, count, label, count)
        } else {
            label
        }

        // The room the label needs to keep clear has just changed — the badge may have appeared, gone.
        applyContentInsets(binding, selectedTab == NavTab.TASKS)
    }

    /** Animates the two tabs whose width is changing, and snaps on the very first selection. */
    private fun animateFlex(previous: NavTab?, next: NavTab) {
        flexAnimator?.cancel()

        if (previous == null) {
            tabs.forEach { (tab, binding) ->
                binding.root.setFlex(if (tab == next) ACTIVE_FLEX else INACTIVE_FLEX)
            }
            requestLayout()
            return
        }

        val startFlex = tabs.mapValues { (_, binding) -> binding.root.flex }

        flexAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = FLEX_DURATION_MILLIS
            interpolator = FLEX_INTERPOLATOR
            addUpdateListener { animator ->
                val fraction = animator.animatedFraction
                tabs.forEach { (tab, binding) ->
                    val from = startFlex.getValue(tab)
                    val to = if (tab == next) ACTIVE_FLEX else INACTIVE_FLEX
                    binding.root.setFlex(from + (to - from) * fraction)
                }
                // One request for the whole row rather than one per tab.
                requestLayout()
            }
            start()
        }
    }

    /** This tab's current share of the row. */
    private val View.flex: Float get() = (layoutParams as LayoutParams).weight

    /** Sets the tab's share of the row without asking for a layout pass. */
    private fun View.setFlex(value: Float) {
        (layoutParams as LayoutParams).weight = value
    }

    private companion object {

        /** An inactive tab's share of the row: one part, the same as each of its neighbours. */
        const val INACTIVE_FLEX = 1f

        /** The active tab's share: nearly twice an inactive one. */
        const val ACTIVE_FLEX = 1.85f

        /** How long the pill takes to grow. */
        const val FLEX_DURATION_MILLIS = 260L

        /** The curve the width follows: quick to leave, slow to arrive. */
        val FLEX_INTERPOLATOR = PathInterpolator(0.2f, 0.8f, 0.2f, 1f)
    }
}
