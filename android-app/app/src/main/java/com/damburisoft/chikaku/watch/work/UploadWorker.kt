package com.damburisoft.chikaku.watch.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.damburisoft.chikaku.watch.Graph
import com.damburisoft.chikaku.watch.data.ApiResult
import com.damburisoft.chikaku.watch.data.LocationPayload
import com.damburisoft.chikaku.watch.data.PendingLocation
import com.damburisoft.chikaku.watch.location.LocationTuning
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * キューに溜まった位置情報をサーバーへ流し込む。
 * 圏外・サーバー停止時は [Result.retry] を返し、WorkManager の指数バックオフに任せる。
 */
class UploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        Graph.ensureInitialized(applicationContext)
        val dao = Graph.database.pendingLocationDao()
        val settings = Graph.settings

        val current = settings.current()
        if (!current.isPaired) {
            // 未ペアリング。溜めておいても宛先がないので捨てる。
            dao.clear()
            return Result.success()
        }
        val deviceId = current.deviceId ?: return Result.success()

        while (true) {
            val batch = dao.oldest(BATCH_SIZE)
            if (batch.isEmpty()) return Result.success()

            for (row in batch) {
                when (val result = Graph.api.postLocation(row.toPayload(deviceId))) {
                    is ApiResult.Success -> {
                        dao.delete(row.id)
                        settings.setLastSentAt(System.currentTimeMillis())
                    }

                    is ApiResult.NetworkError -> {
                        Log.i(TAG, "送信できませんでした。後で再試行します: ${result.cause.message}")
                        return Result.retry()
                    }

                    is ApiResult.ServerError -> {
                        Log.w(TAG, "サーバーエラー ${result.code}。後で再試行します")
                        return Result.retry()
                    }

                    ApiResult.Unauthorized -> {
                        // 端末トークンが無効。ユーザーがペアリングし直すまで送りようがない。
                        Log.w(TAG, "認証に失敗しました。ペアリングのやり直しが必要です")
                        return Result.failure()
                    }

                    is ApiResult.ClientError -> {
                        // リクエストが受け付けられない。再試行しても同じなので回数を数えて捨てる。
                        Log.w(TAG, "送信を拒否されました (${result.code}): ${result.message}")
                        if (row.attempts + 1 >= LocationTuning.MAX_SEND_ATTEMPTS) {
                            dao.delete(row.id)
                        } else {
                            dao.incrementAttempts(row.id)
                            return Result.retry()
                        }
                    }
                }
            }
        }
    }

    private fun PendingLocation.toPayload(deviceId: String) = LocationPayload(
        deviceId = deviceId,
        lat = lat,
        lng = lng,
        accuracy = accuracy,
        timestamp = ISO.format(Instant.ofEpochMilli(recordedAt)),
        batteryLevel = batteryLevel,
    )

    private companion object {
        const val TAG = "UploadWorker"
        const val BATCH_SIZE = 50
        val ISO: DateTimeFormatter = DateTimeFormatter.ISO_INSTANT
    }
}
