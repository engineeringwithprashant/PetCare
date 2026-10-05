package com.prash.petcare.data.local.entity

/** What part a [User] plays in looking after the pets on their account. */
enum class UserRole {

    /** The pets on this account are the user's own. The default, and what every pre-existing account becomes. */
    OWNER,

    /** The user looks after pets that belong to someone else. */
    CARER
}
