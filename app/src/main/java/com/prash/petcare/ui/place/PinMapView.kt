package com.prash.petcare.ui.place

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import com.google.android.material.color.MaterialColors
import com.prash.petcare.R
import com.prash.petcare.util.PinMapProjection

/**
 * The map on the "Drop pin" method of the Add a place screen: a ruled ground with a "you are here"
 * dot, and a marker the user taps or drags to say where a place is.
 *
 * Part of the **View** layer, and a deliberately ignorant one. It knows about percentages and pixels
 * and nothing about degrees: it reports "the marker is 62% across and 37% down" through [onPinMoved]
 * and is told where to draw it through [setPin]. Turning those percentages into a latitude and a
 * longitude is [PinMapProjection]'s job, called from the ViewModel — which is what keeps the app's
 * geography in one testable place and this class to drawing and touch.
 *
 * **What it draws, and what its children draw.** The ground, the 30dp grid and the blue dot are
 * painted in [onDraw], because a grid has to be ruled to whatever size the view ends up and a
 * shape drawable cannot repeat itself. The marker and the hint pill are ordinary child views declared
 * in the layout: the marker is a label, a stem and a dot stacked vertically, each of which is a
 * styled box the app already knows how to describe, and drawing rounded pills with text by hand
 * would be a great deal of code for a worse result.
 *
 * **Why a FrameLayout.** It is the one container whose single attribute means "children occupy the
 * same space", which is exactly the arrangement here — a marker and a hint laid over one ground.
 * `setWillNotDraw(false)` is needed because a ViewGroup skips its own [onDraw] by default.
 *
 * **In production**, replacing this with a real map library changes this class and nothing above it:
 * the library's own view reports the point it was touched at, that point goes through the library's
 * `unproject`/`latLngFromPoint` instead of [PinMapProjection], and the ViewModel still receives a
 * position it did not get from the device.
 */
class PinMapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    /**
     * Called as the marker moves, with its position as percentages of the view's width and height.
     *
     * Fired continuously during a drag rather than once at the end, so the coordinate readout under
     * the map keeps up with the finger — that live figure is how the user aims.
     */
    var onPinMoved: ((xPercent: Float, yPercent: Float) -> Unit)? = null

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.getDimension(R.dimen.pin_map_grid_hairline)
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val gridStep = resources.getDimension(R.dimen.pin_map_grid_step)
    private val hereRadius = resources.getDimension(R.dimen.pin_map_here_dot_size) / 2f
    private val hereRingWidth = resources.getDimension(R.dimen.pin_map_here_ring_width)
    private val hereHaloWidth = resources.getDimension(R.dimen.pin_map_here_halo_width)

    /** The marker's position. Percentages, so a rotation or a width change keeps it where it was. */
    private var pinXPercent = PinMapProjection.HOME_X_PERCENT
    private var pinYPercent = PinMapProjection.HOME_Y_PERCENT

    /** True between a press on the map and the finger leaving it. See [onTouchEvent]. */
    private var isDragging = false

    /**
     * The marker column — the label, stem and dot declared as children of this view in the layout.
     *
     * Found in [onFinishInflate] and held, because it is moved on every touch event during a drag and
     * a search of the hierarchy per frame is work for nothing.
     */
    private var marker: View? = null

    init {
        // A ViewGroup does not call its own onDraw unless asked to; the ground, grid and blue dot are
        // painted there rather than by child views.
        setWillNotDraw(false)
        // The rounded background is what clips the grid and the marker to the map's corners — the
        // design's `overflow: hidden`.
        background = ContextCompat.getDrawable(context, R.drawable.bg_pin_map)
        clipToOutline = true
    }

    /** Runs once the children declared inside this tag have been inflated. */
    override fun onFinishInflate() {
        super.onFinishInflate()
        marker = findViewById(R.id.pinMarker)
    }

    /**
     * Places the marker, as percentages across and down the map.
     *
     * Called by the fragment on every state emission, including the ones this view's own touch
     * handling caused. That round trip is deliberate: the ViewModel clamps the value (see
     * [PinMapProjection]), so what comes back may not be what went out, and the marker must end up
     * where the *model* says it is rather than where the finger was.
     */
    fun setPin(xPercent: Float, yPercent: Float) {
        if (pinXPercent == xPercent && pinYPercent == yPercent) return
        pinXPercent = xPercent
        pinYPercent = yPercent
        positionMarker()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        // After the children have been measured and laid out, so the marker's own size is known —
        // it is needed to offset the marker onto its point.
        positionMarker()
    }

    /**
     * Moves the marker so that the *tip of its dot* sits on the point, not its top-left corner.
     *
     * That is the design's `transform: translate(-50%, -100%)`: half the marker's width to the left
     * and its whole height upwards, which puts the bottom-centre of the stack — the dot — exactly on
     * the coordinate being marked. Getting this wrong by the marker's height is the difference
     * between a pin that points at a spot and one that hovers over it.
     */
    private fun positionMarker() {
        val view = marker ?: return
        if (width == 0 || height == 0) return

        view.translationX = width * pinXPercent / 100f - view.width / 2f
        view.translationY = height * pinYPercent / 100f - view.height
    }

    /**
     * Tap to place, drag to move.
     *
     * A press anywhere on the map moves the marker there and begins a drag, rather than requiring the
     * marker itself to be grabbed: the marker is a few millimetres wide and the thing the user is
     * aiming at is the ground under it. `requestDisallowInterceptTouchEvent` tells the scrolling page
     * above not to steal the gesture — without it, any vertical movement would be read as a scroll
     * and the marker would jump once and then stop following.
     *
     * The view is not clickable in the ordinary sense, so [performClick] is not called; the map is a
     * positioning surface rather than a button, and its accessible equivalent is the other two
     * methods, either of which sets a position without a gesture at all.
     */
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isDragging = true
                parent?.requestDisallowInterceptTouchEvent(true)
                reportPin(event.x, event.y)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!isDragging) return false
                reportPin(event.x, event.y)
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }

        return super.onTouchEvent(event)
    }

    /**
     * Turns a touch in pixels into the percentages the ViewModel works in.
     *
     * Not clamped here: the model owns the valid range, and clamping in two places is how the two
     * come to disagree. A finger dragged off the edge reports a value outside 0–100, the ViewModel
     * pulls it back inside, and [setPin] draws the corrected position.
     */
    private fun reportPin(x: Float, y: Float) {
        if (width == 0 || height == 0) return
        onPinMoved?.invoke(x / width * 100f, y / height * 100f)
    }

    /**
     * Rules the grid and paints the "you are here" dot.
     *
     * The ground itself is the background drawable, already painted by the time this runs. Colours
     * are resolved on every draw rather than cached in the constructor, so a theme change repaints
     * correctly — the grid is a theme role (see attrs.xml) and the dot is not.
     */
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        drawGrid(canvas)
        drawHereDot(canvas)
    }

    /** Two sets of hairlines a fixed distance apart, so the squares stay square at any width. */
    private fun drawGrid(canvas: Canvas) {
        gridPaint.color = MaterialColors.getColor(this, R.attr.colorPinMapGrid)

        var x = gridStep
        while (x < width) {
            canvas.drawLine(x, 0f, x, height.toFloat(), gridPaint)
            x += gridStep
        }

        var y = gridStep
        while (y < height) {
            canvas.drawLine(0f, y, width.toFloat(), y, gridPaint)
            y += gridStep
        }
    }

    /**
     * The blue dot at the map's home point: a soft halo, a pale ring, then the blue disc.
     *
     * Drawn outside in, so each layer covers the one before it and the ring reads as a clean edge
     * rather than as a blend of two translucent circles.
     *
     * It marks where the map is *centred*, which is also where the distance under the map is measured
     * from. It is not a live GPS position and nothing on this method reads one — see the note on
     * [AddPlaceMethod].
     */
    private fun drawHereDot(canvas: Canvas) {
        val centreX = width * PinMapProjection.HOME_X_PERCENT / 100f
        val centreY = height * PinMapProjection.HOME_Y_PERCENT / 100f

        dotPaint.color = context.getColor(R.color.here_dot_halo)
        canvas.drawCircle(centreX, centreY, hereRadius + hereRingWidth + hereHaloWidth, dotPaint)

        dotPaint.color = context.getColor(R.color.here_dot_ring)
        canvas.drawCircle(centreX, centreY, hereRadius + hereRingWidth, dotPaint)

        dotPaint.color = context.getColor(R.color.here_dot)
        canvas.drawCircle(centreX, centreY, hereRadius, dotPaint)
    }
}
