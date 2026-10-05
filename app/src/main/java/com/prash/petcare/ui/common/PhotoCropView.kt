package com.prash.petcare.ui.common

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A fixed-shape window onto a photograph that the user can slide and pinch to frame. */
class PhotoCropView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** The shape of the window, as width ÷ height. */
    var aspectRatio: Float = PET_PHOTO_ASPECT
        set(value) {
            field = value
            requestLayout()
        }

    /** The photograph being framed, at whatever size it was decoded. */
    private var bitmap: Bitmap? = null

    /** Maps bitmap pixels onto view pixels: the user's framing, expressed as a transform. */
    private val imageMatrix = Matrix()

    /** Scratch objects, reused so that no allocation happens during a drag or inside onDraw. */
    private val mappedBounds = RectF()
    private val matrixValues = FloatArray(9)

    /** Bilinear filtering, so a photo scaled up while zooming does not look blocky. */
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    /** Where the last finger was, for a one-finger drag. */
    private var lastTouchX = 0f
    private var lastTouchY = 0f

    /** Pinch-to-zoom. */
    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val factor = clampScale(detector.scaleFactor)
                imageMatrix.postScale(factor, factor, detector.focusX, detector.focusY)
                clampTranslation()
                invalidate()
                return true
            }
        }
    )

    /** Sizes the window to [aspectRatio]. */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(width, (width / aspectRatio).roundToInt())
    }

    /** Re-frames the photo whenever the window's size changes. */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        resetFraming()
    }

    /** Puts a photograph in the window, framed to cover it. */
    fun setBitmap(bitmap: Bitmap?) {
        this.bitmap = bitmap
        resetFraming()
        invalidate()
    }

    /** Frames the photo as `centerCrop` would: scaled so the smaller overflow just covers the window, and centred. */
    private fun resetFraming() {
        val photo = bitmap ?: return
        if (width == 0 || height == 0 || photo.width == 0 || photo.height == 0) return

        // max, not min: min would fit the photo inside the window and leave bars at two edges.
        val scale = max(width.toFloat() / photo.width, height.toFloat() / photo.height)

        imageMatrix.reset()
        imageMatrix.postScale(scale, scale)
        imageMatrix.postTranslate(
            (width - photo.width * scale) / 2f,
            (height - photo.height * scale) / 2f
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        bitmap?.let { canvas.drawBitmap(it, imageMatrix, paint) }
    }

    /** Turns touches into framing. */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (bitmap == null) return false

        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                lastTouchX = event.x
                lastTouchY = event.y
            }

            MotionEvent.ACTION_MOVE -> if (!scaleDetector.isInProgress) {
                imageMatrix.postTranslate(event.x - lastTouchX, event.y - lastTouchY)
                clampTranslation()
                lastTouchX = event.x
                lastTouchY = event.y
                invalidate()
            }

            // A finger lifting during a pinch leaves the other one somewhere new.
            MotionEvent.ACTION_POINTER_UP -> {
                lastTouchX = event.x
                lastTouchY = event.y
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                parent?.requestDisallowInterceptTouchEvent(false)
        }

        // Always true: this view handles its own gestures and must keep receiving the stream after the initial DOWN.
        return true
    }

    /** Limits a pinch so the photo can neither shrink below covering the window nor be magnified past [MAX_ZOOM]. */
    private fun clampScale(factor: Float): Float {
        val photo = bitmap ?: return factor

        val current = currentScale()
        val minimum = max(width.toFloat() / photo.width, height.toFloat() / photo.height)
        val maximum = minimum * MAX_ZOOM

        // Where the factor would land, pulled back inside the range, then expressed as a factor again.
        return (current * factor).coerceIn(minimum, maximum) / current
    }

    /** The scale currently in the matrix, read from its horizontal term — the two are always equal. */
    private fun currentScale(): Float {
        imageMatrix.getValues(matrixValues)
        return matrixValues[Matrix.MSCALE_X]
    }

    /** Slides the photo back if a gesture has pulled an edge inside the window. */
    private fun clampTranslation() {
        val photo = bitmap ?: return

        mappedBounds.set(0f, 0f, photo.width.toFloat(), photo.height.toFloat())
        imageMatrix.mapRect(mappedBounds)

        imageMatrix.postTranslate(
            axisCorrection(mappedBounds.left, mappedBounds.right, width),
            axisCorrection(mappedBounds.top, mappedBounds.bottom, height)
        )
    }

    /** How far one axis must move for [start]..[end] to cover `0..extent`. */
    private fun axisCorrection(start: Float, end: Float, extent: Int): Float = when {
        // Smaller than the window: centre it.
        end - start < extent -> (extent - (end - start)) / 2f - start
        // A gap at the leading edge: pull back by exactly that gap.
        start > 0f -> -start
        // A gap at the trailing edge.
        end < extent -> extent - end
        else -> 0f
    }

    /** Renders what the window currently shows as a new bitmap. */
    fun croppedBitmap(targetWidthPx: Int): Bitmap? {
        val photo = bitmap ?: return null
        if (width == 0 || height == 0) return null

        val outputWidth = min(targetWidthPx, MAX_OUTPUT_WIDTH_PX)
        val outputHeight = (outputWidth / aspectRatio).roundToInt().coerceAtLeast(1)

        val output = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
        val exportMatrix = Matrix(imageMatrix).apply {
            // View pixels to output pixels. Both axes use the same factor.
            postScale(outputWidth.toFloat() / width, outputHeight.toFloat() / height)
        }

        Canvas(output).drawBitmap(photo, exportMatrix, paint)
        return output
    }

    companion object {

        /** The shape every pet photo is stored and displayed in: 4:3 landscape. */
        const val PET_PHOTO_ASPECT = 4f / 3f

        /** How far in the user may pinch, as a multiple of the "just covers the window" scale. */
        private const val MAX_ZOOM = 5f

        /** Ceiling on the stored image's width. Matches PetPhotoStorage's own limit. */
        private const val MAX_OUTPUT_WIDTH_PX = 1024
    }
}
