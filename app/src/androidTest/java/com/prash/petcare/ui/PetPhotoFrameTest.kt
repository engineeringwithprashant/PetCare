package com.prash.petcare.ui

import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.prash.petcare.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt

/** Checks that all three pet-photo frames really are the shape the cropper offers. */
@RunWith(AndroidJUnit4::class)
class PetPhotoFrameTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** Inflates a layout, measures it at a fixed width, and returns the photo view's size. */
    private fun measurePhoto(layoutRes: Int, widthPx: Int): Pair<Int, Int> {
        val parent = FrameLayout(context)
        val view = LayoutInflater.from(context).inflate(layoutRes, parent, false)

        view.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)

        val photo = view.findViewById<View>(R.id.petPhotoImage)
        return photo.measuredWidth to photo.measuredHeight
    }

    /** Asserts one frame is 4:3, allowing a pixel either way for rounding. */
    private fun assertFourByThree(layoutRes: Int, name: String) {
        val (width, height) = measurePhoto(layoutRes, MEASURED_WIDTH_PX)

        val expected = (width * 3f / 4f).roundToInt()
        assertEquals(
            "$name photo frame should be 4:3 (${width}px wide)",
            expected.toFloat(),
            height.toFloat(),
            1f
        )
    }

    @Test
    fun petsTabCardPhotoIsFourByThree() {
        assertFourByThree(R.layout.item_pet, "Pets tab card")
    }

    @Test
    fun petDetailHeroPhotoIsFourByThree() {
        assertFourByThree(R.layout.fragment_pet_detail, "Pet detail hero")
    }

    /** The dashboard card sets its own width rather than filling the row. */
    @Test
    fun homeRowCardPhotoIsFourByThree() {
        val declaredWidth = context.resources.getDimensionPixelSize(R.dimen.home_pet_card_width)
        val (width, height) = measurePhoto(R.layout.item_home_pet, declaredWidth)

        assertEquals("Home row card should keep its declared width", declaredWidth, width)
        assertEquals(
            "Home row card photo should be 4:3",
            (width * 3f / 4f).roundToInt().toFloat(),
            height.toFloat(),
            1f
        )
    }

    /** The cropper's numeric ratio must equal the one the layouts use. */
    @Test
    fun cropperRatioMatchesTheLayoutRatio() {
        // "H,4:3" — the "H," prefix tells ConstraintLayout which side to compute.
        val declared = context.getString(R.string.pet_photo_aspect_ratio).substringAfter(',')
        val (width, height) = declared.split(':').map { it.toFloat() }

        assertEquals(
            "PhotoCropView.PET_PHOTO_ASPECT must match pet_photo_aspect_ratio",
            width / height,
            com.prash.petcare.ui.common.PhotoCropView.PET_PHOTO_ASPECT,
            0.001f
        )
    }

    private companion object {
        /** An arbitrary but realistic card width in pixels; nothing depends on the exact value. */
        const val MEASURED_WIDTH_PX = 1000
    }
}
