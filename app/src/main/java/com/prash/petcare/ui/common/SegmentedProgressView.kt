package com.prash.petcare.ui.common

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.google.android.material.color.MaterialColors
import com.prash.petcare.R

/** A progress bar split into one rounded segment per task: yellow for done, pale for not yet. */
class SegmentedProgressView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** One flag per segment, in display order; true draws that segment as done. */
    private var segments: List<Boolean> = emptyList()

    // Allocated once and reused on every frame. Creating paints or rectangles inside onDraw would allocate on each redraw.
    private val donePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.accent_yellow)
    }
    private val pendingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MaterialColors.getColor(
            this@SegmentedProgressView,
            com.google.android.material.R.attr.colorSurfaceVariant
        )
    }
    private val segmentRect = RectF()

    private val gap = resources.getDimension(R.dimen.segmented_progress_gap)
    private val barHeight = resources.getDimensionPixelSize(R.dimen.segmented_progress_height)

    /** Replaces the segments and redraws. */
    fun setSegments(newSegments: List<Boolean>) {
        if (newSegments == segments) return
        segments = newSegments.toList()
        invalidate()
    }

    /** Takes all the width offered and a fixed height. */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = resolveSize(barHeight + paddingTop + paddingBottom, heightMeasureSpec)
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (segments.isEmpty()) return

        val left = paddingLeft.toFloat()
        val right = (width - paddingRight).toFloat()
        val top = paddingTop.toFloat()
        val bottom = (height - paddingBottom).toFloat()

        // Every segment is the same width: the space left after the gaps, shared equally.
        val totalGap = gap * (segments.size - 1)
        val segmentWidth = ((right - left) - totalGap) / segments.size
        if (segmentWidth <= 0f) return

        // Fully rounded ends, whatever the bar's thickness.
        val radius = (bottom - top) / 2f

        // In a right-to-left language the first task's segment belongs on the right.
        val isRtl = layoutDirection == LAYOUT_DIRECTION_RTL

        segments.forEachIndexed { index, isDone ->
            val offset = index * (segmentWidth + gap)
            val start = if (isRtl) right - offset - segmentWidth else left + offset

            segmentRect.set(start, top, start + segmentWidth, bottom)
            canvas.drawRoundRect(segmentRect, radius, radius, if (isDone) donePaint else pendingPaint)
        }
    }
}
