package com.damburisoft.chikaku.watch.location

import android.location.LocationManager
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

    /**
     * ロック解除前の測位間隔 (Issue #3)。通常時より短いのは、
     * `LocationManager` にはバッチ配信 (`setMaxUpdateDelayMillis`) が無く、
     * 間隔を延ばしても電池が戻ってこないため。この状態は親が画面ロックを
     * 解除した時点で終わる。
     */
    val LOCKED_MIN_INTERVAL_MILLIS: Long = TimeUnit.MINUTES.toMillis(2)

    /**
     * ロック解除前に使う測位プロバイダを選ぶ。
     *
     * **`passive` を外す。** 他アプリの測位に相乗りするだけで、単独では何も
     * 返さない。ロック解除前は測位している他アプリ自体がほとんど動いていない。
     *
     * **GPS を先に置く。** `network` プロバイダの実体は Google Play services 側に
     * あり、Direct Boot 中は応答しない。屋外にいる前提なら GPS だけで足りる
     * （このバグが出たのは走っている最中だった）。
     */
    fun lockedProviders(enabled: List<String>): List<String> =
        enabled.filter { it != LocationManager.PASSIVE_PROVIDER }
            .sortedBy { if (it == LocationManager.GPS_PROVIDER) 0 else 1 }

    /**
     * 基本の測位間隔。**ここを短くすると HIGH_ACCURACY の代償が直に効く。**
     * 歩行者の移動速度なら 5 分で十分（CLAUDE.md §2.2）。
     */
    private val INTERVAL_MILLIS = TimeUnit.MINUTES.toMillis(5)
    private val MIN_INTERVAL_MILLIS = TimeUnit.MINUTES.toMillis(2)
    private val MAX_UPDATE_DELAY_MILLIS = TimeUnit.MINUTES.toMillis(15)

    /**
     * 歩行者の移動速度なら数分間隔で十分。[setMaxUpdateDelayMillis] でバッチ配信を
     * 許可し、CPUのウェイクアップ回数を減らす。
     *
     * **`PRIORITY_HIGH_ACCURACY` を使う (Issue #13)。** 当初は
     * `PRIORITY_BALANCED_POWER_ACCURACY` だったが、これは公式ドキュメントに
     * 「GPS をほとんど使わず、主に Wi-Fi と基地局の情報を使う」と明記されている。
     * その結果、訪問していない場所が 7km 離れた位置に2時間ぶん記録された。
     * Wi-Fi 測位が返すのはアクセスポイントの登録位置であって実測ではなく、
     * 列車の車内 Wi-Fi のように AP 自体が動く場合、答えは大きく外れる。
     *
     * **電池への影響は間隔の設定で抑えている。** 常時測位ではなく
     * [INTERVAL_MILLIS] ごと、かつ [MIN_UPDATE_DISTANCE_METERS] 動かない限り
     * 配信もされない。GNSS が連続で回るわけではないので、
     * 「HIGH_ACCURACY = 電池を焼く」という一般論はそのまま当てはまらない。
     * **ただし増えることは確かで、実測は必要**（docs §7.3）。
     */
    fun locationRequest(): LocationRequest =
        LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, INTERVAL_MILLIS)
            .setMinUpdateIntervalMillis(MIN_INTERVAL_MILLIS)
            .setMinUpdateDistanceMeters(MIN_UPDATE_DISTANCE_METERS)
            .setMaxUpdateDelayMillis(MAX_UPDATE_DELAY_MILLIS)
            .setWaitForAccurateLocation(false)
            .build()
}
