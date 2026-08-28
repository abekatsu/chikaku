package com.damburisoft.chikaku.watch.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.deviceProtectedDataStoreFile
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import com.damburisoft.chikaku.watch.BuildConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * 端末に保持する設定。位置履歴そのものは持たない（送信済みのものは即座に消える）。
 * バックアップ対象からは除外済み（res/xml/data_extraction_rules.xml）。
 *
 * **保存先が2つに分かれている (Issue #3)。**
 *
 * | | 置き場所 | 読めるタイミング |
 * |---|---|---|
 * | `device_token` 以外 | 端末保護ストレージ | 常時（ロック解除前を含む） |
 * | `device_token` | 認証情報暗号化ストレージ | ロック解除後のみ |
 *
 * ロック解除前に見守りを再開するには、同意・ペアリング済みか・見守りが有効かを
 * 読めなければならない。一方トークンは送信にしか要らず、送信はロック解除後に
 * WorkManager が行う。**送信に要るものだけを資格情報の保護下に残す**ことで、
 * Direct Boot 対応と引き換えに秘密の露出が増えるのを避けている。
 * 詳しくは [DeviceStorage]。
 */
class SettingsStore(context: Context) {

    private val app = context.applicationContext

    /**
     * **`preferencesDataStoreFile` を端末保護 Context に対して呼んではいけない。**
     * その実装は `File(this.applicationContext.filesDir, ...)` であり、
     * せっかく切り替えた Context を捨てて認証情報暗号化ストレージへ戻す。
     * 実機で一度これを踏み、設定が丸ごと旧来の場所に置かれたままになった。
     * 端末保護ストレージ用には専用の `deviceProtectedDataStoreFile` を使う。
     */
    private val deviceStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
        produceFile = { app.deviceProtectedDataStoreFile(DEVICE_STORE_FILE) },
    )

    /**
     * ロック解除前に触ると失敗するため、実際に必要になるまで作らない。
     * 生成そのものはファイルを開かないが、うっかり早く読まないよう意図を残しておく。
     */
    private val credentialStore: DataStore<Preferences> by lazy {
        PreferenceDataStoreFactory.create(
            produceFile = { app.preferencesDataStoreFile(CREDENTIAL_STORE) },
        )
    }

    val settings: Flow<Settings> = deviceStore.data.map { it.toSettings() }

    suspend fun current(): Settings = settings.first()

    /**
     * 端末トークン。**ロック解除前は null を返す。**
     * 呼び出し側（[ApiClient]）はこれを「認証できない」として扱う。
     * ロック解除前に送信する経路は存在しないため、正常系でここが null になることはない。
     */
    suspend fun deviceToken(): String? {
        if (!DeviceStorage.isUserUnlocked(app)) return null
        return credentialStore.data.first()[KEY_DEVICE_TOKEN]
    }

    suspend fun setConsented(consented: Boolean) = deviceStore.edit {
        it[KEY_CONSENTED] = consented
    }

    suspend fun savePairing(deviceId: String, deviceToken: String, familyId: String, deviceName: String) {
        // 先にトークンを書く。逆順だと、途中で失敗したときに
        // 「ペアリング済みなのに送れない」状態が残る。
        credentialStore.edit { it[KEY_DEVICE_TOKEN] = deviceToken }
        deviceStore.edit {
            it[KEY_DEVICE_ID] = deviceId
            it[KEY_FAMILY_ID] = familyId
            it[KEY_DEVICE_NAME] = deviceName
        }
    }

    suspend fun clearPairing() {
        deviceStore.edit {
            it.remove(KEY_DEVICE_ID)
            it.remove(KEY_FAMILY_ID)
            it.remove(KEY_DEVICE_NAME)
            it.remove(KEY_LAST_QUEUED_LAT)
            it.remove(KEY_LAST_QUEUED_LNG)
            it.remove(KEY_LAST_QUEUED_AT)
            it.remove(KEY_LAST_SENT_AT)
            it[KEY_TRACKING_ENABLED] = false
        }
        credentialStore.edit { it.remove(KEY_DEVICE_TOKEN) }
    }

    suspend fun setServerBaseUrl(url: String) = deviceStore.edit {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) it.remove(KEY_SERVER_URL) else it[KEY_SERVER_URL] = trimmed
    }

    suspend fun setTrackingEnabled(enabled: Boolean) = deviceStore.edit {
        it[KEY_TRACKING_ENABLED] = enabled
    }

    suspend fun setLastQueued(lat: Double, lng: Double, at: Long) = deviceStore.edit {
        it[KEY_LAST_QUEUED_LAT] = lat
        it[KEY_LAST_QUEUED_LNG] = lng
        it[KEY_LAST_QUEUED_AT] = at
    }

    suspend fun setLastSentAt(at: Long) = deviceStore.edit {
        it[KEY_LAST_SENT_AT] = at
    }

    /**
     * 旧バージョン（すべてを認証情報暗号化ストレージに置いていた頃）の値を取り込む。
     * トークンだけを資格情報側へ残し、残りを端末保護ストレージへ移す。
     *
     * キーを1つずつ書き写さず丸ごと回しているのは、あとから設定項目が増えたときに
     * ここの更新を忘れて静かに欠落するのを防ぐため。
     */
    @Suppress("UNCHECKED_CAST")
    internal suspend fun importLegacy(legacy: Preferences) {
        deviceStore.edit { out ->
            legacy.asMap().forEach { (key, value) ->
                if (key.name != KEY_DEVICE_TOKEN.name) out[key as Preferences.Key<Any>] = value
            }
        }
        val token = legacy[KEY_DEVICE_TOKEN] ?: return
        credentialStore.edit { it[KEY_DEVICE_TOKEN] = token }
    }

    private fun Preferences.toSettings() = Settings(
        consented = this[KEY_CONSENTED] ?: false,
        deviceId = this[KEY_DEVICE_ID],
        familyId = this[KEY_FAMILY_ID],
        deviceName = this[KEY_DEVICE_NAME],
        serverBaseUrl = this[KEY_SERVER_URL] ?: BuildConfig.DEFAULT_SERVER_BASE_URL,
        trackingEnabled = this[KEY_TRACKING_ENABLED] ?: false,
        lastQueuedLat = this[KEY_LAST_QUEUED_LAT],
        lastQueuedLng = this[KEY_LAST_QUEUED_LNG],
        lastQueuedAt = this[KEY_LAST_QUEUED_AT] ?: 0L,
        lastSentAt = this[KEY_LAST_SENT_AT] ?: 0L,
    )

    companion object {
        /** 端末保護ストレージ側。旧バージョンでは同じ名前で CE 側に置かれていた。 */
        internal const val DEVICE_STORE = "chikaku_settings"

        /** `deviceProtectedDataStoreFile` は拡張子を補わないので自分で付ける。 */
        internal const val DEVICE_STORE_FILE = "$DEVICE_STORE.preferences_pb"

        /** 認証情報暗号化ストレージ側。トークンだけが入る。 */
        internal const val CREDENTIAL_STORE = "chikaku_credentials"

        private val KEY_CONSENTED = booleanPreferencesKey("consented")
        private val KEY_DEVICE_ID = stringPreferencesKey("device_id")
        private val KEY_DEVICE_TOKEN = stringPreferencesKey("device_token")
        private val KEY_FAMILY_ID = stringPreferencesKey("family_id")
        private val KEY_DEVICE_NAME = stringPreferencesKey("device_name")
        private val KEY_SERVER_URL = stringPreferencesKey("server_base_url")
        private val KEY_TRACKING_ENABLED = booleanPreferencesKey("tracking_enabled")
        private val KEY_LAST_QUEUED_LAT = doublePreferencesKey("last_queued_lat")
        private val KEY_LAST_QUEUED_LNG = doublePreferencesKey("last_queued_lng")
        private val KEY_LAST_QUEUED_AT = longPreferencesKey("last_queued_at")
        private val KEY_LAST_SENT_AT = longPreferencesKey("last_sent_at")
    }
}

data class Settings(
    val consented: Boolean,
    val deviceId: String?,
    val familyId: String?,
    val deviceName: String?,
    val serverBaseUrl: String,
    val trackingEnabled: Boolean,
    val lastQueuedLat: Double?,
    val lastQueuedLng: Double?,
    val lastQueuedAt: Long,
    val lastSentAt: Long,
) {
    /**
     * **トークンの有無では判定しない。** トークンはロック解除前に読めず、
     * ここで参照すると Direct Boot 中に「未ペアリング」に見えてしまう。
     * 両者は [SettingsStore.savePairing] / [SettingsStore.clearPairing] で
     * 必ず一緒に書き換わる。
     */
    val isPaired: Boolean get() = !deviceId.isNullOrEmpty()
}
