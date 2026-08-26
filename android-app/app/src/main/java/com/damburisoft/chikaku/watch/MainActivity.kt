package com.damburisoft.chikaku.watch

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.damburisoft.chikaku.watch.data.DeviceHealth
import com.damburisoft.chikaku.watch.ui.DisclosureScreen
import com.damburisoft.chikaku.watch.ui.HealthIssue
import com.damburisoft.chikaku.watch.ui.MainViewModel
import com.damburisoft.chikaku.watch.ui.PairingScreen
import com.damburisoft.chikaku.watch.ui.PermissionScreen
import com.damburisoft.chikaku.watch.ui.PermissionStatus
import com.damburisoft.chikaku.watch.ui.StatusScreen
import com.damburisoft.chikaku.watch.ui.theme.ChikakuTheme

class MainActivity : ComponentActivity() {

    private var permissionStatus by mutableStateOf(
        PermissionStatus(
            foregroundLocation = false,
            health = DeviceHealth(
                batteryUnrestricted = false,
                notificationsEnabled = false,
                backgroundLocation = false,
            ),
        )
    )
    private var deniedPermanently by mutableStateOf(false)

    private val foregroundLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result.values.any { it }
        deniedPermanently = !granted && !shouldShowRequestPermissionRationale(
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        refreshPermissionStatus()
    }

    private val backgroundLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refreshPermissionStatus() }

    private val notificationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refreshPermissionStatus() }

    private val systemScreenLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { refreshPermissionStatus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Graph.ensureInitialized(this)
        refreshPermissionStatus()

        setContent {
            ChikakuTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    val vm: MainViewModel = viewModel()
                    val state by vm.state.collectAsState()
                    val settings = state.settings

                    when {
                        settings == null -> LoadingScreen()

                        !settings.consented -> DisclosureScreen(onAgree = vm::onConsent)

                        !settings.isPaired -> PairingScreen(
                            settings = settings,
                            pairing = state.pairing,
                            onSubmit = vm::pair,
                        )

                        !permissionStatus.canStartTracking -> PermissionScreen(
                            status = permissionStatus,
                            deniedPermanently = deniedPermanently,
                            onRequestForegroundLocation = ::requestForegroundLocation,
                            onRequestBackgroundLocation = ::requestBackgroundLocation,
                            onRequestNotifications = ::requestNotificationPermission,
                            onRequestBatteryExemption = ::requestBatteryExemption,
                            onOpenSettings = ::openAppSettings,
                            onStart = { vm.startTracking() },
                        )

                        else -> StatusScreen(
                            settings = settings,
                            pendingCount = state.pendingCount,
                            health = permissionStatus.health,
                            onSendNow = vm::sendNow,
                            onStart = { vm.startTracking() },
                            onStop = vm::stopTracking,
                            onUnpair = vm::unpair,
                            onFixHealth = ::fixHealthIssue,
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 設定画面から戻ってきたときに状態を取り直す。
        refreshPermissionStatus()
    }

    private fun refreshPermissionStatus() {
        permissionStatus = PermissionStatus(
            foregroundLocation = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) ||
                hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION),
            health = DeviceHealth.read(this),
        )
    }

    /** 状態画面の警告から呼ばれる。 */
    private fun fixHealthIssue(issue: HealthIssue) = when (issue) {
        HealthIssue.BackgroundLocationMissing -> requestBackgroundLocation()
        HealthIssue.BatteryRestricted -> requestBatteryExemption()
        // ここに来るのは権限画面を通り過ぎたあと。権限ダイアログは
        // 二度断られていると無反応で終わるので、確実に直せる設定画面へ送る。
        HealthIssue.NotificationsDisabled -> openNotificationSettings()
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun requestForegroundLocation() {
        foregroundLocationLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )
        )
    }

    /**
     * Android 11 以降は権限ダイアログに「常に許可」が出ないため、
     * アプリの権限設定画面へ直接誘導するしかない。
     */
    private fun requestBackgroundLocation() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            openAppSettings()
        } else {
            backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
    }

    /**
     * 権限が無いうちはダイアログで足りるが、一度許可したあとに設定画面や
     * チャンネル単位で切られた場合はダイアログが出ないまま何も起きない。
     * その場合は通知設定画面へ送るしかない。
     */
    private fun requestNotificationPermission() {
        val needsDialog = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !hasPermission(Manifest.permission.POST_NOTIFICATIONS)
        if (needsDialog) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        openNotificationSettings()
    }

    private fun openNotificationSettings() {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        try {
            systemScreenLauncher.launch(intent)
        } catch (_: Exception) {
            // この画面を持たない端末向けの逃げ道。
            openAppSettings()
        }
    }

    private fun requestBatteryExemption() {
        if (DeviceHealth.isIgnoringBatteryOptimizations(this)) return
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.fromParts("package", packageName, null))
        try {
            systemScreenLauncher.launch(intent)
        } catch (_: Exception) {
            // 端末によってはこの画面が存在しない。その場合は電池設定の一覧を開く。
            systemScreenLauncher.launch(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun openAppSettings() {
        systemScreenLauncher.launch(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.fromParts("package", packageName, null))
        )
    }
}

@androidx.compose.runtime.Composable
private fun LoadingScreen() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}
