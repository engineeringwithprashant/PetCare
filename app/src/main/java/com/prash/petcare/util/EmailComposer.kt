package com.prash.petcare.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent

/** Opens a pre-filled email in whichever mail app the user already has set up. */
object EmailComposer {

    /** Opens a draft email containing [subject] and [body]. */
    fun compose(
        context: Context,
        subject: String,
        body: String,
        chooserTitle: String
    ): Boolean {
        val intent = Intent(Intent.ACTION_SEND).apply {
            // The MIME type is what decides which apps offer to handle this.
            type = MIME_TYPE_EMAIL

            // EXTRA_SUBJECT and EXTRA_TEXT are the standard extras every mail app reads.
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
        }

        return try {
            // createChooser always asks which app to use, even when the user has a default.
            context.startActivity(Intent.createChooser(intent, chooserTitle))
            true
        } catch (e: ActivityNotFoundException) {
            // Thrown when the device has no app able to compose mail at all.
            false
        }
    }

    /** The MIME type of an email message, per RFC 822. */
    private const val MIME_TYPE_EMAIL = "message/rfc822"
}
