package com.prash.petcare.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Stores pet photos in the app's internal storage. */
object PetPhotoStorage {

    /** Subfolder of `filesDir` holding every pet photo. */
    private const val PHOTO_DIRECTORY = "pet_photos"

    /** Longest edge kept when saving, in pixels. Comfortably sharp on a high-density phone screen. */
    private const val MAX_DIMENSION_PX = 1024

    /** JPEG quality. 85 is the usual sweet spot where further loss starts to become visible. */
    private const val JPEG_QUALITY = 85

    /** Reads the image at [sourceUri] into memory at a workable size, without storing anything. */
    suspend fun decodeForCrop(context: Context, sourceUri: Uri): Bitmap? = withContext(Dispatchers.IO) {
        decodeScaled(context, sourceUri)
    }

    /** Writes an already-prepared bitmap into internal storage and returns its absolute path. */
    suspend fun saveBitmap(context: Context, bitmap: Bitmap): String? = withContext(Dispatchers.IO) {
        val directory = File(context.filesDir, PHOTO_DIRECTORY)
        // mkdirs() is safe to call when the folder already exists; it simply returns false.
        directory.mkdirs()

        // A timestamped name guarantees each save is a new file.
        val target = File(directory, "pet_${System.currentTimeMillis()}.jpg")

        try {
            target.outputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
            }
            target.absolutePath
        } catch (e: java.io.IOException) {
            // Storage full, or the file could not be created. Returning null lets the caller show a message instead.
            target.delete()
            null
        }
        // No recycle() here, unlike the version this replaced: the bitmap is the crop view's, it is still on screen.
    }

    /** Deletes a previously saved photo. Safe to call with null or a path that no longer exists. */
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

    /** Decodes [uri] at a reduced size. */
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

    /** Works out the `inSampleSize` that brings the longest edge to about [MAX_DIMENSION_PX]. */
    private fun calculateSampleSize(width: Int, height: Int): Int {
        var sampleSize = 1
        while (maxOf(width, height) / (sampleSize * 2) >= MAX_DIMENSION_PX) {
            sampleSize *= 2
        }
        return sampleSize
    }
}
