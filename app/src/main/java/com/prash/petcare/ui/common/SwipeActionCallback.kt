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

/** Swipe gestures for a list, shared by every screen that uses them. */
class SwipeActionCallback(
    context: Context,
    private val allowedDirections: (position: Int) -> Int,
    private val onSwipe: (position: Int, direction: Int) -> Unit
) : ItemTouchHelper.Callback() {

    // Colours come from the Material 3 theme rather than being hard-coded.
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

    // Allocated once and reused. Creating a Paint inside onChildDraw would allocate on every frame of every swipe.
    private val backgroundPaint = Paint()

    private val iconMargin = context.resources.getDimensionPixelSize(
        R.dimen.checklist_swipe_icon_margin
    )

    /** Decides which gestures a row allows. */
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

    /** Draws the coloured background and icon revealed behind a row as it slides. */
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

        // `mutate` gives this instance its own state before tinting.
        val tintable = DrawableCompat.wrap(drawable.mutate())
        DrawableCompat.setTint(
            tintable,
            MaterialColors.getColor(context, colorAttr, context.getColor(android.R.color.white))
        )
        return tintable
    }
}
