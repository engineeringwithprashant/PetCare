package com.prash.petcare.util

import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Instrumented tests for [QrCodeRenderer], the QR code on the two-factor setup screen. */
@RunWith(AndroidJUnit4::class)
class QrCodeRendererTest {

    /** A realistic input: the same shape of otpauth URI the setup screen encodes. */
    private val otpAuthUri =
        "otpauth://totp/prashant%40example.com?secret=JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP" +
            "&issuer=PetCare&algorithm=SHA1&digits=6&period=30"

    /** The bitmap must come out at exactly the requested size. */
    @Test
    fun encodesAtExactlyTheRequestedSize() {
        val bitmap = QrCodeRenderer.encode(otpAuthUri, 512)

        requireNotNull(bitmap)
        assertEquals(512, bitmap.width)
        assertEquals(512, bitmap.height)
    }

    /** Dark modules on a light ground, and nothing in between. */
    @Test
    fun drawsOnlyBlackAndWhite() {
        val bitmap = requireNotNull(QrCodeRenderer.encode(otpAuthUri, 256))

        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        val colours = pixels.toHashSet()
        assertTrue("unexpected colours: $colours", colours.all { it == Color.BLACK || it == Color.WHITE })
        // Both must be present: an all-white bitmap would pass the check above and be no code at all.
        assertTrue("no dark modules were drawn", colours.contains(Color.BLACK))
        assertTrue("no light background was drawn", colours.contains(Color.WHITE))
    }

    /** The corner a scanner looks for first is dark. */
    @Test
    fun paintsTheFinderPattern() {
        val size = 256
        val bitmap = requireNotNull(QrCodeRenderer.encode(otpAuthUri, size))

        // The corner itself is quiet zone; a little way in is the finder pattern's dark ring.
        assertEquals(Color.WHITE, bitmap.getPixel(1, 1))
        assertTrue(
            "expected dark modules near the top-left finder pattern",
            (0 until size / 3).any { bitmap.getPixel(it, it) == Color.BLACK }
        )
    }

    /** Nothing to encode, or nowhere to draw it, returns null rather than throwing. */
    @Test
    fun returnsNullRatherThanThrowingForUnusableInput() {
        assertNull(QrCodeRenderer.encode("", 256))
        assertNull(QrCodeRenderer.encode(otpAuthUri, 0))
        assertNull(QrCodeRenderer.encode(otpAuthUri, -10))
    }
}
