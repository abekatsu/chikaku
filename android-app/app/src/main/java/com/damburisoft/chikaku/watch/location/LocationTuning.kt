package com.damburisoft.chikaku.watch.location

import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.Priority
import java.util.concurrent.TimeUnit

/**
 * バッテリー最優先の測位パラメータ（CLAUDE.md §2.2）。
 *
 * ポーリングではなく「変化」を起点にするため、要となるのは
 * [LocationRequest.Builder.setMinUpdateDistanceMeters]。これにより、
 * 家で座っている間は測位コールバック自体がほとんど発生しない。
 */
object LocationTuning {

    /** これ未満しか動いていない位置更新はそもそも配信されない。 */
    const val MIN_UPDATE_DISTANCE_METERS = 50f

    /** 前回キューに入れた位置からこれ以上離れたら送信対象にする。 */
    const val SEND_DISTANCE_THRESHOLD_METERS = 50f

    /**
     * 動きがなくても、この間隔で1回は送る。
     * 「送られてこない」のが静止なのか異常なのかを子側が区別できるようにするため。
     */
    val HEARTBEAT_INTERVAL_MILLIS: Long = TimeUnit.MINUTES.toMillis(30)

    /** 精度がこれより悪い測位結果は誤差が大きすぎるため捨てる。 */
    const val MAX_ACCEPTABLE_ACCURACY_METERS = 500f

    /** キューに残せる最大件数。長期圏外でも無限に膨らませない。 */
    const val MAX_QUEUE_SIZE = 500

    /** 1件あたりの送信試行上限。超えたら捨てる。 */
    const val MAX_SEND_ATTEMPTS = 10

    private val INTERVAL_MILLIS = TimeUnit.MINUTES.toMillis(5)
    private val MIN_INTERVAL_MILLIS = TimeUnit.MINUTES.toMillis(2)
    private val MAX_UPDATE_DELAY_MILLIS = TimeUnit.MINUTES.toMillis(15)

    /**
     * 歩行者の移動速度なら数分間隔で十分。[setMaxUpdateDelayMillis] でバッチ配信を
     * 許可し、CPUのウェイクアップ回数を減らす。
     */
    fun locationRequest(): LocationRequest =
        LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, INTERVAL_MILLIS)
            .setMinUpdateIntervalMillis(MIN_INTERVAL_MILLIS)
            .setMinUpdateDistanceMeters(MIN_UPDATE_DISTANCE_METERS)
            .setMaxUpdateDelayMillis(MAX_UPDATE_DELAY_MILLIS)
            .setWaitForAccurateLocation(false)
            .build()
}
