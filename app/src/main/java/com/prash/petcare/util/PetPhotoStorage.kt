package com.prash.petcare.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Stores pet photos in the app's internal storage.
 *
 * **Why the picked image is copied rather than just referenced.** The photo picker hands back a
 * `content://` URI that grants read access only temporarily — it stops working once the app
 * restarts, and the user could delete or move the original from their gallery at any time. Saving
 * a copy the app owns is what makes the photo still be there tomorrow.
 *
 * **Why internal storage.** Files under `filesDir` are private to this app, need no runtime
 * permission, and are removed automatically when the app is uninstalled, so no orphaned images are
 * left behind on the device.
 *
 * Images are downscaled and re-compressed on the way in. A modern phone camera produces files of
 * several megabytes at a resolution far beyond what a form preview needs; storing those untouched
 * would waste space and risk an out-of-memory crash when decoding them later.
 */
object PetPhotoStorage {

    /** Subfolder of `filesDir` holding every pet photo. */
    private const val PHOTO_DIRECTORY = "pet_photos"

    /**
     * Longest edge kept when saving, in pixels. Comfortably sharp on a high-density phone screen
     * while keeping each file to tens of kilobytes rather than megabytes.
     */
    private const val MAX_DIMENSION_PX = 1024

    /** JPEG quality. 85 is the usual sweet spot where further loss starts to become visible. */
    private const val JPEG_QUALITY = 85

    /**
     * Copies the image at [sourceUri] into internal storage and returns its absolute path, or null
     * if the image could not be read.
     *
     * Runs on [Dispatchers.IO] because it both reads from the content provider and writes a file;
     * doing either on the main thread would freeze the UI.
     */
    suspend fun save(context: Context, sourceUri: Uri): String? = withContext(Dispatchers.IO) {
        val bitmap = decodeScaled(context, sourceUri) ?: return@withContext null

        val directory = File(context.filesDir, PHOTO_DIRECTORY)
        // mkdirs() is safe to call when the folder already exists; it simply returns false.
        directory.mkdirs()

        // A timestamped name guarantees each save is a new file, so replacing a pet's photo never
        // overwrites an image that is still referenced by the row in the database.
        val target = File(directory, "pet_${System.currentTimeMillis()}.jpg")

        try {
            target.outputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
            }
            target.absolutePath
        } catch (e: java.io.IOException) {
            // Storage full, or the file could not be created. Returning null lets the caller show
            // a message instead of the app crashing.
            target.delete()
            null
        } finally {
            // Frees the decoded pixels immediately rather than waiting for the garbage collector.
            bitmap.recycle()
        }
    }

    /**
     * Deletes a previously saved photo. Safe to call with null or a path that no longer exists.
     *
     * Takes only a path, deliberately: no Context is needed, so a ViewModel can clean up orphaned
     * files without holding an Android context.
     */
    fun delete(path: String?) {
        if (path.isNullOrEmpty()) return
        val file = File(path)
        if (file.exists()) {
            file.delete()
        }
    }

    /** Loads a saved photo for display, or null if the file is missing or unreadable. */
    suspend fun load(path: String): Bitmap? = withContext(Dispatchers.IO) {
        val file = File(path)
        if (!file.exists()) return@withContext null
        // Already downscaled by [save], so a straight decode is cheap here.
        BitmapFactory.decodeFile(file.absolutePath)
    }

    /**
     * Decodes [uri] at a reduced size.
     *
     * This is the standard two-pass approach. The first pass sets `inJustDecodeBounds`, which reads
     * only the image's dimensions from its header without allocating memory for the pixels. Those
     * dimensions then determine how far to downscale, so the second pass never has to hold the
     * full-size image in memory — which is what a naive single-pass decode of a 50-megapixel photo
     * would do, often fatally.
     *
     * Note: orientation metadata is not applied, so a photo taken in landscape on some cameras may
     * appear rotated.
     */
    private fun decodeScaled(context: Context, uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, bounds)
        }

        // A width of zero means the header could not be read — not an image, or an unreadable URI.
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateSampleSize(bounds.outWidth, bounds.outHeight)
        }

        return context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, options)
        }
    }

    /**
     * Works out the `inSampleSize` that brings the longest edge to about [MAX_DIMENSION_PX].
     *
     * A sample size of 2 loads every second pixel, 4 every fourth, and so on. The decoder only
     * honours powers of two, which is why this doubles rather than dividing precisely — the result
     * is the nearest power of two that is still large enough.
     */
    private fun calculateSampleSize(width: Int, height: Int): Int {
        var sampleSize = 1
        while (maxOf(width, height) / (sampleSize * 2) >= MAX_DIMENSION_PX) {
            sampleSize *= 2
        }
        return sampleSize
    }
}
