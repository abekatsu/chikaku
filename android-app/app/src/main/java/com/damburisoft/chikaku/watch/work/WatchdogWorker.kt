package com.damburisoft.chikaku.watch.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.damburisoft.chikaku.watch.Graph
import com.damburisoft.chikaku.watch.service.HeartbeatScheduler
import com.damburisoft.chikaku.watch.service.LocationTrackingService

/**
 * 定期ヘルスチェック（CLAUDE.md §2.1）。
 * OSに Foreground Service を落とされていた場合に気付いて起こし直し、
 * ついでに溜まっているキューの掃き出しも促す。
 *
 * **キューの掃き出しは「促す」では足りない (Issue #15)。** 回線が戻っても
 * 送信ワークのバックオフが残り続けるため、ここが唯一の定期的な解除機会になる。
 * 即時の復帰は [com.damburisoft.chikaku.watch.service.LocationTrackingService] が
 * 張るネットワークの監視が受け持ち、こちらはそれが取りこぼしたときの受け皿。
 */
class WatchdogWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        Graph.ensureInitialized(applicationContext)
        val settings = Graph.settings.current()
        if (!settings.consented || !settings.isPaired) return Result.success()

        if (settings.trackingEnabled && !LocationTrackingService.isRunning) {
            Log.i(TAG, "見守りが止まっていたため再開します")
            LocationTrackingService.start(applicationContext)
        } else if (settings.trackingEnabled) {
            // サービスは生きている。ヘルスチェックの予約だけが失われている場合に
            // 備えて張り直す。残り時間を渡すのは、既に予約が生きているときに
            // 発火を先送りしてしまわないため（0 以下なら即時発火になる）。
            HeartbeatScheduler.schedule(
                applicationContext,
                Graph.locations.millisUntilHeartbeat(),
            )
        }
        if (Graph.database.pendingLocationDao().count() > 0) {
            // **enqueueNow ではなく retryNow (Issue #15)。** 回線断でバックオフが
            // 伸びたワークは `ExistingWorkPolicy.KEEP` に弾かれるため、
            // ここで呼んでいたのに一度も救済にならなかった。毎時のこの呼び出しが
            // バックオフを捨てることで、沈黙は最大1時間に収まる。
            UploadScheduler.retryNow(applicationContext)
        }
        return Result.success()
    }

    private companion object {
        const val TAG = "WatchdogWorker"
    }
}
