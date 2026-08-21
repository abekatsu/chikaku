package com.damburisoft.chikaku.watch.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.damburisoft.chikaku.watch.BuildConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "chikaku_settings")

/**
 * 端末に保持する設定。位置履歴そのものは持たない（送信済みのものは即座に消える）。
 * バックアップ対象からは除外済み（res/xml/data_extraction_rules.xml）。
 */
class SettingsStore(context: Context) {

    private val store = context.applicationContext.dataStore

    val settings: Flow<Settings> = store.data.map { it.toSettings() }

    suspend fun current(): Settings = settings.first()

    suspend fun setConsented(consented: Boolean) = store.edit {
        it[KEY_CONSENTED] = consented
    }

    suspend fun savePairing(deviceId: String, deviceToken: String, familyId: String, deviceName: String) =
        store.edit {
            it[KEY_DEVICE_ID] = deviceId
            it[KEY_DEVICE_TOKEN] = deviceToken
            it[KEY_FAMILY_ID] = familyId
            it[KEY_DEVICE_NAME] = deviceName
        }

    suspend fun clearPairing() = store.edit {
        it.remove(KEY_DEVICE_ID)
        it.remove(KEY_DEVICE_TOKEN)
        it.remove(KEY_FAMILY_ID)
        it.remove(KEY_DEVICE_NAME)
        it.remove(KEY_LAST_QUEUED_LAT)
        it.remove(KEY_LAST_QUEUED_LNG)
        it.remove(KEY_LAST_QUEUED_AT)
        it.remove(KEY_LAST_SENT_AT)
        it[KEY_TRACKING_ENABLED] = false
    }

    suspend fun setServerBaseUrl(url: String) = store.edit {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) it.remove(KEY_SERVER_URL) else it[KEY_SERVER_URL] = trimmed
    }

    suspend fun setTrackingEnabled(enabled: Boolean) = store.edit {
        it[KEY_TRACKING_ENABLED] = enabled
    }

    suspend fun setLastQueued(lat: Double, lng: Double, at: Long) = store.edit {
        it[KEY_LAST_QUEUED_LAT] = lat
        it[KEY_LAST_QUEUED_LNG] = lng
        it[KEY_LAST_QUEUED_AT] = at
    }

    suspend fun setLastSentAt(at: Long) = store.edit {
        it[KEY_LAST_SENT_AT] = at
    }

    private fun Preferences.toSettings() = Settings(
        consented = this[KEY_CONSENTED] ?: false,
        deviceId = this[KEY_DEVICE_ID],
        deviceToken = this[KEY_DEVICE_TOKEN],
        familyId = this[KEY_FAMILY_ID],
        deviceName = this[KEY_DEVICE_NAME],
        serverBaseUrl = this[KEY_SERVER_URL] ?: BuildConfig.DEFAULT_SERVER_BASE_URL,
        trackingEnabled = this[KEY_TRACKING_ENABLED] ?: false,
        lastQueuedLat = this[KEY_LAST_QUEUED_LAT],
        lastQueuedLng = this[KEY_LAST_QUEUED_LNG],
        lastQueuedAt = this[KEY_LAST_QUEUED_AT] ?: 0L,
        lastSentAt = this[KEY_LAST_SENT_AT] ?: 0L,
    )

    private companion object {
        val KEY_CONSENTED = booleanPreferencesKey("consented")
        val KEY_DEVICE_ID = stringPreferencesKey("device_id")
        val KEY_DEVICE_TOKEN = stringPreferencesKey("device_token")
        val KEY_FAMILY_ID = stringPreferencesKey("family_id")
        val KEY_DEVICE_NAME = stringPreferencesKey("device_name")
        val KEY_SERVER_URL = stringPreferencesKey("server_base_url")
        val KEY_TRACKING_ENABLED = booleanPreferencesKey("tracking_enabled")
        val KEY_LAST_QUEUED_LAT = doublePreferencesKey("last_queued_lat")
        val KEY_LAST_QUEUED_LNG = doublePreferencesKey("last_queued_lng")
        val KEY_LAST_QUEUED_AT = longPreferencesKey("last_queued_at")
        val KEY_LAST_SENT_AT = longPreferencesKey("last_sent_at")
    }
}

data class Settings(
    val consented: Boolean,
    val deviceId: String?,
    val deviceToken: String?,
    val familyId: String?,
    val deviceName: String?,
    val serverBaseUrl: String,
    val trackingEnabled: Boolean,
    val lastQueuedLat: Double?,
    val lastQueuedLng: Double?,
    val lastQueuedAt: Long,
    val lastSentAt: Long,
) {
    val isPaired: Boolean get() = !deviceId.isNullOrEmpty() && !deviceToken.isNullOrEmpty()
}
