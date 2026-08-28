package com.damburisoft.chikaku.watch.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.LifecycleService
import com.damburisoft.chikaku.watch.Graph
import com.damburisoft.chikaku.watch.data.DeviceStorage
import com.damburisoft.chikaku.watch.location.LocationTuning
import com.damburisoft.chikaku.watch.work.UploadScheduler
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * 位置情報を監視し続ける Foreground Service。
 *
 * 測位そのものは [LocationTuning.locationRequest] の設定によって「一定距離動いたとき」
 * にしか発生しない。このサービスが常駐するのは、OSにプロセスを殺されないための
 * 器であって、能動的にポーリングするためではない。
 *
 * **測位の出どころが2つある (Issue #3)。** 画面ロックが解除される前は
 * Google Play services が動いていないため `FusedLocationProviderClient` を
 * 使えない。その間だけ、OS そのものが提供する [LocationManager] に切り替える。
 * 解除された時点で通常の経路へ戻す。
 */
class LocationTrackingService : LifecycleService() {

    private lateinit var fused: FusedLocationProviderClient
    private var updatesRequested = false
    private var usingLockedSource = false
    private var unlockReceiverRegistered = false

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            // バッチ配信されるため、1回のコールバックに複数件入りうる。
            result.locations.forEach { handleLocation(it, force = false) }
        }
    }

    /**
     * ロック解除前の測位を受ける。既定実装のあるメソッドまで明示しているのは、
     * `LocationListener` に既定実装が入ったのが API 30 からで、
     * minSdk 26 の端末では `AbstractMethodError` になるため。
     */
    private val lockedListener = object : LocationListener {
        override fun onLocationChanged(location: Location) = handleLocation(location, force = false)

        @Deprecated("API 29 で廃止。実装しないと古い端末で落ちる。")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

        override fun onProviderEnabled(provider: String) = Unit

        override fun onProviderDisabled(provider: String) = Unit
    }

    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = onUserUnlocked()
    }

    override fun onCreate() {
        super.onCreate()
        Graph.ensureInitialized(this)
        fused = LocationServices.getFusedLocationProviderClient(this)
        isRunning = true
        observeStateForNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        if (intent?.action == ACTION_STOP) {
            stopTracking()
            return Service.START_NOT_STICKY
        }

        if (!promoteToForeground()) {
            // 常駐通知を出せないまま居座ると OS に強制終了される。
            // 見守りは WatchdogWorker が次の機会に立て直す。
            stopSelf()
            return Service.START_NOT_STICKY
        }

        // **どの経路で入っても継続測位を確実に張る。**
        // 「今すぐ送信」やヘルスチェックでサービスが叩き起こされた場合、
        // ここを通さないと1点だけ送って測位が止まったままになる。
        val startedNow = startTracking()

        // 開始時は startTracking が初回測位を済ませているので重ねない。
        if (!startedNow &&
            (intent?.action == ACTION_SEND_NOW || intent?.action == ACTION_HEARTBEAT)
        ) {
            requestSingleLocation()
        }
        return Service.START_STICKY
    }

    override fun onDestroy() {
        stopLocationUpdates()
        unregisterUnlockReceiver()
        isRunning = false
        super.onDestroy()
    }

    /**
     * @return 常駐通知を出して前面に上がれたら true。
     *
     * ロック解除前は通知チャンネルの作成そのものが通らない端末がありうる。
     * そこで落ちると再起動直後の見守りが例外で終わるため、握って撤退する。
     * 解除後の通常経路ではここで失敗しない。
     */
    private fun promoteToForeground(): Boolean = try {
        TrackingNotification.createChannel(this)
        ServiceCompat.startForeground(
            this,
            TrackingNotification.NOTIFICATION_ID,
            TrackingNotification.build(this, lastSentAt = 0L, pendingCount = 0),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            } else {
                0
            },
        )
        true
    } catch (e: Exception) {
        Log.w(TAG, "常駐通知を出せませんでした", e)
        false
    }

    /**
     * 継続測位を開始する。冪等。
     * @return 今回この呼び出しで開始したら true（既に動いていたら false）
     */
    private fun startTracking(): Boolean {
        if (updatesRequested) return false
        if (!hasLocationPermission()) {
            Log.w(TAG, "位置情報の権限がないため見守りを開始できません")
            stopTracking()
            return false
        }
        val unlocked = DeviceStorage.isUserUnlocked(this)
        // 解除後に通常の測位へ移るための受け口は、いま測位できるかに関わらず張る。
        if (!unlocked) registerUnlockReceiver()
        try {
            // 静止していても定期的に生存を知らせる。測位コールバックは 50m 動かないと
            // 発生しないため、これが無いと沈黙し続ける。測位を張れなかった場合にも
            // 効くよう、先に予約しておく。
            HeartbeatScheduler.schedule(this)
            if (unlocked) {
                fused.requestLocationUpdates(LocationTuning.locationRequest(), callback, mainLooper)
                // 起動直後は前回位置が古い可能性が高いので、1回だけ現在地を取りに行く。
                requestSingleLocation()
            } else if (!startLockedUpdates()) {
                // **ロック解除前に測位できないだけなら、見守りを止めてはいけない。**
                // GPS が切られている・屋内で掴めないといった一時的な事情で
                // `trackingEnabled` を false にすると、親が自分で入れ直すまで
                // 二度と復帰しなくなる。常駐したまま解除を待つ。
                return false
            }
            // ロック解除前に単発取得の API は使えないが、requestLocationUpdates が
            // 最初の1点をそのまま流してくるので困らない。
            updatesRequested = true
            usingLockedSource = !unlocked
            return true
        } catch (e: SecurityException) {
            Log.w(TAG, "位置情報の取得を拒否されました", e)
            stopTracking()
            return false
        }
    }

    // 直前に hasLocationPermission() を確認しているが、lint は経路を追えない。
    @SuppressLint("MissingPermission")
    private fun startLockedUpdates(): Boolean {
        val manager = getSystemService(LocationManager::class.java) ?: return false
        val providers = LocationTuning.lockedProviders(manager.getProviders(true))
        if (providers.isEmpty()) {
            Log.w(TAG, "ロック解除前に使える測位プロバイダがありません")
            return false
        }
        providers.forEach {
            manager.requestLocationUpdates(
                it,
                LocationTuning.LOCKED_MIN_INTERVAL_MILLIS,
                LocationTuning.MIN_UPDATE_DISTANCE_METERS,
                lockedListener,
                mainLooper,
            )
        }
        return true
    }

    private fun stopLocationUpdates() {
        if (!updatesRequested) return
        if (usingLockedSource) {
            getSystemService(LocationManager::class.java)?.removeUpdates(lockedListener)
        } else {
            fused.removeLocationUpdates(callback)
        }
        updatesRequested = false
    }

    private fun stopTracking() {
        lifecycleScope.launch { Graph.settings.setTrackingEnabled(false) }
        HeartbeatScheduler.cancel(this)
        stopLocationUpdates()
        unregisterUnlockReceiver()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * 画面ロックが解除された。ここで初めて Google Play services・WorkManager・
     * 認証情報暗号化ストレージが使えるようになる。
     */
    private fun onUserUnlocked() {
        unregisterUnlockReceiver()
        Graph.onUserUnlocked(this)
        // 簡易測位から通常の測位へ差し替える。
        stopLocationUpdates()
        startTracking()
        UploadScheduler.schedulePeriodicFlush(this)
        // ロック解除前に溜めたぶんを送る。ここまで来て初めて宛先の認証ができる。
        UploadScheduler.enqueueNow(this)
    }

    private fun registerUnlockReceiver() {
        if (unlockReceiverRegistered) return
        ContextCompat.registerReceiver(
            this,
            unlockReceiver,
            IntentFilter(Intent.ACTION_USER_UNLOCKED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        unlockReceiverRegistered = true
    }

    private fun unregisterUnlockReceiver() {
        if (!unlockReceiverRegistered) return
        unregisterReceiver(unlockReceiver)
        unlockReceiverRegistered = false
    }

    /** ユーザーが「今すぐ送信する」を押したとき、および起動直後の初回測位。 */
    private fun requestSingleLocation() {
        if (!hasLocationPermission()) return
        // 単発取得は Play services 側の API なので、ロック解除前は呼んでも失敗する。
        if (!DeviceStorage.isUserUnlocked(this)) return
        lifecycleScope.launch {
            try {
                val request = CurrentLocationRequest.Builder()
                    // 手動送信のときだけは精度を優先する。頻度が低いので電池への影響は小さい。
                    .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                    .setMaxUpdateAgeMillis(MAX_CACHED_FIX_AGE_MILLIS)
                    .build()
                val location = fused.getCurrentLocation(request, null).await()
                if (location != null) handleLocation(location, force = true)
            } catch (e: SecurityException) {
                Log.w(TAG, "現在地を取得できませんでした", e)
            } catch (e: Exception) {
                Log.w(TAG, "現在地の取得に失敗しました", e)
            }
        }
    }

    private fun handleLocation(location: Location, force: Boolean) {
        lifecycleScope.launch {
            val queued = Graph.locations.onLocationUpdate(location, force = force)
            if (queued) UploadScheduler.enqueueNow(this@LocationTrackingService)
        }
    }

    /**
     * 「最終送信」「未送信件数」を常駐通知に反映し続ける。
     * 親が通知を見るだけで、ちゃんと届いているかを確認できるようにするため。
     */
    // collect の中で hasNotificationPermission() を確認しているが、lint はラムダ越しに追えない。
    @SuppressLint("MissingPermission")
    private fun observeStateForNotification() {
        lifecycleScope.launch {
            combine(
                Graph.settings.settings,
                Graph.locations.pendingCount,
            ) { settings, pending -> settings.lastSentAt to pending }
                .distinctUntilChanged()
                .collect { (lastSentAt, pending) ->
                    if (!hasNotificationPermission()) return@collect
                    NotificationManagerCompat.from(this@LocationTrackingService).notify(
                        TrackingNotification.NOTIFICATION_ID,
                        TrackingNotification.build(this@LocationTrackingService, lastSentAt, pending),
                    )
                }
        }
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "TrackingService"
        private const val MAX_CACHED_FIX_AGE_MILLIS = 60_000L

        const val ACTION_STOP = "com.damburisoft.chikaku.watch.action.STOP"
        const val ACTION_SEND_NOW = "com.damburisoft.chikaku.watch.action.SEND_NOW"
        const val ACTION_HEARTBEAT = "com.damburisoft.chikaku.watch.action.HEARTBEAT"

        /** Watchdog がサービスの生死を判断するために参照する。 */
        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, LocationTrackingService::class.java),
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, LocationTrackingService::class.java).setAction(ACTION_STOP),
            )
        }

        /** ヘルスチェックの測位。[HeartbeatReceiver] から呼ばれる。 */
        fun heartbeat(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, LocationTrackingService::class.java).setAction(ACTION_HEARTBEAT),
            )
        }

        fun sendNow(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, LocationTrackingService::class.java).setAction(ACTION_SEND_NOW),
            )
        }
    }
}
