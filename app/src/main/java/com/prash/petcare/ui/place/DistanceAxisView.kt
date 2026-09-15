package com.prash.petcare.ui.place

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.prash.petcare.R

/**
 * The horizontal "home → furthest place" axis inside the Places tab's dark distance strip.
 *
 * From left to right it draws a blue location dot labelled "home", a line with a labelled tick at
 * each step, and above the line one small category chip per saved place, each joined to its point
 * on the line by a thin stem. The featured place's chip is filled with the accent.
 *
 * **Why a custom View.** Every other part of the app is built from standard widgets, and that is the
 * right default. This one is a small chart: the chips' horizontal positions are *proportions of the
 * view's own width*, known only after layout, and they have to dodge one another. Expressed as
 * child views, that would mean measuring text, computing offsets and setting translations on a
 * dozen TextViews after every layout pass — which is custom drawing done the long way round. Drawing
 * directly onto the [Canvas] states it plainly.
 *
 * **Deliberately dumb.** It is handed ready-made [Tick]s and [Chip]s — already translated labels, and
 * positions already expressed as fractions of the axis by PlacesViewModel. It knows nothing about
 * kilometres, places or categories, so none of that logic is buried in drawing code.
 *
 * **Accessibility.** A drawing has no text a screen reader can find, so the fragment sets a
 * `contentDescription` listing every place and its distance, and TalkBack reads that instead.
 */
class DistanceAxisView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** A labelled tick. [fraction] runs from 0 (the home dot) to 1 (the end of the axis). */
    data class Tick(val label: String, val fraction: Float)

    /** A place's chip. [fraction] as for [Tick]; [isHighlighted] fills it with the accent. */
    data class Chip(val label: String, val fraction: Float, val isHighlighted: Boolean)

    private var ticks: List<Tick> = emptyList()
    private var chips: List<Chip> = emptyList()
    private var originLabel: String = ""

    /** Replaces what the axis shows and redraws it. */
    fun setContent(originLabel: String, ticks: List<Tick>, chips: List<Chip>) {
        this.originLabel = originLabel
        this.ticks = ticks
        this.chips = chips
        invalidate()
    }

    // ---------------------------------------------------------------------------------------
    // Measurements, in pixels. Declared in dp/sp and converted once, so the axis is the same
    // physical size on every screen density and its labels follow the user's font-size setting.
    // ---------------------------------------------------------------------------------------

    private val chipHeight = dp(22f)
    private val chipPaddingHorizontal = dp(8f)
    private val chipCorner = dp(6f)
    /** Vertical gap between the two rows ("lanes") chips are staggered into. */
    private val laneGap = dp(6f)
    /** Minimum horizontal gap two chips in the same lane must keep. */
    private val chipGap = dp(4f)
    private val tickHeight = dp(5f)
    private val dotRadius = dp(5f)
    private val dotHaloRadius = dp(10f)
    private val labelGap = dp(8f)

    // ---------------------------------------------------------------------------------------
    // Paints. Created once rather than in onDraw, which runs every frame of any animation on
    // screen: allocating there churns the garbage collector and shows up as dropped frames.
    //
    // The colours are fixed rather than theme roles because the strip is dark in both themes — see
    // the Places strip section of colors.xml.
    // ---------------------------------------------------------------------------------------

    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.places_strip_axis)
        strokeWidth = dp(1f)
    }

    private val stemPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.places_strip_axis)
        strokeWidth = dp(1f)
    }

    private val highlightedStemPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.accent_yellow)
        strokeWidth = dp(1f)
    }

    private val tickLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.places_strip_muted)
        textSize = sp(10f)
        // Monospace so "0.5 km" and "1.5 km" are the same width and the labels sit evenly under
        // their ticks — the strip's typographic voice throughout, see PetCare.Places.StripLabel.
        // A Paint cannot read a font resource from XML the way a TextView can, so the bundled
        // IBM Plex Mono file is loaded explicitly; the platform monospace is the fallback if the
        // resource ever fails to load.
        typeface = ResourcesCompat.getFont(context, R.font.ibm_plex_mono_medium) ?: Typeface.MONOSPACE
    }

    private val chipFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.places_axis_chip)
    }

    private val highlightedChipFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.accent_yellow)
    }

    private val chipTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.on_accent_yellow)
        textSize = sp(10f)
        // The SemiBold *file* rather than Typeface.create(…, BOLD): asking for bold on a single
        // loaded font can make older Android versions thicken the strokes artificially, whereas the
        // real SemiBold cut is drawn as the type designer intended.
        typeface = ResourcesCompat.getFont(context, R.font.ibm_plex_mono_semibold)
            ?: Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        letterSpacing = 0.06f
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.places_home_dot)
    }

    private val dotHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.places_home_dot_halo)
    }

    private val dotRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.on_pet_card)
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
    }

    /** Reused for every chip's rectangle, for the same no-allocation-in-onDraw reason as the paints. */
    private val chipRect = RectF()

    /**
     * Asks for enough height for two lanes of chips, their stems, the axis and a row of labels, when
     * the layout leaves the height to the view (`wrap_content`). Width is always whatever the parent
     * gives — the axis stretches to fill it.
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredHeight = (chipHeight * 2 + laneGap + dp(14f) + tickHeight + labelGap +
            tickLabelPaint.textSize + paddingTop + paddingBottom).toInt()

        setMeasuredDimension(
            getDefaultSize(suggestedMinimumWidth, widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // The axis line runs from the centre of the home dot to the right edge. The dot's halo is
        // what sets the inset on the left, so it is never clipped by the view's edge.
        val startX = paddingLeft + dotHaloRadius
        val endX = width - paddingRight - dp(2f)
        val axisLength = endX - startX

        // Measured up from the bottom: labels at the foot, then the gap, then the line.
        val labelBaseline = height - paddingBottom - tickLabelPaint.descent()
        val axisY = labelBaseline - tickLabelPaint.textSize - labelGap

        canvas.drawLine(startX, axisY, endX, axisY, axisPaint)

        drawTicks(canvas, startX, axisLength, axisY, labelBaseline)
        drawChips(canvas, startX, axisLength, axisY)
        drawHomeDot(canvas, startX, axisY, labelBaseline)
    }

    /**
     * Draws each tick and its label.
     *
     * The label under the last tick is right-aligned to it, not centred, so "2 km" at the very end of
     * the axis stays inside the view instead of losing its second half off the edge.
     */
    private fun drawTicks(canvas: Canvas, startX: Float, axisLength: Float, axisY: Float, baseline: Float) {
        ticks.forEachIndexed { index, tick ->
            val x = startX + axisLength * tick.fraction
            canvas.drawLine(x, axisY - tickHeight / 2, x, axisY + tickHeight / 2, axisPaint)

            val textWidth = tickLabelPaint.measureText(tick.label)
            val textX = if (index == ticks.lastIndex) x - textWidth else x - textWidth / 2
            canvas.drawText(tick.label, textX, baseline, tickLabelPaint)
        }
    }

    /**
     * Draws the chips, staggered into two lanes so neighbours do not overlap.
     *
     * **The lane rule.** Chips arrive sorted by distance and alternate top, bottom, top… — the
     * rhythm in the design, and usually enough on its own, since the nearest neighbour along the axis
     * is always in the other lane. When the lane a chip would take is already occupied at that point
     * (two places very close together), it takes the other lane if that one is free. If both are
     * taken it overlaps in its natural lane; with a dozen places inside a hundred metres something
     * has to give, and the list below the strip still names them all.
     *
     * The chip is centred over its point but slid back inside the view at either end, while the stem
     * still drops to the true position — the stem is the precise mark, the chip only its label.
     */
    private fun drawChips(canvas: Canvas, startX: Float, axisLength: Float, axisY: Float) {
        // The rightmost edge drawn so far in each lane, for the overlap test.
        val laneRightEdges = floatArrayOf(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY)
        val laneTops = floatArrayOf(paddingTop.toFloat(), paddingTop + chipHeight + laneGap)

        // The highlighted chip is drawn last so it sits on top of any neighbour it overlaps.
        val drawOrder = chips.withIndex().sortedBy { it.value.isHighlighted }

        // Lanes are assigned in distance order first, so the highlight never disturbs the layout.
        val laneOf = IntArray(chips.size)
        val leftOf = FloatArray(chips.size)
        chips.forEachIndexed { index, chip ->
            val chipWidth = chipTextPaint.measureText(chip.label) + chipPaddingHorizontal * 2
            val pointX = startX + axisLength * chip.fraction
            val left = (pointX - chipWidth / 2).coerceIn(0f, (width - chipWidth).coerceAtLeast(0f))

            val preferred = index % 2
            val other = 1 - preferred
            val lane = when {
                left >= laneRightEdges[preferred] + chipGap -> preferred
                left >= laneRightEdges[other] + chipGap -> other
                else -> preferred
            }

            laneOf[index] = lane
            leftOf[index] = left
            laneRightEdges[lane] = maxOf(laneRightEdges[lane], left + chipWidth)
        }

        drawOrder.forEach { (index, chip) ->
            val chipWidth = chipTextPaint.measureText(chip.label) + chipPaddingHorizontal * 2
            val pointX = startX + axisLength * chip.fraction
            val top = laneTops[laneOf[index]]

            // The stem first, so the chip is painted over its top end.
            canvas.drawLine(
                pointX, top + chipHeight, pointX, axisY,
                if (chip.isHighlighted) highlightedStemPaint else stemPaint
            )

            chipRect.set(leftOf[index], top, leftOf[index] + chipWidth, top + chipHeight)
            canvas.drawRoundRect(
                chipRect, chipCorner, chipCorner,
                if (chip.isHighlighted) highlightedChipFillPaint else chipFillPaint
            )

            // Vertically centring text means centring its ascent-to-descent box, not its baseline.
            val textY = chipRect.centerY() - (chipTextPaint.ascent() + chipTextPaint.descent()) / 2
            canvas.drawText(chip.label, chipRect.left + chipPaddingHorizontal, textY, chipTextPaint)
        }
    }

    /**
     * Draws the "home" origin: a blue dot with a white ring inside a translucent halo — the
     * conventional "you are here" mark users already recognise from every maps app — with its label
     * left-aligned beneath it.
     */
    private fun drawHomeDot(canvas: Canvas, x: Float, axisY: Float, baseline: Float) {
        canvas.drawCircle(x, axisY, dotHaloRadius, dotHaloPaint)
        canvas.drawCircle(x, axisY, dotRadius, dotPaint)
        canvas.drawCircle(x, axisY, dotRadius, dotRingPaint)
        canvas.drawText(originLabel, paddingLeft.toFloat(), baseline, tickLabelPaint)
    }

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

    private fun sp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)
}
