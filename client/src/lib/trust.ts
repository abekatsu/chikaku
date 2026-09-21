/**
 * その測位を額面どおり信じてよいか (Issue #16)。
 *
 * **判定を 1 か所に置く。** 地図と端末カードで食い違うと、
 * 「地図では点線なのにカードは何も言わない」といった不整合が起きる。
 *
 * **出どころの自己申告 (`source`) は主役ではない。** #13 で入れた
 * `satellite` / `network` の判定は、根拠にした高度・速度・方位のどれもが
 * 出どころを区別しないことが実機で分かった（1,770 件中 `network` は 4 件）。
 * 代わりに、データそのものに残っていた 2 つの手がかりを使う。
 *
 * 1. **誤差の申告が 100 / 200 / 300 / 400 / 500 ちょうど。** Wi-Fi・基地局の
 *    データベース位置を返すとき、端末は精度を離散値で申告する。既知の誤り
 *    10 点は全部これで、速度が付いた測位 512 件のうちこれに当たるのは 2 件。
 * 2. **前後の点は近いのに、その点だけ遠い。** accuracy 56m を自称して 33km
 *    外した点があり、1 では拾えない。1,770 件でこの条件に当たるのはその 1 点だけ。
 *
 * どちらも「network を当てる」ためのものではない。自宅で最もよく記録される
 * 精度 22m の点も実は network 測位だが、場所としては正しい。目的は
 * **信用してはいけない点を実線で描かないこと**で、それ以上ではない。
 */

import type { LocationSourceKind } from "../api/types";

/** 判定に必要な最小限。`Fix` と `HistoryEvent` の両方が満たす。 */
export interface TrustInput {
  lat: number;
  lng: number;
  accuracy: number;
  source: LocationSourceKind | null;
}

/** 疑う理由。信じてよければ null。 */
export type DistrustReason = "database_accuracy" | "spike" | "reported_network" | "reported_unknown";

/**
 * 誤差の申告がデータベース由来の形をしているか。
 *
 * 衛星測位の精度は 9.965 のような実数で、100.0 ちょうどになることは
 * 事実上ない。一方 Wi-Fi・基地局の推定は 100 / 200 / 300 / 400 / 500 の
 * 離散値で返ってくる（実データに出た値はこの 5 つ）。将来 1000 や 2000 が
 * 出ても同じ性質と考え、「100 以上の 100 の倍数」で判定する。
 */
export function looksDatabaseAccuracy(accuracy: number): boolean {
  return Number.isInteger(accuracy) && accuracy >= 100 && accuracy % 100 === 0;
}

/** スパイクとみなす距離。前後の点からこれ以上離れていれば疑う。 */
export const SPIKE_FAR_METERS = 2000;
/** 前後の点同士がこれより近ければ「本人は動いていない」とみなす。 */
export const SPIKE_NEAR_METERS = 1000;

/**
 * 前後の点は近いのに、この点だけ遠い。
 *
 * **「直前からの速度」では拾えない。** 33km 外した実例は 15 分前の点から見ると
 * 127km/h で、電車なら出る数字。外れたあと 108 秒で戻ってくる側でしか
 * 引っかからない。前後を両方見れば、その往復自体が不自然だと分かる。
 *
 * 前後どちらかが無ければ判定しない（false）。最新位置は次の点が来るまで
 * この理由では疑えない。
 */
export function isSpike(
  prev: TrustInput | undefined,
  current: TrustInput,
  next: TrustInput | undefined,
): boolean {
  if (!prev || !next) return false;
  return (
    distanceMeters(prev, current) >= SPIKE_FAR_METERS &&
    distanceMeters(current, next) >= SPIKE_FAR_METERS &&
    distanceMeters(prev, next) <= SPIKE_NEAR_METERS
  );
}

/**
 * 疑う理由を返す。優先順位は「データから分かること」が先で、
 * 端末の自己申告は最後。自己申告の `network` は 4 件しか無く、
 * その 4 件は場所としては正しかったが、申告を無視する理由も無いので残す。
 *
 * `source` が null（報告が無い）なのは、報告に対応する前のアプリと iOS 版。
 * それだけでは疑わない。
 */
export function distrustReason(
  current: TrustInput,
  neighbors: { prev?: TrustInput | undefined; next?: TrustInput | undefined } = {},
): DistrustReason | null {
  if (looksDatabaseAccuracy(current.accuracy)) return "database_accuracy";
  if (isSpike(neighbors.prev, current, neighbors.next)) return "spike";
  if (current.source === "network") return "reported_network";
  if (current.source === "unknown") return "reported_unknown";
  return null;
}

export function isTrustedFix(
  current: TrustInput,
  neighbors: { prev?: TrustInput | undefined; next?: TrustInput | undefined } = {},
): boolean {
  return distrustReason(current, neighbors) === null;
}

/**
 * 履歴の各点について、前後を見たうえで信じてよいかを返す。
 * 経路の描き分けはこれを使う。1 点ずつ [isTrustedFix] を呼ぶと
 * スパイク判定に必要な前後の点が渡らない。
 */
export function trustedHistory(events: readonly TrustInput[]): boolean[] {
  return events.map((event, i) =>
    isTrustedFix(event, { prev: events[i - 1], next: events[i + 1] }),
  );
}

export const DISTRUST_LABEL: Record<DistrustReason, string> = {
  database_accuracy:
    "Wi-Fi・基地局からの推定と見られます。実際の場所と大きく離れることがあります。",
  spike: "前後の位置から大きく離れた点です。測位の誤りの可能性があります。",
  reported_network:
    "Wi-Fi・基地局からの推定です。実際の場所と大きく離れることがあります。",
  reported_unknown: "測位の出どころを判別できませんでした。",
};

/** 2 点間の距離（メートル）。Haversine。数 km の判定に使うだけなので十分。 */
export function distanceMeters(a: { lat: number; lng: number }, b: { lat: number; lng: number }): number {
  const R = 6_371_000;
  const toRad = (deg: number) => (deg * Math.PI) / 180;
  const dLat = toRad(b.lat - a.lat);
  const dLng = toRad(b.lng - a.lng);
  const h =
    Math.sin(dLat / 2) ** 2 +
    Math.cos(toRad(a.lat)) * Math.cos(toRad(b.lat)) * Math.sin(dLng / 2) ** 2;
  return 2 * R * Math.asin(Math.sqrt(h));
}
