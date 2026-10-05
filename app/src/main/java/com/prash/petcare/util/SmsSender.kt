package com.prash.petcare.util

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager

/** Sends a text message from the user's own device. */
object SmsSender {

    /** True if this device can send text messages at all. */
    fun isSmsSupported(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)

    /** How many individual text messages [message] will be split into. */
    fun partCount(context: Context, message: String): Int =
        smsManager(context)?.divideMessage(message)?.size ?: 1

    /** Sends [message] to [phoneNumber]. Returns true if it was handed to the system successfully. */
    fun send(context: Context, phoneNumber: String, message: String): Boolean {
        val smsManager = smsManager(context) ?: return false

        return try {
            // Long messages must be split and sent as a multipart message.
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
            // Thrown if the permission was revoked between the check and this call.
            false
        }
    }

    /** Obtains the system's SmsManager. */
    private fun smsManager(context: Context): SmsManager? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }
}
