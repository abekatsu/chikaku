package com.damburisoft.chikaku.watch.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [PendingLocation::class], version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {

    abstract fun pendingLocationDao(): PendingLocationDao

    companion object {
        const val NAME = "chikaku.db"

        /**
         * **渡された [context] をそのまま使う。`applicationContext` を挟んではいけない。**
         * 呼び出し側は端末保護ストレージの Context を渡しており（[DeviceStorage]）、
         * 挟むと保存先が認証情報暗号化ストレージに戻ってロック解除前に開けなくなる。
         */
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, NAME).build()
    }
}
