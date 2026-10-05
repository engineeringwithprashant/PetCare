package com.prash.petcare.util

import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.graphics.createBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** Draws a QR code for a piece of text. */
object QrCodeRenderer {

    /** Encodes [content] as a square QR bitmap [sizePx] pixels on each side. */
    fun encode(content: String, sizePx: Int): Bitmap? {
        if (content.isEmpty() || sizePx <= 0) return null

        val matrix = runCatching {
            QRCodeWriter().encode(
                content,
                BarcodeFormat.QR_CODE,
                sizePx,
                sizePx,
                mapOf(
                    // A QR code is read off a screen at an angle, sometimes by a camera focusing past it.
                    EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                    // The white border a scanner needs to find the code's edges.
                    EncodeHintType.MARGIN to QUIET_ZONE_MODULES,
                    // The otpauth URI is plain ASCII, but saying.
                    EncodeHintType.CHARACTER_SET to Charsets.UTF_8.name()
                )
            )
        }.getOrNull() ?: return null

        // ARGB_8888 rather than a 1-bit format: Android has no bitmap config for two colours.
        val bitmap = createBitmap(matrix.width, matrix.height)

        // setPixels with one prepared row at a time rather than setPixel per module.
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
