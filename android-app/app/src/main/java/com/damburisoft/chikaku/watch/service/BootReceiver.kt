package com.damburisoft.chikaku.watch.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.damburisoft.chikaku.watch.Graph
import com.damburisoft.chikaku.watch.data.DeviceStorage
import com.damburisoft.chikaku.watch.work.UploadScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 端末の再起動やアプリ更新のあと、見守りを自動で再開する。
 * 親に「アプリを開き直して」と頼まなくて済むようにするための要。
 *
 * **`LOCKED_BOOT_COMPLETED` も受ける (Issue #3)。** `BOOT_COMPLETED` は親が
 * 画面ロックを解除するまで配信されない。再起動して解除せずに出かけると、
 * その間ずっとプロセスすら存在しない。本番では再起動の1分後に走り始め、
 * 走り終えた35分後にようやく最初の1点が届いた。
 *
 * 両方が届く（起動時と解除時）。[LocationTrackingService] の起動は冪等なので
 * 二重に叩いても問題にならない。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in HANDLED_ACTIONS) return

        val appContext = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                Graph.ensureInitialized(appContext)
                if (DeviceStorage.isUserUnlocked(appContext)) {
                    // 旧バージョンからの引き継ぎは、解除されて初めて可能になる。
                    Graph.onUserUnlocked(appContext)
                }
                val settings = Graph.settings.current()
                if (settings.consented && settings.isPaired && settings.trackingEnabled) {
                    try {
                        LocationTrackingService.start(appContext)
                    } catch (e: Exception) {
                        // Android 12+ ではバックグラウンドからの Foreground Service 起動が
                        // 拒否されることがある。その場合は Watchdog に拾わせる。
                        Log.w(TAG, "起動直後にサービスを開始できませんでした", e)
                    }
                }
                // ロック解除前は何もしない（[UploadScheduler] を参照）。
                UploadScheduler.schedulePeriodicFlush(appContext)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "BootReceiver"

        val HANDLED_ACTIONS = setOf(
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
        )
    }
}
