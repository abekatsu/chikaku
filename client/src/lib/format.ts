import type { LocationSourceKind } from "../api/types";

/**
 * 「いつの情報か」を人が読める形にする。
 *
 * 見守りダッシュボードで最初に知りたいのは座標ではなく
 * 「その情報がどれだけ新しいか」なので、相対時刻を主に出す。
 */

const MINUTE = 60_000;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;

export function relativeTime(iso: string, now: number = Date.now()): string {
  const elapsed = now - new Date(iso).getTime();
  if (!Number.isFinite(elapsed)) return "不明";
  if (elapsed < 0) return "たった今";
  if (elapsed < MINUTE) return "たった今";
  if (elapsed < HOUR) return `${Math.floor(elapsed / MINUTE)}分前`;
  if (elapsed < DAY) return `${Math.floor(elapsed / HOUR)}時間前`;
  return `${Math.floor(elapsed / DAY)}日前`;
}

const dateTimeFormat = new Intl.DateTimeFormat("ja-JP", {
  month: "numeric",
  day: "numeric",
  hour: "2-digit",
  minute: "2-digit",
});

export function absoluteTime(iso: string): string {
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? "不明" : dateTimeFormat.format(date);
}

const timeOnlyFormat = new Intl.DateTimeFormat("ja-JP", {
  hour: "2-digit",
  minute: "2-digit",
});

export function timeOnly(iso: string): string {
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? "--:--" : timeOnlyFormat.format(date);
}

/**
 * 情報の鮮度。見守りでは「古い」ことそのものが伝えるべき状態なので、
 * 単なる表示の濃淡ではなく明示的な区分として持つ。
 */
export type Freshness = "fresh" | "aging" | "stale" | "unknown";

export function freshness(iso: string | null, now: number = Date.now()): Freshness {
  if (!iso) return "unknown";
  const elapsed = now - new Date(iso).getTime();
  if (!Number.isFinite(elapsed)) return "unknown";
  if (elapsed < 30 * MINUTE) return "fresh";
  if (elapsed < 3 * HOUR) return "aging";
  return "stale";
}

export const FRESHNESS_LABEL: Record<Freshness, string> = {
  fresh: "最新",
  aging: "やや古い",
  stale: "古い",
  unknown: "未受信",
};

/** 電池残量。端末が取得できなかった場合は -1 が来る。 */
export function batteryLabel(level: number): string | null {
  return level < 0 ? null : `${level}%`;
}

/** 測位誤差。数十メートル単位で丸めて、精度を過大に見せない。 */
export function accuracyLabel(meters: number): string {
  if (meters < 1000) return `誤差 約${Math.round(meters / 10) * 10}m`;
  return `誤差 約${(meters / 1000).toFixed(1)}km`;
}

/**
 * その測位を額面どおり信じてよいか（Issue #13）。
 *
 * **判定を 1 か所に置く。** 地図と端末カードで食い違うと、
 * 「地図では点線なのにカードは何も言わない」といった不整合が起きる。
 *
 * `null`（報告が無い）は **`true` に倒す。** 報告に対応する前のアプリと
 * iOS 版が該当し、それらを「疑わしい」と表示すると、実際には正しい
 * 大量の測位に警告が付いてしまう。
 */
export function isTrustedFix(source: LocationSourceKind | null): boolean {
  return source !== "network" && source !== "unknown";
}

/** 疑わしい測位に添える理由。信頼できる場合は null。 */
export function untrustedReason(source: LocationSourceKind | null): string | null {
  switch (source) {
    case "network":
      return "Wi-Fi・基地局からの推定です。実際の場所と大きく離れることがあります。";
    case "unknown":
      return "測位の出どころを判別できませんでした。";
    default:
      return null;
  }
}

/** 招待コードを読み上げやすいよう 4 文字ずつに区切る。 */
export function groupInviteCode(code: string): string {
  return code.length === 8 ? `${code.slice(0, 4)}-${code.slice(4)}` : code;
}
