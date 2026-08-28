package com.damburisoft.chikaku.watch.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 旧バージョンからの引き継ぎ判定を固定する (Issue #3)。
 *
 * ここを間違えると、**親の端末が更新した瞬間にペアリングを失う。**
 * 招待コードの再発行と再入力が要るので、静かに落としてよい類の失敗ではない。
 */
class StorageMigrationTest {

    @Test
    fun `ロック解除前は引き継がない`() {
        // 移す元が認証情報暗号化ストレージにあり、そもそも読めない。
        assertFalse(StorageMigration.shouldImport(userUnlocked = false, legacyExists = true))
    }

    @Test
    fun `旧データがあり解除済みなら引き継ぐ`() {
        assertTrue(StorageMigration.shouldImport(userUnlocked = true, legacyExists = true))
    }

    @Test
    fun `旧データが無ければ何もしない`() {
        assertFalse(StorageMigration.shouldImport(userUnlocked = true, legacyExists = false))
        assertFalse(StorageMigration.shouldImport(userUnlocked = false, legacyExists = false))
    }

    /**
     * 移動先の有無を条件に入れてはいけない。ロック解除前にプロセスが起きると、
     * 中身が空のまま移動先が先に作られることがある。それを「済み」と見なすと
     * 引き継ぎが永久に走らなくなる。
     */
    @Test
    fun `移動先が既にあっても引き継ぎを止めない`() {
        assertTrue(StorageMigration.shouldImport(userUnlocked = true, legacyExists = true))
    }
}
