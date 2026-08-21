package com.damburisoft.chikaku.watch.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.damburisoft.chikaku.watch.Graph
import com.damburisoft.chikaku.watch.work.UploadScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 端末の再起動やアプリ更新のあと、見守りを自動で再開する。
 * 親に「アプリを開き直して」と頼まなくて済むようにするための要。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }

        val appContext = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                Graph.ensureInitialized(appContext)
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
                UploadScheduler.schedulePeriodicFlush(appContext)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "BootReceiver"
    }
}
