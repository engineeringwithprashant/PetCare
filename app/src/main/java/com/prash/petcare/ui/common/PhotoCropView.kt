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

/**
 * A fixed-shape window onto a photograph that the user can slide and pinch to frame.
 *
 * **The problem it solves.** A pet card is a fixed shape and a photograph almost never shares it, so
 * something has to be trimmed. Left to `centerCrop` the decision is made by arithmetic — keep the
 * middle — and the middle of a photo of a dog is very often its ribcage. This view hands that
 * decision to the person who knows which part matters, and [croppedBitmap] then bakes their choice
 * into the image that is actually stored, so every card in the app shows what they framed rather
 * than guessing again from scratch.
 *
 * **Why a custom View rather than a library or an ImageView.** `ImageView.ScaleType.MATRIX` would
 * give somewhere to put the transform but nothing that produces one from touches, and the gesture
 * handling, the clamping and the export are the whole feature. Written out here it is also readable:
 * roughly a hundred lines of Matrix arithmetic, against a dependency whose behaviour would have to
 * be taken on trust.
 *
 * **The one rule everything else serves:** the photo must always cover the window completely. There
 * must never be a gap at an edge, because a gap would be stored as blank pixels in the saved image.
 * Both [clampScale] and [clampTranslation] exist to enforce it, and they are applied after every
 * gesture rather than trusting the gesture to behave.
 *
 * Part of the **View** layer. It holds no repository and knows nothing about pets — it frames a
 * bitmap and hands one back, which is what makes it reusable for any photo the app ever crops.
 */
class PhotoCropView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /**
     * The shape of the window, as width ÷ height.
     *
     * Defaults to the app's pet-photo shape so the crop the user sees is exactly the shape every pet
     * card draws — see `pet_photo_aspect_*` in dimens.xml, which the three card layouts use too.
     * Changing it here without changing them would break the promise the whole feature rests on.
     */
    var aspectRatio: Float = PET_PHOTO_ASPECT
        set(value) {
            field = value
            requestLayout()
        }

    /** The photograph being framed, at whatever size it was decoded. */
    private var bitmap: Bitmap? = null

    /**
     * Maps bitmap pixels onto view pixels: the user's framing, expressed as a transform.
     *
     * Keeping the framing *as a matrix* rather than as a crop rectangle is what makes panning and
     * zooming composable — each gesture simply post-multiplies its own change onto it, and the view
     * never has to reason about what the previous gestures did.
     */
    private val imageMatrix = Matrix()

    /** Scratch objects, reused so that no allocation happens during a drag or inside onDraw. */
    private val mappedBounds = RectF()
    private val matrixValues = FloatArray(9)

    /** Bilinear filtering, so a photo scaled up while zooming does not look blocky. */
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    /** Where the last finger was, for a one-finger drag. */
    private var lastTouchX = 0f
    private var lastTouchY = 0f

    /**
     * Pinch-to-zoom.
     *
     * The zoom is applied **around the pinch's focal point** rather than the view's centre, which is
     * what makes it feel like the photo is being stretched under the fingers instead of sliding away
     * from them. [clampScale] caps the change first, so a fast pinch cannot overshoot the limits and
     * then be yanked back.
     */
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

    /**
     * Sizes the window to [aspectRatio].
     *
     * The width is taken from the parent — the view is always laid out at `match_parent` — and the
     * height derived from it, so the window is the right shape on a narrow phone and a wide one
     * alike without any dimension being hard-coded.
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(width, (width / aspectRatio).roundToInt())
    }

    /**
     * Re-frames the photo whenever the window's size changes.
     *
     * Also covers the first time the view is measured, which is the only moment the initial framing
     * *can* be worked out: [setBitmap] may well be called before the view has a width, and a scale
     * computed against a width of zero would be meaningless.
     */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        resetFraming()
    }

    /**
     * Puts a photograph in the window, framed to cover it.
     *
     * Replacing the bitmap deliberately discards the previous framing: the new photo is a different
     * picture, and keeping the old pan and zoom would open it at some arbitrary corner.
     */
    fun setBitmap(bitmap: Bitmap?) {
        this.bitmap = bitmap
        resetFraming()
        invalidate()
    }

    /**
     * Frames the photo as `centerCrop` would: scaled so the *smaller* overflow just covers the
     * window, and centred.
     *
     * Starting where the old behaviour ended is deliberate. The user opens the cropper already
     * looking at what the card would have shown, so their work is only to correct it — and a photo
     * that was framed acceptably by accident needs no work at all.
     */
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

    /**
     * Turns touches into framing.
     *
     * The scale detector is offered every event first and is *not* allowed to consume them, because
     * it needs to keep watching for a second finger throughout. A drag is then applied only while a
     * single finger is down — moving the photo during a pinch as well would fight the focal-point
     * zoom and make it drift.
     *
     * `requestDisallowInterceptTouchEvent` is what lets this work inside a scrolling dialog: without
     * it the first vertical drag would be stolen by the scroll container and the photo would never
     * move.
     */
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

            // A finger lifting during a pinch leaves the other one somewhere new; re-recording the
            // position stops the photo jumping when the drag resumes from it.
            MotionEvent.ACTION_POINTER_UP -> {
                lastTouchX = event.x
                lastTouchY = event.y
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                parent?.requestDisallowInterceptTouchEvent(false)
        }

        // Always true: this view handles its own gestures and must keep receiving the stream after
        // the initial DOWN, which returning false would end.
        return true
    }

    /**
     * Limits a pinch so the photo can neither shrink below covering the window nor be magnified past
     * [MAX_ZOOM].
     *
     * It returns an adjusted *factor* rather than rejecting the gesture, so a pinch that runs into a
     * limit stops there smoothly instead of the photo freezing while the fingers keep moving.
     */
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

    /**
     * Slides the photo back if a gesture has pulled an edge inside the window.
     *
     * This is the guarantee the whole view rests on, and it is enforced here — once, after every
     * gesture — rather than by trying to predict each gesture's effect. For each axis: if the photo
     * overflows the window, its edges are pushed back to the boundary they crossed; if it somehow
     * does not, it is centred, which cannot happen while [clampScale] is doing its job but leaves no
     * undefined case if it ever failed to.
     */
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

    /**
     * Renders what the window currently shows as a new bitmap.
     *
     * The same [imageMatrix] the view draws with is reused, scaled up to the output size, so the
     * result is by construction the picture the user was looking at — there is no second, subtly
     * different crop calculation that could disagree with the preview. That is what makes the
     * feature trustworthy: what they framed is what is saved.
     *
     * @param targetWidthPx Width of the returned image. The height follows from [aspectRatio], so
     *   the output is always the shape the cards expect.
     * @return The cropped photo, or null when there is nothing framed.
     */
    fun croppedBitmap(targetWidthPx: Int): Bitmap? {
        val photo = bitmap ?: return null
        if (width == 0 || height == 0) return null

        val outputWidth = min(targetWidthPx, MAX_OUTPUT_WIDTH_PX)
        val outputHeight = (outputWidth / aspectRatio).roundToInt().coerceAtLeast(1)

        val output = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
        val exportMatrix = Matrix(imageMatrix).apply {
            // View pixels to output pixels. Both axes use the same factor because the output has the
            // view's aspect ratio, so scaling is uniform and the photo cannot be stretched.
            postScale(outputWidth.toFloat() / width, outputHeight.toFloat() / height)
        }

        Canvas(output).drawBitmap(photo, exportMatrix, paint)
        return output
    }

    companion object {

        /**
         * The shape every pet photo is stored and displayed in: 4:3 landscape.
         *
         * **Why one shape for the whole app.** The promise is that what the user frames is what the
         * cards show, and that can only hold if the cards all draw the same shape they were asked to
         * frame — a card with a different ratio would have to crop the crop, and the user's choice
         * would be second-guessed after all. 4:3 is chosen because it is close to what the Pets card
         * and the detail hero already were, so honouring it cost those two screens almost no change
         * in height. See the three card layouts, which all derive their photo frame from this.
         */
        const val PET_PHOTO_ASPECT = 4f / 3f

        /** How far in the user may pinch, as a multiple of the "just covers the window" scale. */
        private const val MAX_ZOOM = 5f

        /**
         * Ceiling on the stored image's width. Matches PetPhotoStorage's own limit: a crop is saved
         * as a file like any other photo, and there is no point storing more pixels than a phone
         * screen can show.
         */
        private const val MAX_OUTPUT_WIDTH_PX = 1024
    }
}
