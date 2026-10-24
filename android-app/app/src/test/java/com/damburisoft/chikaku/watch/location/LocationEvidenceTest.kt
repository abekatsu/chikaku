package com.damburisoft.chikaku.watch.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 判断材料の写し取りで、境界の扱いを固定する (Issue #16)。
 *
 * ここは**判定ではなく記録**なので、間違えても測位が止まるわけではない。
 * ただし壊れ方が静かで、**気づくのは「実機のデータを見て決めよう」と
 * した時点**になる。#13 では判定の根拠が壊れたまま 1,770 件を集めてしまい、
 * その 561 件が使い物にならなかった。同じことを繰り返さないための歯止め。
 */
class LocationEvidenceTest {

    private val now = 1_000_000_000_000L // 適当な単調増加時計の値（ナノ秒）

    @Test
    fun `経過時間はミリ秒に落として返す`() {
        // 3 秒前に測位された
        val threeSecondsAgo = now - 3_000_000_000L
        assertEquals(3_000L, LocationEvidence.ageMillis(threeSecondsAgo, now))
    }

    @Test
    fun `測位時刻が未設定なら経過時間は求めない`() {
        // **0 を返してはいけない。** 「たった今の測位」と区別がつかなくなり、
        // キャッシュ済みの古い位置を見分けるという目的が果たせない。
        assertNull(LocationEvidence.ageMillis(0L, now))
        assertNull(LocationEvidence.ageMillis(-1L, now))
    }

    @Test
    fun `未来の測位は0に丸める`() {
        // 単調増加時計が巻き戻ることは無いはずだが、負の経過時間を
        // そのまま通すより無害。
        assertEquals(0L, LocationEvidence.ageMillis(now + 5_000_000_000L, now))
    }

    @Test
    fun `extras のキーは整列してから連結する`() {
        // 並び順が端末やバージョンで揺れると、同じ組み合わせが
        // 集計上は別物に見えてしまう。
        assertEquals(
            "maxCn0,meanCn0,satellites",
            LocationEvidence.joinExtraKeys(setOf("satellites", "maxCn0", "meanCn0")),
        )
        assertEquals(
            LocationEvidence.joinExtraKeys(setOf("b", "a")),
            LocationEvidence.joinExtraKeys(setOf("a", "b")),
        )
    }

    @Test
    fun `extras が無い場合は null`() {
        assertNull(LocationEvidence.joinExtraKeys(null))
        assertNull(LocationEvidence.joinExtraKeys(emptySet()))
    }

    @Test
    fun `長すぎるキー一覧は切り詰める`() {
        // 端末が何を入れてくるか読めないので、際限なく伸ばさない。
        val many = (1..200).map { "key$it" }.toSet()
        val joined = LocationEvidence.joinExtraKeys(many)!!
        assertEquals(LocationEvidence.MAX_EXTRA_KEYS_LENGTH, joined.length)
        assertEquals('…', joined.last())
    }
}
