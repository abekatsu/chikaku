package com.damburisoft.chikaku.watch

import android.app.Application
import com.damburisoft.chikaku.watch.data.DeviceStorage
import com.damburisoft.chikaku.watch.service.TrackingNotification
import com.damburisoft.chikaku.watch.work.UploadScheduler

class ChikakuApp : Application() {

    override fun onCreate() {
        super.onCreate()
        Graph.ensureInitialized(this)

        // **ロック解除前にも通る (Issue #3)。** Direct Boot 対応の
        // レシーバがプロセスを起こすと、そのときも Application は作られる。
        // WorkManager は自身の DB を認証情報暗号化ストレージに置いており、
        // 解除前に触ると初期化できずに落ちる。通知チャンネルも同様に扱えない。
        // どちらも解除後に BootReceiver とサービスが改めて用意する。
        if (!DeviceStorage.isUserUnlocked(this)) return

        TrackingNotification.createChannel(this)
        // 圏外復帰やアプリ更新でキューが取り残されないよう、定期的な掃き出しを常に予約する。
        UploadScheduler.schedulePeriodicFlush(this)
    }
}
