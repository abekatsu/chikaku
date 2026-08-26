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
            UploadScheduler.enqueueNow(applicationContext)
        }
        return Result.success()
    }

    private companion object {
        const val TAG = "WatchdogWorker"
    }
}
