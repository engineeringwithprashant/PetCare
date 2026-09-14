package com.prash.petcare.data.local.converter

import androidx.room.TypeConverter
import com.prash.petcare.data.local.entity.ExpenseCategory
import com.prash.petcare.data.local.entity.PetSpecies
import com.prash.petcare.data.local.entity.PlaceCategory
import com.prash.petcare.data.local.entity.RepeatUnit
import com.prash.petcare.data.local.entity.SyncEntityType
import com.prash.petcare.data.local.entity.TaskCategory

/**
 * Teaches Room how to store the app's enum types in SQLite.
 *
 * SQLite only understands a handful of primitive column types, so an enum constant such as
 * [PetSpecies.DOG] cannot be saved as-is. Each pair of methods below tells Room to write the
 * constant's [Enum.name] ("DOG") into a text column, then rebuild the constant when reading.
 *
 * Names are used rather than [Enum.ordinal] positions on purpose: ordinals are just index
 * numbers, so inserting a new constant in the middle of an enum would silently re-point every
 * existing row at the wrong value. Names survive reordering.
 *
 * The class is registered once on the database with `@TypeConverters`, which makes these
 * conversions available to every entity and DAO query.
 *
 * Dates need no converter here — they are already stored as `Long` epoch milliseconds, a type
 * SQLite handles natively.
 */
class Converters {

    @TypeConverter
    fun fromPetSpecies(species: PetSpecies): String = species.name

    @TypeConverter
    fun toPetSpecies(value: String): PetSpecies = PetSpecies.valueOf(value)

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
    fun fromSyncEntityType(type: SyncEntityType): String = type.name

    @TypeConverter
    fun toSyncEntityType(value: String): SyncEntityType = SyncEntityType.valueOf(value)
}
