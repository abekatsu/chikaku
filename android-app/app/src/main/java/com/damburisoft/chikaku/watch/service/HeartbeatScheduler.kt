package com.damburisoft.chikaku.watch.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import com.damburisoft.chikaku.watch.location.LocationTuning

/**
 * 動きがないときのヘルスチェックを時計で駆動する。
 *
 * **なぜ要るか。** 測位パラメータが `setMinUpdateDistanceMeters(50m)` なので、
 * 静止している間は測位コールバックが1度も発生しない。送信可否の判定
 * ([com.damburisoft.chikaku.watch.location.LocationRepository.shouldSend]) は
 * そのコールバックの中にあるため、**判定そのものに到達できなかった**。
 * 結果として「動きがなくても30分に1回は送る」が実装されていなかった。
 * 本番では自宅にいる間に5時間の空白ができ、その裏で見守りが止まっていた
 * ことに誰も気づけなかった。
 *
 * **なぜ WorkManager ではなく AlarmManager か。** WorkManager の定期実行は
 * Doze 中はメンテナンス窓まで繰り延べられ、30分間隔の保証にならない。
 * `setAndAllowWhileIdle` は Doze 中でも発火する。
 *
 * **なぜ exact alarm を使わないか。** 「おおむね30分ごと」で十分であり、
 * `setExactAndAllowWhileIdle` は Android 12+ で `SCHEDULE_EXACT_ALARM` を
 * 要求する。見守りのために追加の権限を親に求める価値はない。
 */
object HeartbeatScheduler {

    private const val TAG = "HeartbeatScheduler"
    private const val REQUEST_CODE = 1002

    /**
     * 次のヘルスチェックを予約する。既存の予約があれば置き換わる。
     *
     * @param delayMillis 何ミリ秒後に発火させるか。前回送信から時間が
     *   経っていない場合、呼び出し側が残り時間を渡して重複発火を避ける。
     */
    fun schedule(context: Context, delayMillis: Long = LocationTuning.HEARTBEAT_INTERVAL_MILLIS) {
        val alarm = context.getSystemService(AlarmManager::class.java) ?: return
        // 端末の時計がずれても影響を受けないよう、起動からの経過時間で指定する。
        val triggerAt = SystemClock.elapsedRealtime() + delayMillis.coerceAtLeast(0)
        try {
            alarm.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerAt,
                pendingIntent(context),
            )
        } catch (e: SecurityException) {
            // 端末側の制限で予約できないことがある。WatchdogWorker が拾う。
            Log.w(TAG, "ヘルスチェックを予約できませんでした", e)
        }
    }

    fun cancel(context: Context) {
        val alarm = context.getSystemService(AlarmManager::class.java) ?: return
        alarm.cancel(pendingIntent(context))
    }

    /**
     * 宛先は [HeartbeatReceiver]。Foreground Service を直接指さないのは、
     * サービスが死んでいるときに背景からの起動制限で捕捉できない例外に
     * なりうるため（[HeartbeatReceiver] の説明を参照）。
     */
    private fun pendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, HeartbeatReceiver::class.java)
                .setAction(HeartbeatReceiver.ACTION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}
