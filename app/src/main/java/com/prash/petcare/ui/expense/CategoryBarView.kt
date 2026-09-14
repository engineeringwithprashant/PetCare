package com.prash.petcare.ui.expense

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat
import com.prash.petcare.R

/**
 * The horizontal stacked bar on the Money tab's total card: one rounded segment per spending
 * category, each as long as that category's share of the month and drawn in its colour.
 *
 * **Why a custom View.** A LinearProgressIndicator draws one proportion; this needs several, side by
 * side, each a different colour, with a small gap between them. The existing
 * [com.prash.petcare.ui.common.SegmentedProgressView] draws *equal* segments (one per task), which is
 * a different chart. Building this from a View per category inside a LinearLayout with weights would
 * work, but would add and remove child views every time the month's categories changed; drawing a
 * few rounded rectangles in [onDraw] costs nothing to update and has no hierarchy to measure.
 *
 * **Tiny shares stay visible.** A category worth 0.4% of the month would otherwise be a sliver
 * narrower than a pixel. Every segment is given at least [minSegmentWidth] — about a dot — and the
 * width that borrows is taken from the larger segments in proportion to their size, so the bar still
 * fills exactly.
 *
 * **Accessibility.** The colours carry no words, so the view is hidden from TalkBack: the legend rows
 * directly beneath it state every category, percentage and amount in text.
 */
class CategoryBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** One slice of the bar: [share] of the whole, from 0 to 1, drawn in [color]. */
    data class Segment(val share: Float, @param:ColorInt val color: Int)

    private var segments: List<Segment> = emptyList()

    private val gap = dp(3f)
    private val minSegmentWidth = dp(8f)

    /** Painted under the segments, and alone when the month has no spending yet. */
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.total_card_track)
    }

    /** One paint reused for every segment, recoloured before each draw — see the note in onDraw. */
    private val segmentPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val rect = RectF()

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** Replaces the bar's segments, in the order they should be drawn from the start edge. */
    fun setSegments(newSegments: List<Segment>) {
        segments = newSegments.filter { it.share > 0f }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val left = paddingLeft.toFloat()
        val top = paddingTop.toFloat()
        val right = (width - paddingRight).toFloat()
        val bottom = (height - paddingBottom).toFloat()
        // Fully rounded ends: a radius of half the height turns every segment into a capsule.
        val radius = (bottom - top) / 2

        if (segments.isEmpty()) {
            rect.set(left, top, right, bottom)
            canvas.drawRoundRect(rect, radius, radius, trackPaint)
            return
        }

        val widths = segmentWidths(right - left - gap * (segments.size - 1))

        // Drawn from the start edge, so in a right-to-left language the largest category still sits
        // where reading begins.
        val isRtl = layoutDirection == LAYOUT_DIRECTION_RTL
        var cursor = if (isRtl) right else left

        segments.forEachIndexed { index, segment ->
            val w = widths[index]
            if (isRtl) rect.set(cursor - w, top, cursor, bottom) else rect.set(cursor, top, cursor + w, bottom)

            // Recolouring one Paint rather than keeping a Paint per segment: onDraw runs on every
            // frame of any animation on screen, and allocating inside it causes stutter.
            segmentPaint.color = segment.color
            canvas.drawRoundRect(rect, radius, radius, segmentPaint)

            cursor = if (isRtl) cursor - w - gap else cursor + w + gap
        }
    }

    /**
     * Shares each segment's width out of [available] pixels, guaranteeing every segment at least
     * [minSegmentWidth] and still adding up to exactly [available].
     *
     * Segments below the minimum are fixed at it; the rest divide what remains in proportion to their
     * shares. If there are so many categories that even the minimum does not fit, every segment
     * simply gets an equal slice.
     */
    private fun segmentWidths(available: Float): FloatArray {
        val count = segments.size
        if (minSegmentWidth * count >= available) return FloatArray(count) { available / count }

        val totalShare = segments.sumOf { it.share.toDouble() }.toFloat()
        val small = BooleanArray(count) { segments[it].share / totalShare * available < minSegmentWidth }
        val largeShare = segments.indices.filterNot { small[it] }.sumOf { segments[it].share.toDouble() }.toFloat()
        val remaining = available - minSegmentWidth * small.count { it }

        return FloatArray(count) { i ->
            if (small[i]) minSegmentWidth else segments[i].share / largeShare * remaining
        }
    }

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)
}
