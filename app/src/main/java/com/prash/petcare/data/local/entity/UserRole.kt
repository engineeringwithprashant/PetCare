package com.prash.petcare.data.local.entity

/**
 * What part a [User] plays in looking after the pets on their account.
 *
 * An enum rather than free text, for the same reasons as [PetGender] and [PetSpecies]: the account
 * form can offer a fixed set of choices, and the profile screen can label the role pill from a known
 * constant instead of guarding against whatever someone typed.
 *
 * **Why only two constants, in an app with one account per device.** The role does not grant
 * anything — there is no sharing, no permission system and no second signed-in person to grant
 * something *to*. It records how the user describes themselves, which is a genuine difference worth
 * storing: someone caring for a friend's cat while they travel is not that cat's owner, and a
 * delegation message that opens "Prashant's pets" reads wrongly when the pets are somebody else's.
 * Anything more than these two would be inventing an access model the app does not have.
 *
 * Stored in SQLite as the constant's name via
 * [com.prash.petcare.data.local.converter.Converters], so the order of the constants below can be
 * changed freely without re-pointing existing rows.
 */
enum class UserRole {

    /** The pets on this account are the user's own. The default, and what every pre-existing account becomes. */
    OWNER,

    /** The user looks after pets that belong to someone else. */
    CARER
}
