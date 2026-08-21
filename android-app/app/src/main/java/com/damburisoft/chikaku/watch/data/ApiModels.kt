package com.damburisoft.chikaku.watch.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class RegisterDeviceRequest(
    @SerialName("invite_code") val inviteCode: String,
    @SerialName("device_name") val deviceName: String,
    /** 端末モデル名。ダッシュボード側で見分けるための補助情報。 */
    @SerialName("device_model") val deviceModel: String,
)

@Serializable
data class RegisterDeviceResponse(
    @SerialName("device_id") val deviceId: String,
    @SerialName("device_token") val deviceToken: String,
    @SerialName("family_id") val familyId: String,
)

/** CLAUDE.md §2.4 の送信ペイロード。 */
@Serializable
data class LocationPayload(
    @SerialName("device_id") val deviceId: String,
    val lat: Double,
    val lng: Double,
    val accuracy: Float,
    /** ISO-8601 (UTC)。サーバー側の時刻表現に依存しないよう文字列で送る。 */
    val timestamp: String,
    @SerialName("battery_level") val batteryLevel: Int,
)

@Serializable
data class ErrorResponse(val message: String? = null, val error: String? = null)
