package com.prash.petcare.ui.common

import android.widget.ImageView
import com.prash.petcare.R
import com.prash.petcare.util.PetPhotoStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Loads a pet's photo into an [ImageView], or shows the paw-print placeholder when there is none.
 *
 * **Why this is not three lines at the call site.** Decoding a photo touches the disk, so it cannot
 * happen on the main thread, so it has to be a coroutine — and the moment it is a coroutine, a
 * recycled list row introduces a bug that is easy to write and unpleasant to find. The user scrolls,
 * a row bound to Bella is rebound to Max, and Bella's load finishes *afterwards* and paints her
 * photo into Max's card. The list then shows the wrong animal until that row happens to be redrawn.
 *
 * This function closes that hole twice over, which is belt and braces on purpose because the two
 * guards cover different moments:
 *
 *  1. **Cancel the previous load.** Rebinding a view cancels whatever coroutine was still loading
 *     into it. This is what stops work piling up during a fast scroll — without it, flinging through
 *     twenty pets starts twenty decodes and every one of them runs to completion.
 *  2. **Check the view still wants this photo.** Cancellation is not instantaneous: a coroutine
 *     that has already finished its disk read and is waiting to resume cannot be stopped. So the
 *     path the view is *currently* meant to be showing is recorded on the view itself, and the
 *     finished load compares against it before touching a single pixel.
 *
 * Both pieces of state hang off the view rather than the adapter, using the tag ids declared in
 * res/values/ids.xml. That is what lets this be a plain extension function usable from any adapter,
 * and it means the state is destroyed exactly when the view is.
 *
 * @param path Absolute path to the stored photo, or null when the pet has none.
 * @param scope The coroutine scope the load runs in. Callers pass `viewLifecycleOwner.lifecycleScope`
 *   so that leaving the screen cancels every load still in flight, rather than having them finish
 *   and write into views that no longer exist.
 */
fun ImageView.loadPetPhoto(path: String?, scope: CoroutineScope) {
    // Guard 1: whatever this view was loading before, it is not loading it now.
    (getTag(R.id.tag_pet_photo_job) as? Job)?.cancel()

    // Recorded before the load starts, so the check inside it has something to compare against.
    setTag(R.id.tag_pet_photo_path, path)

    // The placeholder goes in first either way. A recycled row still holds the previous pet's
    // bitmap, and leaving it there while the new one decodes would show the wrong animal for a
    // frame or two — which reads as the list glitching rather than as an image loading.
    showPlaceholder()

    if (path == null) return

    val job = scope.launch {
        val bitmap = PetPhotoStorage.load(path)

        // Guard 2: this view may have been rebound to another pet while the decode was running.
        if (getTag(R.id.tag_pet_photo_path) != path) return@launch

        // Null means the file has gone missing — the placeholder already showing is the right
        // answer, so there is nothing to do.
        if (bitmap == null) return@launch

        setImageBitmap(bitmap)
        // A real photo fills the frame and is cropped to it; the placeholder is a small glyph that
        // needs breathing room around it. Both are set explicitly because this view may be recycled
        // from a row that was showing the other kind.
        scaleType = ImageView.ScaleType.CENTER_CROP
        setPadding(0, 0, 0, 0)
    }

    setTag(R.id.tag_pet_photo_job, job)
}

/**
 * Puts the paw-print placeholder in the frame.
 *
 * `CENTER_INSIDE` with padding, rather than the `CENTER_CROP` a photo uses: the placeholder is an
 * icon and cropping it to fill a wide card would leave the user looking at one toe.
 */
private fun ImageView.showPlaceholder() {
    setImageResource(R.drawable.ic_pet_placeholder)
    scaleType = ImageView.ScaleType.CENTER_INSIDE
    val inset = resources.getDimensionPixelSize(R.dimen.spacing_md)
    setPadding(inset, inset, inset, inset)
}
