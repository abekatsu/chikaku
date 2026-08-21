package com.damburisoft.chikaku.watch

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
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
import com.damburisoft.chikaku.watch.ui.DisclosureScreen
import com.damburisoft.chikaku.watch.ui.MainViewModel
import com.damburisoft.chikaku.watch.ui.PairingScreen
import com.damburisoft.chikaku.watch.ui.PermissionScreen
import com.damburisoft.chikaku.watch.ui.PermissionStatus
import com.damburisoft.chikaku.watch.ui.StatusScreen
import com.damburisoft.chikaku.watch.ui.theme.ChikakuTheme

class MainActivity : ComponentActivity() {

    private var permissionStatus by mutableStateOf(PermissionStatus(false, false, false, false))
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
                            onSendNow = vm::sendNow,
                            onStart = { vm.startTracking() },
                            onStop = vm::stopTracking,
                            onUnpair = vm::unpair,
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
            backgroundLocation = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                hasPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
            notifications = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                hasPermission(Manifest.permission.POST_NOTIFICATIONS),
            batteryUnrestricted = isIgnoringBatteryOptimizations(),
        )
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

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun isIgnoringBatteryOptimizations(): Boolean =
        getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) ?: true

    private fun requestBatteryExemption() {
        if (isIgnoringBatteryOptimizations()) return
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
