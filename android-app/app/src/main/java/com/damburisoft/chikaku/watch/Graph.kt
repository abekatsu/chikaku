package com.damburisoft.chikaku.watch

import android.annotation.SuppressLint
import android.content.Context
import com.damburisoft.chikaku.watch.data.ApiClient
import com.damburisoft.chikaku.watch.data.AppDatabase
import com.damburisoft.chikaku.watch.data.DeviceStorage
import com.damburisoft.chikaku.watch.data.SettingsStore
import com.damburisoft.chikaku.watch.data.StorageMigration
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
     * Application からだけでなく Worker や Receiver からも呼ばれる。
     * プロセスがそれらのためだけに起こされた場合でも初期化を保証するため。
     *
     * **ロック解除前にも呼ばれる (Issue #3)。** ここで組み立てるものは
     * すべて端末保護ストレージの上に載っており、解除を待たずに使える。
     */
    @Synchronized
    fun ensureInitialized(context: Context) {
        if (initialized) return
        val app = context.applicationContext
        settings = SettingsStore(app)
        database = AppDatabase.create(DeviceStorage.deviceProtected(app))
        api = ApiClient(settings)
        locations = LocationRepository(app, database.pendingLocationDao(), settings)
        initialized = true
        StorageMigration.runIfNeeded(app, settings, database)
    }

    /**
     * 画面ロックが解除されたとき。旧バージョンからの引き継ぎは、
     * 移す元が読めるこの時点で初めて可能になる。
     *
     * インスタンスは作り直さない。作り直すと、既に流れているデータを
     * 購読している側（常駐通知の更新など）が古い参照を掴んだまま残る。
     * 引き継ぎ先はここで持っているものと同じなので、その必要もない。
     */
    @Synchronized
    fun onUserUnlocked(context: Context) {
        ensureInitialized(context)
        StorageMigration.runIfNeeded(context.applicationContext, settings, database)
    }
}
