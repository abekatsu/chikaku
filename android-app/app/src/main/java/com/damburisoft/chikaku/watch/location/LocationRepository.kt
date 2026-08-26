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
        val distance = if (lastLat == null || lastLng == null) {
            null
        } else {
            val results = FloatArray(1)
            Location.distanceBetween(lastLat, lastLng, location.latitude, location.longitude, results)
            results[0]
        }
        return shouldSend(distance, now - current.lastQueuedAt)
    }

    /**
     * 次のヘルスチェックまでの残り時間（ミリ秒）。0 以下なら今すぐ送るべき。
     * [com.damburisoft.chikaku.watch.service.HeartbeatScheduler] が
     * 無駄な測位を避けるために参照する。
     */
    suspend fun millisUntilHeartbeat(now: Long = System.currentTimeMillis()): Long {
        val current = settings.current()
        if (current.lastQueuedAt == 0L) return 0
        return LocationTuning.HEARTBEAT_INTERVAL_MILLIS - (now - current.lastQueuedAt)
    }

    companion object {
        /**
         * 送信するかどうかの判定。
         *
         * **Android の API に依存しない純粋な関数にしてある。** この判定は
         * 見守りの中核だが、以前ここに「静止中はそもそも呼ばれない」という
         * 欠陥があり、実機で5時間の空白ができるまで誰も気づかなかった。
         * 単体テストで固定できる形にしておく。
         *
         * @param distanceFromLastQueuedMeters 前回キュー投入地点からの距離。
         *   まだ1件も送っていなければ null。
         * @param millisSinceLastQueued 前回キュー投入からの経過時間。
         */
        fun shouldSend(distanceFromLastQueuedMeters: Float?, millisSinceLastQueued: Long): Boolean {
            // 初回は無条件で送る。
            if (distanceFromLastQueuedMeters == null) return true
            // 動いていなくても、この間隔を超えたら生存を知らせる。
            if (millisSinceLastQueued >= LocationTuning.HEARTBEAT_INTERVAL_MILLIS) return true
            return distanceFromLastQueuedMeters >= LocationTuning.SEND_DISTANCE_THRESHOLD_METERS
        }
    }

    private fun batteryLevel(): Int =
        context.getSystemService(BatteryManager::class.java)
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            ?: -1
}
