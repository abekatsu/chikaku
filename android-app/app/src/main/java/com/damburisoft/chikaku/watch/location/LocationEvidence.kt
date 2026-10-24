package com.damburisoft.chikaku.watch.location

import android.location.Location
import android.os.Build
import android.os.SystemClock

/**
 * 測位結果に付いてきた「出どころの手がかり」をそのまま写し取ったもの (Issue #16)。
 *
 * **判定しない。記録するだけ。** #13 では「高度があれば衛星測位」という
 * もっともらしい前提で判定を作り、実機で完全に外した（1,770 件中 `network` と
 * 判定できたのは 4 件、33km 外した点も `satellite` になった）。同じ失敗を
 * 繰り返さないため、**実機のデータを見るまで新しい判定は作らない。**
 *
 * **何を記録するかは実機の `dumpsys location` から選んだ。** 2026-09-21 に
 * 両プロバイダの最終位置を比べたところ、こうなっていた。
 *
 * ```
 * network: hAcc=22.5 alt=60.09 vAcc=3.56 mslAlt=23.17 mslAltAcc=3.57
 * gps:     hAcc=9.97 alt=28.89 vAcc=17.0  mslAlt=-8.03 mslAltAcc=17.0
 *          vel=12.77 sAcc=1.17 bear=285.8 bAcc=5.08
 *          {Bundle[{satellites=11, maxCn0=25, meanCn0=21}]}
 * ```
 *
 * つまり**高度・鉛直精度・標高は network にも付く。** #13 の前提が成り立たない
 * ことはこれで確定した。一方 `sAcc` / `bAcc` と extras の衛星情報は gps にしか
 * 無かった。そこが次の候補になる。
 *
 * **ただしこれは「プロバイダの出力」であって「アプリが受け取るもの」ではない。**
 * アプリに届くのは融合済みの結果で、融合の過程で extras や精度が落とされる
 * 可能性がある。落ちるかどうかは実機で見るまで分からない。だから
 * 期待の薄いもの（鉛直精度・標高）も含めて全部記録する。**外れたことを
 * 確認できるのも情報のうち**で、#13 ではそれができたから壊れていると分かった。
 *
 * すべて null 可。**null は「該当しない」ではなく「報告が無い」。**
 * 古いアプリが積んだ行や、API レベルが足りない端末が該当する。
 */
data class LocationEvidence(
    /** 融合済みだと `fused` としか返らないことがある。 */
    val provider: String?,
    val hasAltitude: Boolean,
    val hasSpeed: Boolean,
    val hasBearing: Boolean,
    /** 以下が #16 で足したぶん。 */
    val hasVerticalAccuracy: Boolean,
    val hasSpeedAccuracy: Boolean,
    val hasBearingAccuracy: Boolean,
    /** API 34 未満では判定できないので null。「無い」ではない。 */
    val hasMslAltitude: Boolean?,
    /**
     * 測位されてから今までの経過時間。CoreLocation と同じく、Android も
     * 起動直後にキャッシュ済みの古い位置を返すことがある。壁時計ではなく
     * 単調増加時計で測るので、端末の時刻がずれていても意味を保つ。
     */
    val ageMillis: Long?,
    /** extras のキー名だけ。値は入れない（何が入るか端末依存のため）。 */
    val extraKeys: String?,
    /** extras の `satellites`。これが残っていれば決定打になりうる。 */
    val satellites: Int?,
) {
    companion object {
        /** extras のキー名を連結したときの上限。端末が何を入れてくるか読めないため。 */
        internal const val MAX_EXTRA_KEYS_LENGTH = 200

        /**
         * extras から取り出す整数値。GNSS の衛星数で、Play services 由来の
         * 融合結果に残るかどうかが #16 の焦点。
         */
        internal const val KEY_SATELLITES = "satellites"

        fun of(location: Location, nowNanos: Long = SystemClock.elapsedRealtimeNanos()): LocationEvidence {
            val extras = runCatching { location.extras }.getOrNull()
            return LocationEvidence(
                provider = location.provider,
                hasAltitude = location.hasAltitude(),
                hasSpeed = location.hasSpeed(),
                hasBearing = location.hasBearing(),
                hasVerticalAccuracy = location.hasVerticalAccuracy(),
                hasSpeedAccuracy = location.hasSpeedAccuracy(),
                hasBearingAccuracy = location.hasBearingAccuracy(),
                hasMslAltitude = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    location.hasMslAltitude()
                } else {
                    null
                },
                ageMillis = ageMillis(location.elapsedRealtimeNanos, nowNanos),
                extraKeys = joinExtraKeys(extras?.keySet()),
                satellites = runCatching {
                    extras?.takeIf { it.containsKey(KEY_SATELLITES) }?.getInt(KEY_SATELLITES)
                }.getOrNull(),
            )
        }

        /**
         * **Android の API に依存しない純粋な関数にしてある。** ここは実機の
         * データを読み解く土台になる部分なので、境界の扱いを単体テストで固定する。
         *
         * 測位時刻が未設定（0）なら経過時間は求めようがないので null。
         * 単調増加時計が巻き戻ることは無いはずだが、負になったら 0 に丸める
         * （未来の測位を「古い」と読み違えるより無害）。
         */
        fun ageMillis(elapsedRealtimeNanos: Long, nowNanos: Long): Long? {
            if (elapsedRealtimeNanos <= 0L) return null
            val age = (nowNanos - elapsedRealtimeNanos) / 1_000_000
            return if (age < 0) 0 else age
        }

        /**
         * キー名を並べて1つの文字列にする。並び順が端末やバージョンで揺れると
         * 集計のときに同じものが別物に見えるので、**整列してから連結する。**
         * 長すぎるものは切る（何が入るか端末依存で、際限が無い）。
         */
        fun joinExtraKeys(keys: Set<String>?): String? {
            if (keys.isNullOrEmpty()) return null
            val joined = keys.sorted().joinToString(",")
            return if (joined.length <= MAX_EXTRA_KEYS_LENGTH) {
                joined
            } else {
                joined.take(MAX_EXTRA_KEYS_LENGTH - 1) + "…"
            }
        }
    }
}
