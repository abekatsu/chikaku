package com.damburisoft.chikaku.watch.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.damburisoft.chikaku.watch.data.DeviceStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * **どの入口もロック解除前は何もしない (Issue #3)。** WorkManager は自身の DB を
 * 認証情報暗号化ストレージに置いており、Direct Boot 中に `getInstance` を呼ぶと
 * 初期化できずに落ちる。解除前に積んだ位置は解除後の [enqueueNow] が掃き出す。
 */
object UploadScheduler {

    private const val WORK_UPLOAD = "chikaku-upload"
    private const val WORK_WATCHDOG = "chikaku-watchdog"

    private val networkRequired = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    /** 位置をキューに積んだ直後に呼ぶ。通信できる状態になり次第送られる。 */
    fun enqueueNow(context: Context) {
        if (!DeviceStorage.isUserUnlocked(context)) return
        WorkManager.getInstance(context)
            // 実行中のワーカーはキューを空になるまで読み続けるので、重ねて起動しない。
            .enqueueUniqueWork(WORK_UPLOAD, ExistingWorkPolicy.KEEP, uploadRequest())
    }

    /**
     * バックオフの待ち時間を捨てて、いますぐ送り直す (Issue #15)。
     *
     * **なぜ [enqueueNow] と別に要るか。** 圏外や回線断が続くと `UploadWorker` の
     * 指数バックオフが伸び、WorkManager の上限（5時間）まで届く。回線が戻っても
     * このタイマーは解除されないため、見守りが数時間止まったままになる。
     * 実機では 2026-09-07 に、回線復帰から実際に届くまで最大 8 時間 17 分かかった。
     *
     * [enqueueNow] では直せない。あちらは `ExistingWorkPolicy.KEEP` なので、
     * **待っているだけのワークを見つけると何もせずに返る。** 毎時の
     * [WatchdogWorker] がキューを見て呼んでいたのに一度も救済にならなかったのは
     * これが理由で、実機のログでは 203 ミリ秒で成功して終わっていた。
     */
    suspend fun retryNow(context: Context) {
        if (!DeviceStorage.isUserUnlocked(context)) return
        val manager = WorkManager.getInstance(context)
        val existing = withContext(Dispatchers.IO) {
            // WorkManager の DB を読む。壊れていても見守りを止めたくないので、
            // 読めなければ「分からない」として通常の投入に落とす。
            runCatching { manager.getWorkInfosForUniqueWork(WORK_UPLOAD).get() }.getOrNull()
        }?.firstOrNull()
        val policy = if (shouldResetBackoff(existing?.state, existing?.runAttemptCount ?: 0)) {
            // REPLACE は取り消しと投入をひとまとめに行う。cancel してから
            // enqueue すると、その隙間で KEEP に弾かれうる。
            ExistingWorkPolicy.REPLACE
        } else {
            ExistingWorkPolicy.KEEP
        }
        manager.enqueueUniqueWork(WORK_UPLOAD, policy, uploadRequest())
    }

    /** アプリ起動時に予約する定期ヘルスチェック。 */
    fun schedulePeriodicFlush(context: Context) {
        if (!DeviceStorage.isUserUnlocked(context)) return
        val request = PeriodicWorkRequestBuilder<WatchdogWorker>(1, TimeUnit.HOURS)
            .setConstraints(networkRequired)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(WORK_WATCHDOG, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun cancelAll(context: Context) {
        if (!DeviceStorage.isUserUnlocked(context)) return
        WorkManager.getInstance(context).apply {
            cancelUniqueWork(WORK_UPLOAD)
            cancelUniqueWork(WORK_WATCHDOG)
        }
    }

    private fun uploadRequest() = OneTimeWorkRequestBuilder<UploadWorker>()
        .setConstraints(networkRequired)
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
        // Doze のメンテナンス窓を待たずに走らせる。電池の最適化から
        // 除外されていない端末で送信が数十分遅れるのを緩和する (Issue #4)。
        // 割当を使い切ったときは通常のワークとして積まれるだけで、失敗はしない。
        // ただしこれは緩和であって解決ではなく、除外の代わりにはならない。
        .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        .build()

    /**
     * 溜まっているワークを叩き起こすべきか (Issue #15)。
     *
     * **Android の API に依存しない純粋な関数にしてある。** ここを間違えると
     * 見守りが静かに止まるか、逆に送信中のワーカーを繰り返し中断するかの
     * どちらかになる。どちらも実機でしか気づけない類の壊れ方なので、
     * 判断だけは単体テストで固定する。
     *
     * @param state 既存の送信ワークの状態。まだ1つも無ければ null。
     * @param runAttemptCount その試行回数。0 なら一度も失敗していない。
     */
    fun shouldResetBackoff(state: WorkInfo.State?, runAttemptCount: Int): Boolean {
        // **実行中は絶対に触らない。** ワーカーはキューが空になるまで読み続けるので、
        // ここで置き換えると掃き出しの途中で毎回中断されることになる。
        if (state != WorkInfo.State.ENQUEUED) return false
        // 失敗していなければバックオフは付いていない。捨てるものが無い。
        return runAttemptCount > 0
    }
}
