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

/**
 * The five tabs of the app's bottom navigation bar, in the order they are drawn.
 *
 * Each entry holds everything that distinguishes one tab from the next: where it navigates to, what
 * it is called, the two drawings of its icon, and the other destinations that should still leave it
 * lit. Keeping all five facts in one table is what lets [FloatingNavBar] build the row from a single
 * layout file instead of five near-identical copies, and what makes "which tab is this screen part
 * of?" a question with one place to look.
 *
 * **Why [subDestinations] exists.** A tab is not just one screen. Opening a pet from the Pets list,
 * then adding a routine to that pet, never leaves the Pets section — the user has gone *deeper*, not
 * sideways — so the bar keeps Pets lit the whole way down. Without this the bar would go blank the
 * moment anybody tapped anything, which reads as having left the app's structure altogether.
 *
 * Screens reached from the shared header rather than from the bar — the profile, the notifications
 * list, the assistant — are deliberately in nobody's list. They are a step sideways out of whichever
 * tab the user was on, available from all five, and [forDestination] returning null for them is what
 * leaves that tab lit underneath. See [FloatingNavBar.selectTab].
 *
 * @param destinationId The destination in res/navigation/nav_graph.xml this tab navigates to. It is
 *   also how the bar recognises that it has arrived: the id is the only thing tying a tab to a
 *   screen, exactly as it was when the bar was a BottomNavigationView driven by a menu.
 * @param labelRes The tab's name, drawn inside the pill while it is active and used as its spoken
 *   name and long-press tooltip at all times.
 * @param iconRes The icon at rest.
 * @param activeIconRes The same icon drawn at the heavier stroke, for the active pill. Two drawables
 *   rather than one because a VectorDrawable's stroke width is fixed at inflation.
 * @param subDestinations Screens that belong to this tab without being it.
 */
// The annotations are targeted at the getters rather than left to default to the constructor
// parameters. They exist so lint can check a resource id is the right *kind* of id, and every read
// of these is through the property — `tab.iconRes` — which is the getter.
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

    /**
     * The pet list, and everything about one pet: its page, the add/edit form, its routines and the
     * screen that records a vaccination.
     */
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

    /**
     * Saved vets, groomers, parks and shops, the screen for saving another, and the form for
     * changing one.
     *
     * Both of those are steps *deeper* into this tab rather than journeys out of it — the user is
     * still working on their places — so the bar keeps Places lit while either is open.
     */
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

        /**
         * The tab a destination belongs to, or null if it belongs to none.
         *
         * Null is a meaningful answer rather than a failure — see the note on the class about the
         * screens reached from the shared header.
         */
        fun forDestination(@IdRes destinationId: Int): NavTab? = values().firstOrNull { tab ->
            destinationId == tab.destinationId || destinationId in tab.subDestinations
        }
    }
}

/**
 * The row of tabs inside the app's floating bottom navigation bar.
 *
 * Part of the View layer, and deliberately a *dumb* one: it draws five tabs, animates between them
 * and reports taps through [onTabSelected]. It navigates nowhere and reads no data. MainActivity
 * owns both ends — it tells the bar which tab is current when the destination changes, and it
 * performs the navigation when a tab is tapped — which is what keeps navigation described in one
 * place instead of half here and half there.
 *
 * **Why this is a custom view rather than a BottomNavigationView.** Material's bar lays every item
 * out as a column of equal width with the label beneath the icon, and has no concept of one item
 * being wider than the rest. This design turns on exactly that: the current tab grows to nearly
 * twice the width of an inactive one and its name appears beside its icon inside the pill, with the
 * width change being the thing that animates. No combination of BottomNavigationView attributes
 * produces it.
 *
 * **Why the tabs are built in code from [NavTab] rather than written out in XML.** The five differ
 * only in icon, label and destination. Written out, this file's layout would appear five times in
 * activity_main.xml, and every change to a tab's metrics would be five edits with four chances to
 * miss one. Instead the shape of a tab is declared once in res/layout/view_nav_tab.xml and inflated
 * per entry in the table above.
 *
 * **How the widening works.** Each tab is a child of this [LinearLayout] with a width of zero and a
 * layout weight, which is Android's equivalent of the design's `flex: 1` — the row's space is shared
 * out in proportion to the weights. Selecting a tab animates its weight from [INACTIVE_FLEX] to
 * [ACTIVE_FLEX] while the outgoing tab's travels the other way, so the pill grows as its neighbour
 * shrinks and the row stays exactly as wide as the bar.
 *
 * Everything else about the change — the pill fading in, the ink switching colour — is declared as
 * drawable and colour state lists (bg_nav_tab.xml, nav_tab_ink.xml) and follows from this class
 * setting `isSelected` on the tab. Only the three things a state list cannot express are done by
 * hand: the weight, the icon's stroke weight, and the label appearing.
 *
 * **Touch targets.** A tab is [R.dimen.nav_tab_height] (46dp) tall, above the 44dp minimum. Its
 * width is not fixed but works out above the minimum too, by construction: on the narrowest phone
 * Android supports (320dp) the bar's inner width is 278dp, which after four 4dp gaps leaves 262dp
 * shared between weights totalling 5.85 — about 45dp for the narrowest tab.
 *
 * **Accessibility.** Each tab carries its name as a content description and a long-press tooltip, so
 * an icon with no visible label is still announced and can still be identified by a sighted user who
 * does not recognise the glyph. The Tasks tab folds its open-task count into the same description —
 * see [setOpenTaskCount] — because a bare "3" read out after "Tasks" says nothing.
 */
class FloatingNavBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    /**
     * Called when the user taps a tab. Set by MainActivity, which does the navigating.
     *
     * The bar does not select the tab itself in response to a tap. It waits to be told, by the same
     * path that tells it about any other destination change — so a tap that navigation declines to
     * honour cannot leave the bar lighting up a screen the user is not on.
     */
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
        // The tabs have no text to line up at rest, and baseline alignment on a row of differently
        // sized children silently pushes them out of vertical centre.
        isBaselineAligned = false

        val inflater = LayoutInflater.from(context)
        val gap = resources.getDimensionPixelSize(R.dimen.nav_tab_gap)

        tabs = NavTab.values().associateWith { tab ->
            val binding = ViewNavTabBinding.inflate(inflater, this, true)
            val label = context.getString(tab.labelRes)

            binding.root.apply {
                updateLayoutParams<LayoutParams> {
                    // Zero width plus a weight: the row's space is shared in proportion to the
                    // weights, which is what the design's `flex` values describe.
                    width = 0
                    weight = INACTIVE_FLEX
                    // LinearLayout has no gap property, so the space between tabs is a margin on
                    // every tab but the first.
                    marginStart = if (tab.ordinal == 0) 0 else gap
                }

                // All five tabs are inflated from the same layout and so share its view ids. That is
                // harmless here — nothing looks them up — but it would make the framework's own
                // save/restore write five sets of view state under the same keys and restore the
                // wrong one. The bar rebuilds itself from the current destination anyway, so it has
                // no view state worth saving.
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

    /**
     * Lights [tab] and unlights whichever was lit before, animating the change.
     *
     * Everything that distinguishes an active tab is applied here in one pass, so there is no state
     * in which a tab has, say, its pill but not its label. Setting `isSelected` does most of it by
     * itself: the pill's fade and the ink's colour are declared as state lists and follow from that
     * single flag.
     *
     * Called by MainActivity on every destination change. Doing nothing when the tab is already
     * selected is what stops the pill re-animating each time the user moves *within* a section.
     */
    fun selectTab(tab: NavTab) {
        if (tab == selectedTab) return

        val previous = selectedTab
        selectedTab = tab

        tabs.forEach { (candidate, binding) ->
            val isActive = candidate == tab

            binding.root.isSelected = isActive
            // The active icon is the same outline at a heavier stroke. It has to be swapped rather
            // than restyled, because a VectorDrawable's stroke width is fixed when it is inflated.
            binding.navTabIcon.setImageResource(
                if (isActive) candidate.activeIconRes else candidate.iconRes
            )
            // Made visible at the *start* of the widening, not the end, so the pill grows around a
            // label that is already there and uncovers it. Gone rather than invisible: an invisible
            // label would still be measured and would push the icon off centre on every idle tab.
            binding.navTabLabel.isVisible = isActive
            binding.navTabBadge.updateLayoutParams<FrameLayout.LayoutParams> {
                marginEnd = if (isActive) badgeMarginEndActive else badgeMarginEnd
            }
            applyContentInsets(binding, isActive)
        }

        animateFlex(previous, tab)
    }

    /**
     * Insets the icon and label inside the tab, leaving the badge its corner.
     *
     * **Why this is not simply the design's `padding: 0 14px`.** It is, on the start side. On the end
     * side the active pill also has to keep clear of the badge, which is pinned to the tab's corner
     * and takes no part in the layout: centring the icon and label in the pill's *full* width puts
     * the end of the label underneath it, and on the Tasks tab — the only tab with a badge — the
     * count sat squarely on the last letter of "Tasks".
     *
     * Reserving the badge's width as extra end padding fixes it without moving the badge, because
     * the row is centred in whatever the padding leaves: adding the badge's width to one side shifts
     * the pair left by half of it, which is exactly enough to clear the corner and still leaves the
     * icon and label optically centred in the space they actually have.
     *
     * **The padding goes on the row, never on the tab.** A FrameLayout lays every child out inside
     * its padding, the badge included, so padding the tab would drag the badge inwards too and the
     * reservation would chase itself. See the note in view_nav_tab.xml.
     */
    private fun applyContentInsets(binding: ViewNavTabBinding, isActive: Boolean) {
        val start = if (isActive) activePadding else 0
        val end = if (isActive) start + badgeWidthOf(binding) else 0

        binding.navTabContent.setPaddingRelative(start, 0, end, 0)
    }

    /**
     * How much room the badge needs, or zero when it is not showing.
     *
     * Measured rather than assumed, because the badge grows with its count — a two-digit day is
     * wider than a one-digit one — and a fixed reservation would either waste room or stop clearing
     * the label. Measuring with an unspecified spec asks the view for its natural width, which is
     * available immediately and does not wait for a layout pass.
     */
    private fun badgeWidthOf(binding: ViewNavTabBinding): Int {
        val badge = binding.navTabBadge
        if (!badge.isVisible) return 0

        val unbounded = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        badge.measure(unbounded, unbounded)

        return badge.measuredWidth
    }

    /**
     * Shows the number of care tasks still open today on the Tasks tab, or hides the badge at zero.
     *
     * The bar is handed the figure rather than reading it, like everything else here — see the class
     * note. MainActivity collects it from the session and calls this.
     *
     * The count also replaces the tab's spoken name, because TalkBack reading "Tasks" and then "3"
     * conveys nothing about what the three are. The badge itself is excluded from accessibility in
     * view_nav_tab.xml so it is not read twice.
     */
    fun setOpenTaskCount(count: Int) {
        val binding = tabs.getValue(NavTab.TASKS)
        val label = context.getString(NavTab.TASKS.labelRes)

        binding.navTabBadge.isVisible = count > 0
        // NumberFormat rather than toString(): toString() always writes Western Arabic digits, and a
        // device set to, say, Bengali expects its own numerals. The same call the header's pet-count
        // badge makes — see ui/common/TabHeader.kt.
        binding.navTabBadge.text = NumberFormat.getInstance().format(count)
        binding.root.contentDescription = if (count > 0) {
            resources.getQuantityString(R.plurals.nav_tab_open_tasks, count, label, count)
        } else {
            label
        }

        // The room the label needs to keep clear has just changed — the badge may have appeared,
        // gone, or grown a digit — and this tab may be the active one right now.
        applyContentInsets(binding, selectedTab == NavTab.TASKS)
    }

    /**
     * Animates the two tabs whose width is changing, and snaps on the very first selection.
     *
     * The first call has no outgoing tab: the bar is being shown for the first time and there is
     * nothing to animate *from*, so an animation there would look like the bar assembling itself
     * every time the app opens.
     *
     * Every tab's weight is driven, not just the two that move. The four inactive ones are already
     * at [INACTIVE_FLEX] and so stay put, but interpolating all five from wherever they currently
     * are is what makes a tap during a running animation pick up smoothly from the half-finished
     * widths instead of jumping to the start of a fresh one.
     */
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
                // One request for the whole row rather than one per tab: a weight change is only a
                // pending value until the row is measured again, and measuring it five times a frame
                // would do the same work five times.
                requestLayout()
            }
            start()
        }
    }

    /** This tab's current share of the row. */
    private val View.flex: Float get() = (layoutParams as LayoutParams).weight

    /**
     * Sets the tab's share of the row without asking for a layout pass.
     *
     * Deliberately separate from the request, so the caller can move several tabs and re-measure
     * once — see [animateFlex].
     */
    private fun View.setFlex(value: Float) {
        (layoutParams as LayoutParams).weight = value
    }

    private companion object {

        /** An inactive tab's share of the row: one part, the same as each of its neighbours. */
        const val INACTIVE_FLEX = 1f

        /**
         * The active tab's share: nearly twice an inactive one. Enough to hold an icon and a short
         * word comfortably, and still narrow enough that all five tabs read as a single row rather
         * than as one button with four ornaments beside it.
         */
        const val ACTIVE_FLEX = 1.85f

        /** How long the pill takes to grow. */
        const val FLEX_DURATION_MILLIS = 260L

        /**
         * The curve the width follows: quick to leave, slow to arrive.
         *
         * Built once and shared, because an interpolator is stateless and allocating one per tap
         * would litter the heap on the app's most-used control. The four numbers are the control
         * points of a cubic Bézier, the same form CSS transitions are written in.
         */
        val FLEX_INTERPOLATOR = PathInterpolator(0.2f, 0.8f, 0.2f, 1f)
    }
}
