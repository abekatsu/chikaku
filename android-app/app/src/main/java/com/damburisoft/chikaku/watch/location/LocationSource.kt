package com.damburisoft.chikaku.watch.location

import android.location.Location

/**
 * その測位がどこから来たか (Issue #13)。
 *
 * **なぜ要るか。** 2026-08-29 に、訪問していない場所が 7km 離れた位置に
 * 2時間ぶん記録された。原因と考えられるのは Wi-Fi / 基地局による測位で、
 * これは「アクセスポイントや基地局が過去に観測された場所」を返すものであり、
 * 実測ではない。列車の車内 Wi-Fi のように AP 自体が動く場合、答えは大きく外れる。
 *
 * **精度 (`accuracy`) では区別できない。** 問題の測位は 100m を自称しながら
 * 7km 外していた。誤っているときに限って誤差の申告が当てにならないため、
 * 閾値による足切りは機能しない（issue に全データでの検証がある）。
 *
 * そこで出どころ自体を記録する。`getProvider()` は融合済みだと `fused` としか
 * 返らないことがあるため、**高度・速度・方位の有無**という副次的な手がかりを使う。
 */
enum class LocationSource {
    /** 衛星測位。実際に空を見て得た位置。 */
    SATELLITE,

    /** Wi-Fi / 基地局。データベース上の登録位置であって実測ではない。 */
    NETWORK,

    /** 判断がつかない。**「問題なし」ではない。** */
    UNKNOWN,
    ;

    val wireValue: String get() = name.lowercase()

    companion object {

        /**
         * **これは経験則であって保証ではない。** 判定の根拠になった生の値も
         * 一緒に保存してあるので、実機のデータを見てから見直せるようにしてある。
         *
         * 衛星測位は高度を伴う。Wi-Fi / 基地局測位は原理的に高度を出せないため、
         * 高度の有無がいちばん素直な手がかりになる。速度と方位は移動していないと
         * 付かないので、静止中の衛星測位を取りこぼさないよう単独の条件にはしない。
         */
        fun classify(hasAltitude: Boolean, hasSpeed: Boolean, hasBearing: Boolean): LocationSource =
            when {
                hasAltitude -> SATELLITE
                // 高度が無いのに速度や方位がある。素性が読めないので断定しない。
                hasSpeed || hasBearing -> UNKNOWN
                else -> NETWORK
            }

        fun of(location: Location): LocationSource =
            classify(location.hasAltitude(), location.hasSpeed(), location.hasBearing())
    }
}
