package com.prash.petcare.ui.common

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.prash.petcare.R

/**
 * Swipe gestures for a list, shared by every screen that uses them.
 *
 * Hooked up through [ItemTouchHelper], which detects the drag, animates the row and calls back
 * here. This class owns the parts that should look and behave identically wherever swiping appears
 * — the colours, the icons, and what is drawn behind a moving row. Which rows may be swiped, and
 * what a swipe means, are left to the caller, because that differs by screen.
 *
 * **The direction convention is fixed across the app**, so a gesture never means different things
 * in different places:
 *  - swipe **left** is destructive (bin icon, error colours);
 *  - swipe **right** is affirmative (tick icon, primary colours).
 *
 * A screen that only supports one of the two simply never allows the other, and that half is never
 * drawn.
 *
 * **Accessibility.** Swipes are invisible to a screen reader, so they are deliberately shortcuts
 * rather than the only route to anything: every action reachable by swiping is also reachable by a
 * button or a checkbox that TalkBack can find.
 *
 * @param context Used to load the icons and resolve theme colours.
 * @param allowedDirections Returns the [ItemTouchHelper] direction flags permitted for a given
 *   adapter position, or zero to make that row unswipeable. Called for every row, so a list mixing
 *   headings with content can protect the headings.
 * @param onSwipe Called with the position and the direction once a swipe completes.
 */
class SwipeActionCallback(
    context: Context,
    private val allowedDirections: (position: Int) -> Int,
    private val onSwipe: (position: Int, direction: Int) -> Unit
) : ItemTouchHelper.Callback() {

    // Colours come from the Material 3 theme rather than being hard-coded, so the backgrounds
    // follow the app's palette and stay legible in dark mode. The "container" roles are the muted
    // variants intended to sit behind content.
    private val deleteBackgroundColor = MaterialColors.getColor(
        context,
        com.google.android.material.R.attr.colorErrorContainer,
        context.getColor(android.R.color.holo_red_light)
    )
    private val confirmBackgroundColor = MaterialColors.getColor(
        context,
        com.google.android.material.R.attr.colorPrimaryContainer,
        context.getColor(android.R.color.holo_green_light)
    )

    private val deleteIcon: Drawable? = loadTintedIcon(
        context,
        R.drawable.ic_delete,
        com.google.android.material.R.attr.colorOnErrorContainer
    )
    private val confirmIcon: Drawable? = loadTintedIcon(
        context,
        R.drawable.ic_check,
        com.google.android.material.R.attr.colorOnPrimaryContainer
    )

    // Allocated once and reused. Creating a Paint inside onChildDraw would allocate on every frame
    // of every swipe, which is exactly the kind of churn that causes stutter.
    private val backgroundPaint = Paint()

    private val iconMargin = context.resources.getDimensionPixelSize(
        R.dimen.checklist_swipe_icon_margin
    )

    /**
     * Decides which gestures a row allows.
     *
     * Returning zero disables swiping for that row entirely, which is how list headings and
     * already-actioned rows are protected.
     */
    override fun getMovementFlags(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder
    ): Int {
        val swipeFlags = allowedDirections(viewHolder.bindingAdapterPosition)

        // The first argument is drag flags: zero, because no list here is reorderable.
        return makeMovementFlags(0, swipeFlags)
    }

    /** Rows cannot be dragged into a new order, so this never happens. */
    override fun onMove(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder
    ): Boolean = false

    /** A swipe completed; report it to the screen, which owns what happens next. */
    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
        val position = viewHolder.bindingAdapterPosition
        if (position == RecyclerView.NO_POSITION) return

        onSwipe(position, direction)
    }

    /**
     * Draws the coloured background and icon revealed behind a row as it slides.
     *
     * Without this the row would slide away over blank space, giving no clue what the gesture is
     * about to do — and no chance to change your mind by sliding back.
     *
     * [dX] is how far the row has been dragged: positive rightwards, negative leftwards. The
     * background is drawn only across the gap the row has actually vacated, so the colour appears
     * to be revealed from underneath rather than flashing in fully formed.
     */
    override fun onChildDraw(
        canvas: Canvas,
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        dX: Float,
        dY: Float,
        actionState: Int,
        isCurrentlyActive: Boolean
    ) {
        if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE) {
            val itemView = viewHolder.itemView

            when {
                // Dragged right: the affirmative action.
                dX > 0 -> {
                    backgroundPaint.color = confirmBackgroundColor
                    canvas.drawRect(
                        itemView.left.toFloat(),
                        itemView.top.toFloat(),
                        itemView.left + dX,
                        itemView.bottom.toFloat(),
                        backgroundPaint
                    )
                    drawIcon(canvas, confirmIcon, itemView, alignToStart = true)
                }

                // Dragged left: the destructive action.
                dX < 0 -> {
                    backgroundPaint.color = deleteBackgroundColor
                    canvas.drawRect(
                        itemView.right + dX,
                        itemView.top.toFloat(),
                        itemView.right.toFloat(),
                        itemView.bottom.toFloat(),
                        backgroundPaint
                    )
                    drawIcon(canvas, deleteIcon, itemView, alignToStart = false)
                }
            }
        }

        // Let the default implementation translate the row itself, on top of what was just drawn.
        super.onChildDraw(
            canvas,
            recyclerView,
            viewHolder,
            dX,
            dY,
            actionState,
            isCurrentlyActive
        )
    }

    /** Draws [icon] vertically centred against one edge of the row. */
    private fun drawIcon(
        canvas: Canvas,
        icon: Drawable?,
        itemView: View,
        alignToStart: Boolean
    ) {
        if (icon == null) return

        val iconTop = itemView.top + (itemView.height - icon.intrinsicHeight) / 2
        val iconBottom = iconTop + icon.intrinsicHeight

        val iconLeft: Int
        val iconRight: Int

        if (alignToStart) {
            iconLeft = itemView.left + iconMargin
            iconRight = iconLeft + icon.intrinsicWidth
        } else {
            iconRight = itemView.right - iconMargin
            iconLeft = iconRight - icon.intrinsicWidth
        }

        icon.setBounds(iconLeft, iconTop, iconRight, iconBottom)
        icon.draw(canvas)
    }

    /** Loads a drawable and tints it with a colour taken from the theme. */
    private fun loadTintedIcon(
        context: Context,
        drawableRes: Int,
        colorAttr: Int
    ): Drawable? {
        val drawable = ContextCompat.getDrawable(context, drawableRes) ?: return null

        // `mutate` gives this instance its own state before tinting. Drawables loaded from the same
        // resource share one constant state by default, so tinting without it would recolour every
        // other use of the same icon in the app.
        val tintable = DrawableCompat.wrap(drawable.mutate())
        DrawableCompat.setTint(
            tintable,
            MaterialColors.getColor(context, colorAttr, context.getColor(android.R.color.white))
        )
        return tintable
    }
}
