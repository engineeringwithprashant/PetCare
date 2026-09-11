package com.prash.petcare.util

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The outcome of looking a contact up in the phone's address book.
 *
 * Modelled as three distinct results rather than a nullable value, because the caller needs to tell
 * the cases apart: "this person has no number saved" deserves a different message from "that
 * contact could not be read at all", and a plain null could not express the difference.
 */
sealed interface ContactLookup {

    /** The contact was read and has a phone number. */
    data class Found(val name: String, val phoneNumber: String) : ContactLookup

    /** The contact exists but has no phone number recorded, so nothing can be sent to them. */
    data class NoPhoneNumber(val name: String) : ContactLookup

    /** The contact row could not be read — deleted between picking and reading, or inaccessible. */
    data object Unreadable : ContactLookup
}

/**
 * Reads a single contact chosen through the system contact picker.
 *
 * Requires the READ_CONTACTS permission; the caller is responsible for having obtained it before
 * calling. Only the one contact the user explicitly picked is ever read — the address book is
 * never enumerated — and nothing is stored or sent anywhere.
 */
object ContactReader {

    /**
     * Looks up the display name and first phone number behind [contactUri].
     *
     * Two queries are needed because of how the contacts database is laid out: a contact and its
     * phone numbers live in separate tables, since one person can have several numbers. The first
     * query fetches the contact's id and name, the second finds numbers belonging to that id.
     *
     * Runs on [Dispatchers.IO] because a ContentResolver query hits a database in another process
     * and must never block the main thread.
     */
    suspend fun read(context: Context, contactUri: Uri): ContactLookup = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver

        var contactId: String? = null
        var displayName: String? = null

        // `use` closes the cursor even if something throws. A leaked cursor holds a database
        // connection open and is reported as an error by Android's StrictMode.
        resolver.query(
            contactUri,
            arrayOf(
                ContactsContract.Contacts._ID,
                ContactsContract.Contacts.DISPLAY_NAME_PRIMARY
            ),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                contactId = cursor.getString(0)
                displayName = cursor.getString(1)
            }
        }

        val id = contactId ?: return@withContext ContactLookup.Unreadable
        val name = displayName.orEmpty()

        var phoneNumber: String? = null

        resolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            // A parameterised selection, never string concatenation: the id goes in as a bound
            // argument so it is treated as a value and cannot alter the query itself.
            "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
            arrayOf(id),
            null
        )?.use { cursor ->
            // The first number is taken when someone has several. Offering a choice would be
            // better, but this keeps the flow to a single tap.
            if (cursor.moveToFirst()) {
                phoneNumber = cursor.getString(0)
            }
        }

        val number = phoneNumber?.takeIf { it.isNotBlank() }
            ?: return@withContext ContactLookup.NoPhoneNumber(name)

        ContactLookup.Found(name = name, phoneNumber = number)
    }
}
