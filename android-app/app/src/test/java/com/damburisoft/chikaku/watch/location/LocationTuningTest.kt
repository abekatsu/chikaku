package com.damburisoft.chikaku.watch.location

import android.location.LocationManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ロック解除前に使う測位プロバイダの選び方を固定する (Issue #3)。
 *
 * この状態では Google Play services が動いていないため、
 * `FusedLocationProviderClient` も `network` プロバイダも当てにできない。
 */
class LocationTuningTest {

    @Test
    fun `passive は使わない`() {
        // 他アプリの測位に相乗りするだけで、単独では何も返さない。
        val providers = LocationTuning.lockedProviders(
            listOf(LocationManager.PASSIVE_PROVIDER, LocationManager.GPS_PROVIDER),
        )
        assertEquals(listOf(LocationManager.GPS_PROVIDER), providers)
    }

    @Test
    fun `GPS を先に試す`() {
        // network の実体は Play services 側にあり、ロック解除前は応答しない。
        val providers = LocationTuning.lockedProviders(
            listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER),
        )
        assertEquals(LocationManager.GPS_PROVIDER, providers.first())
        assertTrue(LocationManager.NETWORK_PROVIDER in providers)
    }

    @Test
    fun `使えるものが何も無ければ空を返す`() {
        assertTrue(LocationTuning.lockedProviders(emptyList()).isEmpty())
        assertTrue(
            LocationTuning.lockedProviders(listOf(LocationManager.PASSIVE_PROVIDER)).isEmpty(),
        )
    }
}
