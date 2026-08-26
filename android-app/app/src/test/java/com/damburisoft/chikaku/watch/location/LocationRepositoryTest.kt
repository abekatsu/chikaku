package com.damburisoft.chikaku.watch.location

import com.damburisoft.chikaku.watch.location.LocationRepository.Companion.shouldSend
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * 送信可否の判定を固定する。
 *
 * この判定には「静止中はそもそも呼ばれず、ヘルスチェックに到達できない」という
 * 欠陥があり、実機で5時間の空白ができるまで気づけなかった。
 * 判定を Android 非依存にしたうえで、ここで意味を固定しておく。
 */
class LocationRepositoryTest {

    private val heartbeat = LocationTuning.HEARTBEAT_INTERVAL_MILLIS
    private val threshold = LocationTuning.SEND_DISTANCE_THRESHOLD_METERS

    @Test
    fun `初回は無条件で送る`() {
        assertTrue(shouldSend(distanceFromLastQueuedMeters = null, millisSinceLastQueued = 0))
    }

    @Test
    fun `閾値を超えて動いたら送る`() {
        assertTrue(shouldSend(threshold, TimeUnit.MINUTES.toMillis(1)))
        assertTrue(shouldSend(threshold + 10f, TimeUnit.MINUTES.toMillis(1)))
    }

    @Test
    fun `ほとんど動いていなければ送らない`() {
        assertFalse(shouldSend(0f, TimeUnit.MINUTES.toMillis(1)))
        assertFalse(shouldSend(threshold - 1f, TimeUnit.MINUTES.toMillis(1)))
    }

    /**
     * 本件の中核。動いていなくてもヘルスチェック間隔を超えたら送る。
     * 「送られてこない」のが静止なのか異常なのかを子側が区別するために要る。
     */
    @Test
    fun `動いていなくてもヘルスチェック間隔を超えたら送る`() {
        assertFalse(shouldSend(0f, heartbeat - 1))
        assertTrue(shouldSend(0f, heartbeat))
        assertTrue(shouldSend(0f, heartbeat * 10))
    }

    @Test
    fun `ヘルスチェック間隔は30分`() {
        assertTrue(heartbeat == TimeUnit.MINUTES.toMillis(30))
    }
}
