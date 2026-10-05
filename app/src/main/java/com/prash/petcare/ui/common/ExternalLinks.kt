package com.prash.petcare.ui.common

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.core.net.toUri
import androidx.fragment.app.Fragment

/** Hands a web address to whichever app on the device wants it. */
fun Fragment.openExternalLink(url: String): Boolean = try {
    // NEW_TASK so the link opens as its own entry in the recents list.
    startActivity(
        Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
    true
} catch (e: ActivityNotFoundException) {
    false
}
