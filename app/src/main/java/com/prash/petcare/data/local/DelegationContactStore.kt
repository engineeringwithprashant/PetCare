package com.prash.petcare.data.local

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Someone the user hands a checklist to, saved so they do not have to be found in the address book
 * every time.
 *
 * @property id A generated identifier, so a contact can be removed without relying on the name being
 *   unique — two people called Sysy are perfectly possible, and deleting both because one was tapped
 *   would be a nasty surprise.
 * @property name Who they are, as the user typed it or as the contact picker reported it.
 * @property phoneNumber Where the text message goes. Required: a delegation contact with no number
 *   is a contact nothing can be delegated to, which is the one thing this list exists for.
 * @property note What they help with, in the user's own words — "Feeds the cats on weekends". Shown
 *   as the row's second line, and optional because a name and a number are enough to be useful.
 */
data class DelegationContact(
    val id: String,
    val name: String,
    val phoneNumber: String,
    val note: String? = null
)

/**
 * Remembers the handful of people the user delegates care to.
 *
 * **What this is not.** It is not a care *team*: there is no sharing, no invitation, no second signed-in
 * person and no access being granted. The app has one account per device and this list changes nothing
 * about that. It is an address book of two or three people, so that handing over a checklist is a tap
 * instead of a walk through the system contact picker every time.
 *
 * Saying that plainly matters, because the row it draws on the profile screen sits where a mockup
 * showed a care team with roles and an invite state. Those would have implied an access model the app
 * does not have, and a "HELPER" badge next to a name that grants nothing is a promise the app cannot
 * keep.
 *
 * **Why SharedPreferences and not Room.** The same reasoning as [SettingsStore] and [AlertReadStore]:
 * everything in the database describes the user's animals and syncs to Firestore, and this is a short
 * local list of people on *this* phone whose numbers came out of *this* phone's address book. It is
 * also small enough that the cost of an entity, a DAO, a migration and a place in the sync would be
 * out of all proportion to three rows of text.
 *
 * **Why JSON in a single string, rather than a `StringSet`.** A contact is three fields that belong
 * together, and `putStringSet` would either need them glued with a separator — which breaks the first
 * time someone's note contains it — or three parallel sets that can drift out of step. One JSON array
 * keeps a contact whole, and `org.json` is part of Android, so it costs no dependency.
 *
 * @param context Any context; only the application context is kept, so no screen can be leaked.
 */
class DelegationContactStore(context: Context) {

    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private val _contacts = MutableStateFlow(read())

    /**
     * The saved contacts, newest last.
     *
     * A [StateFlow] for the same reason the other local stores publish one: adding or removing a
     * contact has to redraw the card immediately, and a flow lets the ViewModel combine this with the
     * account like any other source rather than refreshing by hand.
     */
    val contacts: StateFlow<List<DelegationContact>> = _contacts.asStateFlow()

    /**
     * Saves a new contact and returns it.
     *
     * The id is generated here rather than taken from the caller, so nothing outside this class has
     * to know how ids are shaped. A timestamp plus the list size is unique enough for a list that
     * will never hold more than a handful: two contacts added in the same millisecond differ by
     * their position.
     */
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

    /**
     * Stores the list and publishes it.
     *
     * Both in one place so the file and the flow can never disagree — the same arrangement as
     * [AlertReadStore.write] and [SettingsStore].
     */
    private fun write(contacts: List<DelegationContact>) {
        val array = JSONArray()

        contacts.forEach { contact ->
            array.put(
                JSONObject().apply {
                    put(FIELD_ID, contact.id)
                    put(FIELD_NAME, contact.name)
                    put(FIELD_PHONE, contact.phoneNumber)
                    // put(key, null) removes the key rather than storing a JSON null, which is
                    // exactly what is wanted: a contact with no note simply has no note field.
                    put(FIELD_NOTE, contact.note)
                }
            )
        }

        preferences.edit { putString(KEY_CONTACTS, array.toString()) }
        _contacts.value = contacts
    }

    /**
     * Reads the stored list back, or an empty list if there is nothing saved or the text cannot be
     * parsed.
     *
     * Failing quietly to an empty list is the right call for a preferences file: the alternative is
     * crashing on launch because a value was hand-edited or written by a version that shaped it
     * differently, and losing two saved numbers is a far smaller harm than an app that will not open.
     * `optString` returning "" for a missing key is why the note is filtered for emptiness rather
     * than compared to null.
     */
    private fun read(): List<DelegationContact> {
        val stored = preferences.getString(KEY_CONTACTS, null) ?: return emptyList()

        return runCatching {
            val array = JSONArray(stored)

            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null

                val name = item.optString(FIELD_NAME).takeIf { it.isNotBlank() }
                val phone = item.optString(FIELD_PHONE).takeIf { it.isNotBlank() }

                // A contact missing either half cannot be delegated to, so it is dropped rather than
                // shown as a row that does nothing when tapped.
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
