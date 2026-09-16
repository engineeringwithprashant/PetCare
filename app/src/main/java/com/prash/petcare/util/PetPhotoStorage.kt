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
     * Reads the image at [sourceUri] into memory at a workable size, without storing anything.
     *
     * **Why picking and saving are now two steps.** A chosen photo is no longer saved straight to
     * disk: it is handed to [com.prash.petcare.ui.common.PhotoCropView] first so the user can frame
     * it, and only the framed result is written by [saveBitmap]. Splitting the old single `save` at
     * exactly this point is what makes that possible, and it means the picked image never becomes a
     * file the app would have to clean up if the user cancels the crop.
     *
     * Runs on [Dispatchers.IO]: it reads through a content provider, which is disk or even network
     * access behind an ordinary-looking call.
     */
    suspend fun decodeForCrop(context: Context, sourceUri: Uri): Bitmap? = withContext(Dispatchers.IO) {
        decodeScaled(context, sourceUri)
    }

    /**
     * Writes an already-prepared bitmap into internal storage and returns its absolute path, or null
     * if it could not be written.
     *
     * Takes a bitmap rather than a URI because by this point the image is the user's *crop* — it
     * exists only in memory and has no source of its own to re-read. The bitmap belongs to the
     * caller and is left intact, since the crop view is still showing it.
     *
     * Runs on [Dispatchers.IO] because compressing and writing a JPEG on the main thread would
     * freeze the UI for as long as it takes.
     */
    suspend fun saveBitmap(context: Context, bitmap: Bitmap): String? = withContext(Dispatchers.IO) {
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
        }
        // No recycle() here, unlike the version this replaced: the bitmap is the crop view's, it is
        // still on screen, and recycling a bitmap another view is drawing crashes on the next frame.
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
