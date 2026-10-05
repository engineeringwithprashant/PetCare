package com.prash.petcare.data.local

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Someone the user hands a checklist to, saved so they do not have to be found in the address book every time. */
data class DelegationContact(
    val id: String,
    val name: String,
    val phoneNumber: String,
    val note: String? = null
)

/** Remembers the handful of people the user delegates care to. */
class DelegationContactStore(context: Context) {

    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private val _contacts = MutableStateFlow(read())

    /** The saved contacts, newest last. */
    val contacts: StateFlow<List<DelegationContact>> = _contacts.asStateFlow()

    /** Saves a new contact and returns it. */
    fun add(name: String, phoneNumber: String, note: String?): DelegationContact {
        val contact = DelegationContact(
            id = "${System.currentTimeMillis()}-${_contacts.value.size}",
            name = name.trim(),
            phoneNumber = phoneNumber.trim(),
            note = note?.trim()?.takeIf { it.isNotEmpty() }
        )

        write(_contacts.value + contact)
        return contact
    }

    /** Forgets one contact. Nothing happens if the id is not in the list. */
    fun remove(contactId: String) {
        val remaining = _contacts.value.filterNot { it.id == contactId }
        if (remaining.size != _contacts.value.size) {
            write(remaining)
        }
    }

    /** Stores the list and publishes it. */
    private fun write(contacts: List<DelegationContact>) {
        val array = JSONArray()

        contacts.forEach { contact ->
            array.put(
                JSONObject().apply {
                    put(FIELD_ID, contact.id)
                    put(FIELD_NAME, contact.name)
                    put(FIELD_PHONE, contact.phoneNumber)
                    // put(key, null) removes the key rather than storing a JSON null, which is exactly what is wanted.
                    put(FIELD_NOTE, contact.note)
                }
            )
        }

        preferences.edit { putString(KEY_CONTACTS, array.toString()) }
        _contacts.value = contacts
    }

    /** Reads the stored list back, or an empty list if there is nothing saved or the text cannot be parsed. */
    private fun read(): List<DelegationContact> {
        val stored = preferences.getString(KEY_CONTACTS, null) ?: return emptyList()

        return runCatching {
            val array = JSONArray(stored)

            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null

                val name = item.optString(FIELD_NAME).takeIf { it.isNotBlank() }
                val phone = item.optString(FIELD_PHONE).takeIf { it.isNotBlank() }

                // A contact missing either half cannot be delegated to, so it is dropped.
                if (name == null || phone == null) return@mapNotNull null

                DelegationContact(
                    id = item.optString(FIELD_ID).takeIf { it.isNotBlank() } ?: "$index",
                    name = name,
                    phoneNumber = phone,
                    note = item.optString(FIELD_NOTE).takeIf { it.isNotBlank() }
                )
            }
        }.getOrDefault(emptyList())
    }

    private companion object {
        /** Its own file rather than sharing another: unrelated settings, unrelated lifetimes. */
        const val PREFERENCES_NAME = "petcare_delegation_contacts"
        const val KEY_CONTACTS = "contacts"

        const val FIELD_ID = "id"
        const val FIELD_NAME = "name"
        const val FIELD_PHONE = "phone"
        const val FIELD_NOTE = "note"
    }
}
