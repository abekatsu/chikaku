package com.damburisoft.chikaku.watch.location

import com.damburisoft.chikaku.watch.location.LocationSource.Companion.classify
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 測位の出どころの判定を固定する (Issue #13)。
 *
 * 訪問していない場所が 7km 離れて 2 時間ぶん記録された。精度は 100m を
 * 自称していたので、**誤差の数字では見分けられない。** 出どころで見分ける。
 */
class LocationSourceTest {

    @Test
    fun `高度があれば衛星測位とみなす`() {
        // Wi-Fi / 基地局測位は原理的に高度を出せない。
        assertEquals(LocationSource.SATELLITE, classify(hasAltitude = true, hasSpeed = true, hasBearing = true))
    }

    @Test
    fun `静止中の衛星測位を取りこぼさない`() {
        // 速度と方位は動いていないと付かない。これを必須にすると
        // 「家で座っている間の衛星測位」が全部 network に落ちる。
        assertEquals(LocationSource.SATELLITE, classify(hasAltitude = true, hasSpeed = false, hasBearing = false))
    }

    @Test
    fun `高度も速度も方位も無ければネットワーク測位`() {
        assertEquals(LocationSource.NETWORK, classify(hasAltitude = false, hasSpeed = false, hasBearing = false))
    }

    @Test
    fun `高度が無いのに速度があるものは断定しない`() {
        // 素性が読めない。**「問題なし」に倒さない**のが肝心で、
        // ここを NETWORK にも SATELLITE にも寄せると判断を誤らせる。
        assertEquals(LocationSource.UNKNOWN, classify(hasAltitude = false, hasSpeed = true, hasBearing = false))
        assertEquals(LocationSource.UNKNOWN, classify(hasAltitude = false, hasSpeed = false, hasBearing = true))
    }

    @Test
    fun `送信時の文字列は小文字`() {
        assertEquals("satellite", LocationSource.SATELLITE.wireValue)
        assertEquals("network", LocationSource.NETWORK.wireValue)
        assertEquals("unknown", LocationSource.UNKNOWN.wireValue)
    }
}
