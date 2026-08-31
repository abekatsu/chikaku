package com.damburisoft.chikaku.watch.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [PendingLocation::class], version = 2, exportSchema = true)
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
            Room.databaseBuilder(context, AppDatabase::class.java, NAME)
                .addMigrations(MIGRATION_1_2)
                .build()

        /**
         * 測位の出どころを記録する列を足す (Issue #13)。
         *
         * **列を nullable にして既存行を埋めない。** 更新前に積まれた行は
         * 出どころが分からないだけで、Wi-Fi 測位だったわけではない。
         * 0 で埋めると「未報告」が「基地局測位」に化ける（#4 と同じ考え方）。
         *
         * **破壊的マイグレーションにはしない。** ここを落とすと、圏外で
         * 溜めていた位置がアプリ更新のたびに消える。
         */
        internal val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE pending_locations ADD COLUMN provider TEXT")
                db.execSQL("ALTER TABLE pending_locations ADD COLUMN hasAltitude INTEGER")
                db.execSQL("ALTER TABLE pending_locations ADD COLUMN hasSpeed INTEGER")
                db.execSQL("ALTER TABLE pending_locations ADD COLUMN hasBearing INTEGER")
            }
        }
    }
}
