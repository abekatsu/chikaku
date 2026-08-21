package com.damburisoft.chikaku.watch.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [PendingLocation::class], version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {

    abstract fun pendingLocationDao(): PendingLocationDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "chikaku.db",
            ).build()
    }
}
