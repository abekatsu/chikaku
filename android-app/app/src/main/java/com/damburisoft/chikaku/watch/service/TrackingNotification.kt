package com.damburisoft.chikaku.watch.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.damburisoft.chikaku.watch.MainActivity
import com.damburisoft.chikaku.watch.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Foreground Service の常駐通知。親（高齢者）が見て不安にならないよう、
 * 状態を日本語で素直に書く。
 */
object TrackingNotification {

    const val CHANNEL_ID = "tracking"
    const val NOTIFICATION_ID = 1001

    /**
     * 送信中に一瞬だけ出る通知。expedited work が Foreground Service として
     * 動く API 31 未満でのみ使われる (`UploadWorker.getForegroundInfo`)。
     * 常駐通知と同じ ID を使うと、送信が終わった時点で常駐通知ごと消える。
     */
    const val UPLOAD_NOTIFICATION_ID = 1002

    private val timeFormat = DateTimeFormatter.ofPattern("M月d日 HH:mm")

    fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_name),
            // 常時表示される通知なので、音もバイブも出さない。
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        context.getSystemService(NotificationManager::class.java)
            ?.createNotificationChannel(channel)
    }

    fun build(context: Context, lastSentAt: Long, pendingCount: Int): Notification {
        val text = when {
            pendingCount > 0 -> context.getString(R.string.notification_text_queued, pendingCount)
            lastSentAt > 0 -> context.getString(R.string.notification_text_sent, formatTime(lastSentAt))
            else -> context.getString(R.string.notification_text_idle)
        }
        val openApp = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notification_title))
            .setContentText(text)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    fun buildUploading(context: Context): Notification =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notification_title))
            .setContentText(context.getString(R.string.notification_text_uploading))
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun formatTime(epochMillis: Long): String =
        timeFormat.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))
}
