package com.damburisoft.chikaku.watch.data

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import com.damburisoft.chikaku.watch.location.LocationTuning
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * 旧バージョンの保存先（すべて認証情報暗号化ストレージ）から、
 * 端末保護ストレージへの引き継ぎ (Issue #3)。
 *
 * **これが無いと、更新した瞬間に親の端末のペアリングが消える。** 招待コードを
 * 発行し直して親に入力してもらうことになるので、黙って落とすわけにはいかない。
 *
 * **ロック解除前は何もしない。** 移す元が読めないため。旧ファイルが残っている
 * 限り何度でも試すので、いつ解除されても取りこぼさない。
 */
internal object StorageMigration {

    private const val TAG = "StorageMigration"

    /**
     * 引き継ぎを実行すべきか。Android に依存しない純粋な判定。
     *
     * 移動先が既にあるかどうかは条件に入れない。**入れると引き継ぎが永久に
     * 走らなくなる経路ができる。** ロック解除前にプロセスが起きると、
     * 移動先のファイルは（中身が空でも）先に作られうるため。
     */
    fun shouldImport(userUnlocked: Boolean, legacyExists: Boolean): Boolean =
        userUnlocked && legacyExists

    /**
     * 必要なら引き継ぐ。通常は旧ファイルの有無を見るだけで帰る。
     *
     * @param settings 引き継ぎ先。**新しく作らず、動いているインスタンスに書く。**
     *   別に作ると、こちらの書き込みが向こうのキャッシュに反映されない。
     */
    @Synchronized
    fun runIfNeeded(context: Context, settings: SettingsStore, database: AppDatabase) {
        val app = context.applicationContext
        val unlocked = DeviceStorage.isUserUnlocked(app)
        importPreferences(app, settings, unlocked)
        importPendingLocations(app, database, unlocked)
    }

    private fun importPreferences(app: Context, settings: SettingsStore, unlocked: Boolean) {
        val legacyFile = app.preferencesDataStoreFile(SettingsStore.DEVICE_STORE)
        if (!shouldImport(unlocked, legacyFile.exists())) return

        // 旧ファイルはこの直後に消すため、DataStore の「1ファイル1インスタンス」制約に
        // 触れない。読むためだけの使い捨て。
        val scope = CoroutineScope(Job() + Dispatchers.IO)
        try {
            runBlocking {
                val legacy = PreferenceDataStoreFactory.create(scope = scope) { legacyFile }
                settings.importLegacy(legacy.data.first())
            }
            deleteWithSidecars(legacyFile)
            Log.i(TAG, "設定を端末保護ストレージへ引き継ぎました")
        } catch (e: Exception) {
            // 消さずに残す。次の起動でやり直せる。
            Log.w(TAG, "設定を引き継げませんでした", e)
        } finally {
            scope.cancel()
        }
    }

    private fun importPendingLocations(app: Context, database: AppDatabase, unlocked: Boolean) {
        if (!shouldImport(unlocked, app.getDatabasePath(AppDatabase.NAME).exists())) return

        // ファイルごと動かさず1件ずつ入れ直すのは、ロック解除前に積まれた行が
        // 移動先に既にありうるため。上書きすると、その空白の間に測位できた
        // ぶんを捨てることになる。
        val legacy = Room.databaseBuilder(app, AppDatabase::class.java, AppDatabase.NAME).build()
        try {
            runBlocking {
                val dao = database.pendingLocationDao()
                legacy.pendingLocationDao().oldest(LocationTuning.MAX_QUEUE_SIZE).forEach {
                    dao.insert(it.copy(id = 0))
                }
                dao.trimTo(LocationTuning.MAX_QUEUE_SIZE)
            }
            legacy.close()
            app.deleteDatabase(AppDatabase.NAME)
            Log.i(TAG, "送信待ちの位置を端末保護ストレージへ引き継ぎました")
        } catch (e: Exception) {
            legacy.close()
            Log.w(TAG, "送信待ちの位置を引き継げませんでした", e)
        }
    }

    /** DataStore は書き込み中に `.tmp` を作る。取り残すと次回の判定が狂う。 */
    private fun deleteWithSidecars(file: File) {
        file.delete()
        File(file.parentFile, "${file.name}.tmp").delete()
    }
}
