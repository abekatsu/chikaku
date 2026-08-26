package com.damburisoft.chikaku.watch.data

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.damburisoft.chikaku.watch.service.TrackingNotification
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 見守りの成否を左右する端末側の設定。
 *
 * これらは権限画面を一度通り過ぎたあとでも、端末の設定変更や OEM の省電力機能に
 * よっていつでも失われる。失われたことは親の画面に出すだけでは誰も気づけないので、
 * 位置情報と一緒にサーバーへ送り、子側のダッシュボードにも出す (Issue #4)。
 *
 * 位置情報そのものではないので、送るのはキューに積んだ時点ではなく**送信する時点**の値。
 * 圏外で何時間も溜まっていたキューに、当時の設定を貼り付けても意味がない。
 */
@Serializable
data class DeviceHealth(
    /** 電池の最適化から除外されているか。false だと Doze 中に送信が数十分遅れる。 */
    @SerialName("battery_unrestricted") val batteryUnrestricted: Boolean,
    /** 常駐通知を実際に表示できるか。false だと親が動作中であることを確認できない。 */
    @SerialName("notifications_enabled") val notificationsEnabled: Boolean,
    /** 位置情報が「常に許可」になっているか。false だと画面を消した間に測位が止まる。 */
    @SerialName("background_location") val backgroundLocation: Boolean,
) {

    val hasProblem: Boolean
        get() = !batteryUnrestricted || !notificationsEnabled || !backgroundLocation

    companion object {

        fun read(context: Context): DeviceHealth = DeviceHealth(
            batteryUnrestricted = isIgnoringBatteryOptimizations(context),
            notificationsEnabled = canShowTrackingNotification(context),
            backgroundLocation = hasBackgroundLocation(context),
        )

        /**
         * 取得できない端末では true 扱いにする。
         * 「分からない」を「問題あり」として警告すると、直しようのない警告が出続ける。
         */
        fun isIgnoringBatteryOptimizations(context: Context): Boolean =
            context.getSystemService(PowerManager::class.java)
                ?.isIgnoringBatteryOptimizations(context.packageName)
                ?: true

        /**
         * POST_NOTIFICATIONS の可否だけでは足りない。権限が付いていても、
         * 設定画面でアプリごと、あるいはチャンネル単位で切られていれば通知は出ない。
         * 実機で切られるのはたいていこちらの経路。
         */
        fun canShowTrackingNotification(context: Context): Boolean {
            val manager = NotificationManagerCompat.from(context)
            if (!manager.areNotificationsEnabled()) return false
            val channel = manager.getNotificationChannel(TrackingNotification.CHANNEL_ID)
            // 未作成なら初回起動直後。これから作られるので問題なしとみなす。
            return channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE
        }

        fun hasBackgroundLocation(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_BACKGROUND_LOCATION,
                ) == PackageManager.PERMISSION_GRANTED
    }
}
