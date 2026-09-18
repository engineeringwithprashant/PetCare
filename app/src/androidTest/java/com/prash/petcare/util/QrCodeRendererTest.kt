package com.prash.petcare.util

import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for [QrCodeRenderer], the QR code on the two-factor setup screen.
 *
 * **Why these run on a device rather than on the JVM.** The class produces a [android.graphics.Bitmap],
 * which is a framework type backed by native code — on a plain JVM unit test it is a stub that returns
 * null from everything. The encoding is real work with real failure modes, so it is tested where the
 * graphics stack actually exists.
 *
 * What is checked is what the *screen* depends on: a bitmap of exactly the size asked for, in the two
 * colours a scanner expects, and a null rather than a crash when there is nothing to draw. Whether the
 * module pattern is a correct QR code is ZXing's business.
 */
@RunWith(AndroidJUnit4::class)
class QrCodeRendererTest {

    /** A realistic input: the same shape of otpauth URI the setup screen encodes. */
    private val otpAuthUri =
        "otpauth://totp/prashant%40example.com?secret=JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP" +
            "&issuer=PetCare&algorithm=SHA1&digits=6&period=30"

    /**
     * The bitmap must come out at exactly the requested size.
     *
     * This is the property the setup screen relies on: it passes the ImageView's own pixel width so
     * the code is never resampled, and a resampled QR code softens at the module edges — which is the
     * part a camera is reading.
     */
    @Test
    fun encodesAtExactlyTheRequestedSize() {
        val bitmap = QrCodeRenderer.encode(otpAuthUri, 512)

        requireNotNull(bitmap)
        assertEquals(512, bitmap.width)
        assertEquals(512, bitmap.height)
    }

    /**
     * Dark modules on a light ground, and nothing in between.
     *
     * Scanners expect that polarity and several refuse an inverted code, which is why the setup screen
     * puts this on a white card even in dark mode rather than tinting it to the theme. A stray third
     * colour would mean something had anti-aliased the modules.
     */
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

    /**
     * The corner a scanner looks for first is dark.
     *
     * Every QR code carries a finder pattern in three corners, inside the quiet zone. Sampling just
     * inside the top-left one is a cheap way of confirming the matrix was really painted rather than
     * that a blank bitmap of the right size came back.
     */
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

    /**
     * Nothing to encode, or nowhere to draw it, returns null rather than throwing.
     *
     * The setup screen turns a null into a line telling the user to type the key by hand, so the
     * feature still works without a picture — see fragment_two_factor_setup.xml.
     */
    @Test
    fun returnsNullRatherThanThrowingForUnusableInput() {
        assertNull(QrCodeRenderer.encode("", 256))
        assertNull(QrCodeRenderer.encode(otpAuthUri, 0))
        assertNull(QrCodeRenderer.encode(otpAuthUri, -10))
    }
}
