package com.prash.petcare.data.local.converter

import androidx.room.TypeConverter
import com.prash.petcare.data.local.entity.ExpenseCategory
import com.prash.petcare.data.local.entity.PetGender
import com.prash.petcare.data.local.entity.PetSpecies
import com.prash.petcare.data.local.entity.PlaceCategory
import com.prash.petcare.data.local.entity.RepeatUnit
import com.prash.petcare.data.local.entity.SyncEntityType
import com.prash.petcare.data.local.entity.TaskCategory
import com.prash.petcare.data.local.entity.UserRole

/** Teaches Room how to store the app's enum types in SQLite. */
class Converters {

    @TypeConverter
    fun fromPetSpecies(species: PetSpecies): String = species.name

    @TypeConverter
    fun toPetSpecies(value: String): PetSpecies = PetSpecies.valueOf(value)

    @TypeConverter
    fun fromPetGender(gender: PetGender): String = gender.name

    @TypeConverter
    fun toPetGender(value: String): PetGender = PetGender.valueOf(value)

    @TypeConverter
    fun fromTaskCategory(category: TaskCategory): String = category.name

    @TypeConverter
    fun toTaskCategory(value: String): TaskCategory = TaskCategory.valueOf(value)

    @TypeConverter
    fun fromRepeatUnit(unit: RepeatUnit): String = unit.name

    @TypeConverter
    fun toRepeatUnit(value: String): RepeatUnit = RepeatUnit.valueOf(value)

    @TypeConverter
    fun fromExpenseCategory(category: ExpenseCategory): String = category.name

    @TypeConverter
    fun toExpenseCategory(value: String): ExpenseCategory = ExpenseCategory.valueOf(value)

    @TypeConverter
    fun fromPlaceCategory(category: PlaceCategory): String = category.name

    @TypeConverter
    fun toPlaceCategory(value: String): PlaceCategory = PlaceCategory.valueOf(value)

    @TypeConverter
    fun fromUserRole(role: UserRole): String = role.name

    @TypeConverter
    fun toUserRole(value: String): UserRole = UserRole.valueOf(value)

    @TypeConverter
    fun fromSyncEntityType(type: SyncEntityType): String = type.name

    @TypeConverter
    fun toSyncEntityType(value: String): SyncEntityType = SyncEntityType.valueOf(value)
}
