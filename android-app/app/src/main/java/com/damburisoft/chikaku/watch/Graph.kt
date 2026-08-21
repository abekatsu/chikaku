package com.damburisoft.chikaku.watch

import android.annotation.SuppressLint
import android.content.Context
import com.damburisoft.chikaku.watch.data.ApiClient
import com.damburisoft.chikaku.watch.data.AppDatabase
import com.damburisoft.chikaku.watch.data.SettingsStore
import com.damburisoft.chikaku.watch.location.LocationRepository

/**
 * 依存を1か所にまとめる簡易サービスロケータ。
 * Service / Worker / ViewModel のいずれからも同じインスタンスを引くために使う。
 * DIライブラリを入れるほどの規模ではないためこの形にしている。
 */
object Graph {

    @Volatile
    private var initialized = false

    lateinit var settings: SettingsStore
        private set
    lateinit var database: AppDatabase
        private set
    lateinit var api: ApiClient
        private set
    // 保持しているのは applicationContext のみなのでリークにはならない。
    @SuppressLint("StaticFieldLeak")
    lateinit var locations: LocationRepository
        private set

    /**
     * Application からだけでなく Worker からも呼ばれる。
     * プロセスが Worker のためだけに起こされた場合でも初期化を保証するため。
     */
    @Synchronized
    fun ensureInitialized(context: Context) {
        if (initialized) return
        val app = context.applicationContext
        settings = SettingsStore(app)
        database = AppDatabase.create(app)
        api = ApiClient(settings)
        locations = LocationRepository(app, database.pendingLocationDao(), settings)
        initialized = true
    }
}
