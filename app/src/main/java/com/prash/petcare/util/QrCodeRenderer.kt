package com.prash.petcare.util

import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.graphics.createBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Draws a QR code for a piece of text — used by the two-factor setup screen for the `otpauth://` URI
 * an authenticator app scans.
 *
 * A `util` object rather than a repository: it takes a string and returns a picture of it, touching
 * no data source and holding no state. It is also not a View: it produces a [Bitmap] that a fragment
 * puts into an ImageView, which keeps the encoding off the layout and lets it run on a background
 * thread.
 *
 * **Why ZXing's `core` and hand-drawn pixels.** `core` is the pure-Java encoder: it answers "which of
 * these squares are black?" as a [com.google.zxing.common.BitMatrix] and draws nothing. ZXing's own
 * renderer lives in a separate `javase` artifact that paints into `java.awt.image.BufferedImage` — a
 * class Android does not have — so on Android the matrix has to be painted by hand anyway. It is
 * about fifteen lines, and doing it here means the bitmap comes out at exactly the size asked for
 * rather than being scaled up from the matrix's natural size and going soft at the edges.
 *
 * **Why it is always black on white, in both themes.** A QR code is read by a camera, and scanners
 * expect dark modules on a light background — several refuse an inverted code outright. So this does
 * not follow the app's light/dark colours; the setup screen puts the code on a white card with a
 * quiet zone of padding around it, which looks deliberate in dark mode and, more importantly, scans.
 */
object QrCodeRenderer {

    /**
     * Encodes [content] as a square QR bitmap [sizePx] pixels on each side.
     *
     * @param sizePx The bitmap's width and height in pixels. The caller works this out from the view
     *   it will be shown in, so the code is drawn at its display size and never resampled.
     * @return The bitmap, or null if the text could not be encoded — which in practice means it was
     *   too long for the format. Null rather than a thrown exception, so the screen can show a
     *   readable message and the secret underneath (which is typed by hand and needs no QR code at
     *   all) instead of crashing on a picture.
     */
    fun encode(content: String, sizePx: Int): Bitmap? {
        if (content.isEmpty() || sizePx <= 0) return null

        val matrix = runCatching {
            QRCodeWriter().encode(
                content,
                BarcodeFormat.QR_CODE,
                sizePx,
                sizePx,
                mapOf(
                    // A QR code is read off a screen at an angle, sometimes by a camera focusing past
                    // it. M recovers from about 15% of the code being unreadable, which is the usual
                    // choice for an on-screen code: higher levels make the pattern denser, and a
                    // denser pattern on a phone screen is harder to read, not easier.
                    EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                    // The white border a scanner needs to find the code's edges. ZXing measures it in
                    // modules; the layout adds visible padding around the image as well, so this is
                    // only the minimum the format requires.
                    EncodeHintType.MARGIN to QUIET_ZONE_MODULES,
                    // The otpauth URI is plain ASCII, but saying so stops ZXing from guessing an
                    // encoding and writing a byte-mode header some readers handle differently.
                    EncodeHintType.CHARACTER_SET to Charsets.UTF_8.name()
                )
            )
        }.getOrNull() ?: return null

        // ARGB_8888 rather than a 1-bit format: Android has no bitmap config for two colours, and
        // this is the one every ImageView can draw without converting first. `createBitmap` here is
        // core-ktx's extension, which defaults to exactly that config — the same call the framework's
        // Bitmap.createBitmap makes, with the argument spelled out in the default rather than at the
        // call site.
        val bitmap = createBitmap(matrix.width, matrix.height)

        // setPixels with one prepared row at a time rather than setPixel per module: a 512px code is
        // a quarter of a million calls the other way, each crossing into native code.
        val row = IntArray(matrix.width)
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                row[x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
            }
            bitmap.setPixels(row, 0, matrix.width, 0, y, matrix.width, 1)
        }
        return bitmap
    }

    /** The blank border around the code, in QR modules. Four is what the specification asks for. */
    private const val QUIET_ZONE_MODULES = 4
}
