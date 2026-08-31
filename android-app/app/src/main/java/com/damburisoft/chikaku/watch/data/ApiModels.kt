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
    /**
     * 端末設定の健康状態 (Issue #4)。位置ではなく端末に紐づく情報なので、
     * サーバーは最新のものだけを parent_devices に上書きする。
     * 古いアプリからは送られてこないため、サーバー側では省略可能。
     */
    val health: DeviceHealth? = null,
    /**
     * 測位の出どころ (Issue #13)。精度では区別できない誤りを見分けるために送る。
     * 古いアプリからは送られてこないため、サーバー側では省略可能。
     */
    val source: LocationSourceReport? = null,
)

/**
 * 出どころの判定結果と、その根拠になった生の値。
 * **判定は経験則なので、根拠も一緒に送って後から見直せるようにしている。**
 */
@Serializable
data class LocationSourceReport(
    /** `satellite` / `network` / `unknown` */
    val kind: String,
    val provider: String? = null,
    @SerialName("has_altitude") val hasAltitude: Boolean,
    @SerialName("has_speed") val hasSpeed: Boolean,
    @SerialName("has_bearing") val hasBearing: Boolean,
)

@Serializable
data class ErrorResponse(val message: String? = null, val error: String? = null)
