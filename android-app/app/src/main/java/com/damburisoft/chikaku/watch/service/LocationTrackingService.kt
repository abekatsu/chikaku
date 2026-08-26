package com.damburisoft.chikaku.watch.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.LifecycleService
import com.damburisoft.chikaku.watch.Graph
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
 */
class LocationTrackingService : LifecycleService() {

    private lateinit var fused: FusedLocationProviderClient
    private var updatesRequested = false

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            // バッチ配信されるため、1回のコールバックに複数件入りうる。
            result.locations.forEach { handleLocation(it, force = false) }
        }
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

        promoteToForeground()

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
        if (updatesRequested) {
            fused.removeLocationUpdates(callback)
            updatesRequested = false
        }
        isRunning = false
        super.onDestroy()
    }

    private fun promoteToForeground() {
        val notification = TrackingNotification.build(this, lastSentAt = 0L, pendingCount = 0)
        ServiceCompat.startForeground(
            this,
            TrackingNotification.NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            } else {
                0
            },
        )
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
        try {
            fused.requestLocationUpdates(LocationTuning.locationRequest(), callback, mainLooper)
            updatesRequested = true
            // 起動直後は前回位置が古い可能性が高いので、1回だけ現在地を取りに行く。
            requestSingleLocation()
            // 静止していても定期的に生存を知らせる。測位コールバックは
            // 50m 動かないと発生しないため、これが無いと沈黙し続ける。
            HeartbeatScheduler.schedule(this)
            return true
        } catch (e: SecurityException) {
            Log.w(TAG, "位置情報の取得を拒否されました", e)
            stopTracking()
            return false
        }
    }

    private fun stopTracking() {
        lifecycleScope.launch { Graph.settings.setTrackingEnabled(false) }
        HeartbeatScheduler.cancel(this)
        if (updatesRequested) {
            fused.removeLocationUpdates(callback)
            updatesRequested = false
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** ユーザーが「今すぐ送信する」を押したとき、および起動直後の初回測位。 */
    private fun requestSingleLocation() {
        if (!hasLocationPermission()) return
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
