package com.damburisoft.chikaku.watch.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.damburisoft.chikaku.watch.Graph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * ヘルスチェックのアラームを受ける。
 *
 * **Foreground Service を直接 PendingIntent の宛先にしない。** サービスが
 * 死んでいる状態でアラームが発火すると、Android 12+ の「バックグラウンドからの
 * Foreground Service 起動」制限に触れ、アプリ側で捕捉できない例外になりうる。
 * レシーバなら制限を受けず、起動の失敗もここで握り潰せる（BootReceiver と同じ考え方）。
 */
class HeartbeatReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return

        val appContext = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                Graph.ensureInitialized(appContext)
                val settings = Graph.settings.current()
                if (!settings.consented || !settings.isPaired || !settings.trackingEnabled) {
                    // 見守りが止まっている。次の予約もしない。
                    return@launch
                }

                val remaining = Graph.locations.millisUntilHeartbeat()
                if (remaining > 0) {
                    // 移動によって既に送信済み。無駄な測位をせず残り時間で取り直す。
                    HeartbeatScheduler.schedule(appContext, remaining)
                    return@launch
                }

                // 次を先に予約する。この後の起動に失敗しても連鎖を切らない。
                HeartbeatScheduler.schedule(appContext)
                try {
                    LocationTrackingService.heartbeat(appContext)
                } catch (e: Exception) {
                    // サービスが死んでおり、かつ背景からの起動を拒否された。
                    // WatchdogWorker が次の機会に拾う。
                    Log.w(TAG, "ヘルスチェックのために起動できませんでした", e)
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION = "com.damburisoft.chikaku.watch.action.HEARTBEAT"
        private const val TAG = "HeartbeatReceiver"
    }
}
