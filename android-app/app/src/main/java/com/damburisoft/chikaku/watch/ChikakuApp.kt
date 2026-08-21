package com.damburisoft.chikaku.watch

import android.app.Application
import com.damburisoft.chikaku.watch.service.TrackingNotification
import com.damburisoft.chikaku.watch.work.UploadScheduler

class ChikakuApp : Application() {

    override fun onCreate() {
        super.onCreate()
        Graph.ensureInitialized(this)
        TrackingNotification.createChannel(this)
        // 圏外復帰やアプリ更新でキューが取り残されないよう、定期的な掃き出しを常に予約する。
        UploadScheduler.schedulePeriodicFlush(this)
    }
}
