package com.damburisoft.chikaku.watch.data

import android.content.Context
import android.os.UserManager

/**
 * Direct Boot（ロック解除前の起動）まわりの判定を1か所にまとめる。
 *
 * **なぜ要るか (Issue #3)。** `ACTION_BOOT_COMPLETED` は、ユーザーが最初に画面ロックを
 * 解除するまで配信されない。認証情報暗号化ストレージ（credential encrypted, CE）が
 * 使えるようになるまで待つためで、再起動後にロックを解除せず出かけると、その間
 * 見守りは1点も記録できない。本番では再起動の1分後から35分間の空白ができた。
 *
 * これを塞ぐには `LOCKED_BOOT_COMPLETED` を受ける必要があり、そのためには
 * 起動に必要な状態を**端末保護ストレージ**（device encrypted, DE）へ移さなければ
 * ならない。DE はロック解除前から読める代わりに、ユーザーの資格情報では
 * 暗号化されない。
 *
 * **何を DE に置き、何を置かないか。**
 * - 置く: 同意・見守りの有効/無効・device_id・前回キュー投入位置・送信待ちの位置
 * - 置かない: `device_token`（[SettingsStore] を参照）
 *
 * ロック解除前は「測位してキューに積む」までを行い、送信はしない。送信に要る
 * トークンを DE に出さずに済むので、端末を拾われたときの露出が現状から増えない。
 * 走った35分ぶんの位置は `recordedAt` 付きで残り、解除した時点でまとめて届く。
 */
object DeviceStorage {

    /**
     * ロック解除済みか。解除前は CE ストレージ・WorkManager・
     * Google Play services のいずれも使えない。
     */
    fun isUserUnlocked(context: Context): Boolean =
        context.getSystemService(UserManager::class.java)?.isUserUnlocked ?: true

    /**
     * 端末保護ストレージに紐づく [Context]。
     *
     * **戻り値に `applicationContext` を使ってはいけない。** そちらは CE 側を指す
     * ため、せっかく切り替えた保存先が元に戻る。
     */
    fun deviceProtected(context: Context): Context =
        context.applicationContext.createDeviceProtectedStorageContext()
}
