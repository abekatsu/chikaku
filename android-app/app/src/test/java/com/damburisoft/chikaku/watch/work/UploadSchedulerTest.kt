package com.damburisoft.chikaku.watch.work

import androidx.work.WorkInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * バックオフを捨ててよい場面を固定する (Issue #15)。
 *
 * ここを間違えた結果が実際に出ている。2026-09-07、端末が 16:20 に回線を失い、
 * 19:51 に復旧させたが送信は再開せず、**最も遅れた位置がサーバーに届いたのは
 * 翌 01:08、遅延 8 時間 17 分**だった。毎時のヘルスチェックは動いていたのに、
 * `ExistingWorkPolicy.KEEP` が待機中のワークを見て何もせず返っていたため。
 */
class UploadSchedulerTest {

    /**
     * これが Issue #15 そのもの。回線断で試行回数が伸びたワークは、
     * 待っているだけで自力では前に進まない。捨てて積み直す。
     */
    @Test
    fun `失敗して待機中ならバックオフを捨てる`() {
        assertTrue(UploadScheduler.shouldResetBackoff(WorkInfo.State.ENQUEUED, runAttemptCount = 1))
        assertTrue(UploadScheduler.shouldResetBackoff(WorkInfo.State.ENQUEUED, runAttemptCount = 8))
    }

    /**
     * **送信中のワーカーを止めてはいけない。** ワーカーはキューが空になるまで
     * 読み続ける作りなので、ここで置き換えると掃き出しが毎回途中で切れる。
     * 回線復帰の直後は回線の監視と定期実行が重なって呼ばれうるため、実際に起きる。
     */
    @Test
    fun `実行中には触らない`() {
        assertFalse(UploadScheduler.shouldResetBackoff(WorkInfo.State.RUNNING, runAttemptCount = 0))
        assertFalse(UploadScheduler.shouldResetBackoff(WorkInfo.State.RUNNING, runAttemptCount = 8))
    }

    /** 一度も失敗していなければバックオフは付いていない。捨てるものが無い。 */
    @Test
    fun `失敗していない待機中はそのままにする`() {
        assertFalse(UploadScheduler.shouldResetBackoff(WorkInfo.State.ENQUEUED, runAttemptCount = 0))
    }

    /**
     * まだ1件も積んでいない場合。通常の投入に落ちるだけで、
     * 取り消すべきワークは存在しない。
     */
    @Test
    fun `ワークが無ければ何もしない`() {
        assertFalse(UploadScheduler.shouldResetBackoff(null, runAttemptCount = 0))
    }

    /**
     * 終わったワークは `KEEP` でもそのまま積み直せる。わざわざ `REPLACE` にすると、
     * 取り消し扱いの履歴が増えるだけで得るものが無い。
     */
    @Test
    fun `終わったワークは置き換えない`() {
        listOf(
            WorkInfo.State.SUCCEEDED,
            WorkInfo.State.FAILED,
            WorkInfo.State.CANCELLED,
            WorkInfo.State.BLOCKED,
        ).forEach {
            assertFalse("$it", UploadScheduler.shouldResetBackoff(it, runAttemptCount = 3))
        }
    }
}
