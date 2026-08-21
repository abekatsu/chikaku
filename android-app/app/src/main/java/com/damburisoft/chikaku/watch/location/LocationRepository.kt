package com.damburisoft.chikaku.watch.location

import android.content.Context
import android.location.Location
import android.os.BatteryManager
import com.damburisoft.chikaku.watch.data.PendingLocation
import com.damburisoft.chikaku.watch.data.PendingLocationDao
import com.damburisoft.chikaku.watch.data.SettingsStore
import kotlinx.coroutines.flow.Flow

/**
 * 「送るべきか」を判断してキューに積むところまでを受け持つ。
 * 実際の送信は [com.damburisoft.chikaku.watch.work.UploadWorker]。
 */
class LocationRepository(
    private val context: Context,
    private val dao: PendingLocationDao,
    private val settings: SettingsStore,
) {

    val pendingCount: Flow<Int> = dao.countFlow()

    /**
     * 測位結果を受け取り、閾値を超えていればキューに積む。
     * @param force ユーザーが「今すぐ送信」を押した場合など、閾値判定を飛ばしたいとき
     * @return キューに積んだら true
     */
    suspend fun onLocationUpdate(
        location: Location,
        now: Long = System.currentTimeMillis(),
        force: Boolean = false,
    ): Boolean {
        if (location.hasAccuracy() && location.accuracy > LocationTuning.MAX_ACCEPTABLE_ACCURACY_METERS) {
            return false
        }
        if (!force && !shouldSend(location, now)) return false

        dao.insert(
            PendingLocation(
                lat = location.latitude,
                lng = location.longitude,
                accuracy = if (location.hasAccuracy()) location.accuracy else -1f,
                // 端末時計がずれていても順序が壊れないよう、測位時刻そのものを使う。
                recordedAt = if (location.time > 0) location.time else now,
                batteryLevel = batteryLevel(),
            )
        )
        dao.trimTo(LocationTuning.MAX_QUEUE_SIZE)
        settings.setLastQueued(location.latitude, location.longitude, now)
        return true
    }

    private suspend fun shouldSend(location: Location, now: Long): Boolean {
        val current = settings.current()
        val lastLat = current.lastQueuedLat
        val lastLng = current.lastQueuedLng
        // 初回、またはヘルスチェック間隔を超えたら無条件で送る。
        if (lastLat == null || lastLng == null) return true
        if (now - current.lastQueuedAt >= LocationTuning.HEARTBEAT_INTERVAL_MILLIS) return true

        val results = FloatArray(1)
        Location.distanceBetween(lastLat, lastLng, location.latitude, location.longitude, results)
        return results[0] >= LocationTuning.SEND_DISTANCE_THRESHOLD_METERS
    }

    private fun batteryLevel(): Int =
        context.getSystemService(BatteryManager::class.java)
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            ?: -1
}
