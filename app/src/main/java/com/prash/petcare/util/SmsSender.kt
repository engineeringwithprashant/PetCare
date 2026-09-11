package com.prash.petcare.util

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager

/**
 * Sends a text message from the user's own device.
 *
 * Requires the SEND_SMS permission; the caller must have obtained it before calling. A message is
 * only ever sent to a recipient the user picked themselves, and only after they have read the exact
 * text on a preview dialog and confirmed it — sending a text can cost money, so it must never
 * happen as a side effect of something else.
 */
object SmsSender {

    /**
     * True if this device can send text messages at all.
     *
     * A tablet or Wi-Fi-only device has no telephony hardware, and asking for SMS permission there
     * would prompt the user for something that could never work. Checking first lets the app
     * explain instead.
     */
    fun isSmsSupported(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)

    /**
     * How many individual text messages [message] will be split into.
     *
     * A single SMS holds only 160 characters of plain GSM text, far fewer once any character
     * outside that alphabet forces the whole message into a wider encoding. A care checklist easily
     * exceeds that, and each part is typically billed separately, so the count is shown on the
     * preview rather than letting the user find out from their bill.
     */
    fun partCount(context: Context, message: String): Int =
        smsManager(context)?.divideMessage(message)?.size ?: 1

    /**
     * Sends [message] to [phoneNumber]. Returns true if it was handed to the system successfully.
     *
     * A true result means the message was accepted for delivery, not that it arrived — actual
     * delivery is asynchronous and would need a PendingIntent to track, which is more than this
     * feature needs.
     */
    fun send(context: Context, phoneNumber: String, message: String): Boolean {
        val smsManager = smsManager(context) ?: return false

        return try {
            // Long messages must be split and sent as a multipart message. Passing an over-length
            // string to sendTextMessage would have it silently truncated.
            val parts = smsManager.divideMessage(message)

            if (parts.size <= 1) {
                smsManager.sendTextMessage(phoneNumber, null, message, null, null)
            } else {
                smsManager.sendMultipartTextMessage(phoneNumber, null, parts, null, null)
            }
            true
        } catch (e: IllegalArgumentException) {
            // Thrown for an empty message or an unusable phone number.
            false
        } catch (e: SecurityException) {
            // Thrown if the permission was revoked between the check and this call — the user can
            // do that from system settings at any moment, so it has to be handled rather than
            // assumed impossible.
            false
        }
    }

    /**
     * Obtains the system's SmsManager.
     *
     * The way to get one changed in Android 12 (API 31): before that it came from the static
     * `SmsManager.getDefault()`, and from 31 onwards through the normal system service lookup,
     * which is aware of which SIM the app should use on a dual-SIM phone. The app supports
     * minSdk 24, so both routes are needed.
     */
    private fun smsManager(context: Context): SmsManager? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }
}
