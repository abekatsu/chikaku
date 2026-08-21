package com.damburisoft.chikaku.watch.ui

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.damburisoft.chikaku.watch.Graph
import com.damburisoft.chikaku.watch.R
import com.damburisoft.chikaku.watch.data.ApiResult
import com.damburisoft.chikaku.watch.data.Settings
import com.damburisoft.chikaku.watch.service.LocationTrackingService
import com.damburisoft.chikaku.watch.work.UploadScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface PairingState {
    data object Idle : PairingState
    data object InProgress : PairingState
    data class Failed(val message: String) : PairingState
}

data class UiState(
    val settings: Settings? = null,
    val pendingCount: Int = 0,
    val pairing: PairingState = PairingState.Idle,
) {
    val loading: Boolean get() = settings == null
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val pairing = MutableStateFlow<PairingState>(PairingState.Idle)

    val state: StateFlow<UiState> = combine(
        Graph.settings.settings,
        Graph.locations.pendingCount,
        pairing,
    ) { settings, pending, pairingState ->
        UiState(settings = settings, pendingCount = pending, pairing = pairingState)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    fun onConsent() = viewModelScope.launch {
        Graph.settings.setConsented(true)
    }

    fun pair(inviteCode: String, deviceName: String, serverBaseUrl: String) {
        val code = inviteCode.trim()
        if (code.isEmpty()) {
            pairing.value = PairingState.Failed(string(R.string.pairing_error_empty_code))
            return
        }
        pairing.value = PairingState.InProgress
        viewModelScope.launch {
            Graph.settings.setServerBaseUrl(serverBaseUrl)
            val baseUrl = Graph.settings.current().serverBaseUrl
            val name = deviceName.trim().ifEmpty { Build.MODEL }
            val result = Graph.api.registerDevice(
                baseUrl = baseUrl,
                inviteCode = code,
                deviceName = name,
                deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
            )
            pairing.value = when (result) {
                is ApiResult.Success -> {
                    Graph.settings.savePairing(
                        deviceId = result.value.deviceId,
                        deviceToken = result.value.deviceToken,
                        familyId = result.value.familyId,
                        deviceName = name,
                    )
                    PairingState.Idle
                }

                is ApiResult.NetworkError ->
                    failure(string(R.string.pairing_error_network))

                is ApiResult.ServerError ->
                    failure(string(R.string.pairing_error_server, result.code))

                ApiResult.Unauthorized ->
                    failure(string(R.string.pairing_error_invalid_code))

                is ApiResult.ClientError ->
                    failure(result.message ?: string(R.string.pairing_error_invalid_code))
            }
        }
    }

    fun dismissPairingError() {
        pairing.value = PairingState.Idle
    }

    fun startTracking() = viewModelScope.launch {
        Graph.settings.setTrackingEnabled(true)
        LocationTrackingService.start(getApplication())
    }

    fun stopTracking() {
        // trackingEnabled はサービス側の停止処理で false にする。
        LocationTrackingService.stop(getApplication())
    }

    fun sendNow() {
        LocationTrackingService.sendNow(getApplication())
    }

    /** 家族との接続を解除し、端末に残る情報を消す（CLAUDE.md §5）。 */
    fun unpair() = viewModelScope.launch {
        LocationTrackingService.stop(getApplication())
        UploadScheduler.cancelAll(getApplication())
        Graph.database.pendingLocationDao().clear()
        Graph.settings.clearPairing()
    }

    private fun failure(message: String) =
        PairingState.Failed(string(R.string.pairing_error_failed, message))

    private fun string(resId: Int, vararg args: Any): String =
        if (args.isEmpty()) getApplication<Application>().getString(resId)
        else getApplication<Application>().getString(resId, *args)
}
