package com.prash.petcare.data.local.entity

/** The sex of a [Pet], as far as the owner knows it. */
enum class PetGender {
    MALE,
    FEMALE,

    /** Not recorded. The default for every pet created before this field existed. */
    UNKNOWN
}
