package com.vellora.dualapp.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * One database file per Android profile (personal vs. work), since each
 * profile has its own isolated app-data directory — this naturally keeps
 * "apps registered on the personal side" separate from "apps cloned into
 * the work profile", with zero extra code needed.
 */
@Database(entities = [ClonedAppEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun clonedAppDao(): ClonedAppDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "cloner.db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}
