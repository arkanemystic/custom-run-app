package dev.pacer.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [PresetEntity::class, RunEntity::class, PointEntity::class], version = 1, exportSchema = true)
abstract class PacerDatabase : RoomDatabase() {
    abstract fun presets(): PresetDao
    abstract fun runs(): RunDao

    companion object {
        fun create(context: Context): PacerDatabase =
            Room.databaseBuilder(context, PacerDatabase::class.java, "pacer.db").build()
    }
}
