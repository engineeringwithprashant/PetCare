package com.prash.petcare.ui.common

import android.widget.ImageView
import com.prash.petcare.R
import com.prash.petcare.util.PetPhotoStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Loads a pet's photo into an [ImageView], or shows the paw-print placeholder when there is none. */
fun ImageView.loadPetPhoto(path: String?, scope: CoroutineScope) {
    // Guard 1: whatever this view was loading before, it is not loading it now.
    (getTag(R.id.tag_pet_photo_job) as? Job)?.cancel()

    // Recorded before the load starts, so the check inside it has something to compare against.
    setTag(R.id.tag_pet_photo_path, path)

    // The placeholder goes in first either way. A recycled row still holds the previous pet's bitmap.
    showPlaceholder()

    if (path == null) return

    val job = scope.launch {
        val bitmap = PetPhotoStorage.load(path)

        // Guard 2: this view may have been rebound to another pet while the decode was running.
        if (getTag(R.id.tag_pet_photo_path) != path) return@launch

        // Null means the file has gone missing — the placeholder already showing is the right answer.
        if (bitmap == null) return@launch

        setImageBitmap(bitmap)
        // A real photo fills the frame and is cropped.
        scaleType = ImageView.ScaleType.CENTER_CROP
        setPadding(0, 0, 0, 0)
    }

    setTag(R.id.tag_pet_photo_job, job)
}

/** Puts the paw-print placeholder in the frame. */
private fun ImageView.showPlaceholder() {
    setImageResource(R.drawable.ic_pet_placeholder)
    scaleType = ImageView.ScaleType.CENTER_INSIDE
    val inset = resources.getDimensionPixelSize(R.dimen.spacing_md)
    setPadding(inset, inset, inset, inset)
}
