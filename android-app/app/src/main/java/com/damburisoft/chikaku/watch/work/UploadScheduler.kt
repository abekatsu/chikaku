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
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object UploadScheduler {

    private const val WORK_UPLOAD = "chikaku-upload"
    private const val WORK_WATCHDOG = "chikaku-watchdog"

    private val networkRequired = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    /** 位置をキューに積んだ直後に呼ぶ。通信できる状態になり次第送られる。 */
    fun enqueueNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(networkRequired)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            // Doze のメンテナンス窓を待たずに走らせる。電池の最適化から
            // 除外されていない端末で送信が数十分遅れるのを緩和する (Issue #4)。
            // 割当を使い切ったときは通常のワークとして積まれるだけで、失敗はしない。
            // ただしこれは緩和であって解決ではなく、除外の代わりにはならない。
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(context)
            // 実行中のワーカーはキューを空になるまで読み続けるので、重ねて起動しない。
            .enqueueUniqueWork(WORK_UPLOAD, ExistingWorkPolicy.KEEP, request)
    }

    /** アプリ起動時に予約する定期ヘルスチェック。 */
    fun schedulePeriodicFlush(context: Context) {
        val request = PeriodicWorkRequestBuilder<WatchdogWorker>(1, TimeUnit.HOURS)
            .setConstraints(networkRequired)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(WORK_WATCHDOG, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun cancelAll(context: Context) {
        WorkManager.getInstance(context).apply {
            cancelUniqueWork(WORK_UPLOAD)
            cancelUniqueWork(WORK_WATCHDOG)
        }
    }
}
