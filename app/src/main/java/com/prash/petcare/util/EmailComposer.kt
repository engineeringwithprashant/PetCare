package com.prash.petcare.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent

/**
 * Opens a pre-filled email in whichever mail app the user already has set up.
 *
 * The counterpart to [SmsSender], and deliberately a very different shape. [SmsSender] sends the
 * message itself, which is why it needs a dangerous permission and why the app has to show a
 * preview and take a confirmation first. This sends nothing: it hands the subject and body to
 * another app and stops there. The user then sees the draft in their own mail app, chooses who it
 * goes to, and taps send — or abandons it — entirely outside PetCare.
 *
 * That is why emailing a checklist needs **no permission at all**, and no in-app confirmation
 * either: the mail app's compose window is the confirmation.
 */
object EmailComposer {

    /**
     * Opens a draft email containing [subject] and [body].
     *
     * @param context Used to start the chooser. Must be an Activity context — a Fragment's
     *   `requireContext()` is one — because starting an activity from an application context
     *   would need FLAG_ACTIVITY_NEW_TASK and would launch the mail app in its own task, outside
     *   the back stack the user is actually in.
     * @param chooserTitle Heading for the app chooser, passed in rather than resolved here so all
     *   of the feature's wording stays in the UI layer alongside the rest of it.
     * @return True if a mail app was opened; false if the device has none, which the caller should
     *   explain rather than treat as a failure.
     */
    fun compose(
        context: Context,
        subject: String,
        body: String,
        chooserTitle: String
    ): Boolean {
        val intent = Intent(Intent.ACTION_SEND).apply {
            // The MIME type is what decides which apps offer to handle this. "text/plain" would
            // work but would also pull in messaging apps, note-takers and clipboard tools, none of
            // which understand EXTRA_SUBJECT; "message/rfc822" — the type of an email message —
            // narrows the chooser to apps that can actually compose mail.
            type = MIME_TYPE_EMAIL

            // EXTRA_SUBJECT and EXTRA_TEXT are the standard extras every mail app reads. No
            // EXTRA_EMAIL is set: the recipient is left for the user to fill in, which keeps this
            // path free of the contacts permission the SMS path needs.
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
        }

        return try {
            // createChooser always asks which app to use, even when the user has a default. That is
            // wanted here: someone may keep work and personal mail in separate apps and want the
            // checklist to go from a particular one.
            context.startActivity(Intent.createChooser(intent, chooserTitle))
            true
        } catch (e: ActivityNotFoundException) {
            // Thrown when the device has no app able to compose mail at all.
            //
            // Checking up front with resolveActivity would be the obvious alternative, but from
            // Android 11 (API 30) package visibility filters what an app is allowed to see: that
            // call would report "nothing installed" unless a matching <queries> element were
            // declared in the manifest. The chooser itself is a system component and is not
            // filtered, so letting the launch fail and handling it here is both simpler and more
            // reliable than duplicating the intent as a manifest declaration.
            false
        }
    }

    /** The MIME type of an email message, per RFC 822. */
    private const val MIME_TYPE_EMAIL = "message/rfc822"
}
