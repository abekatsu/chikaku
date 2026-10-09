import { describe, expect, it } from "vitest";
import {
  distanceMeters,
  distrustReason,
  isSpike,
  isTrustedFix,
  looksDatabaseAccuracy,
  trustedHistory,
  type TrustInput,
} from "./trust";

/**
 * 精度の値と点の並びは本番データから取っている (Issue #16 の分析)。
 * ここを「それらしい値」に書き換えると、実機で起きたことを再現しなくなる。
 *
 * **座標そのものは架空の位置に置き換えてある。** 元データは実在の住居で、
 * 小数第 6 位まで書くと 0.1m まで特定できてしまう。このリポジトリは公開する。
 * 置き換えにあたって **点と点の距離は実測どおりに保ってある**（33km の跳び、
 * 57m の揺れ、158m の離れなど）。判定が見ているのは距離と精度の値だけなので、
 * テストの意味は変わらない。**実データの座標に戻さないこと。**
 *
 * 基準点はこのリポジトリが他のテストでも使っている東京駅。
 *
 * check-private: synthetic
 */

const fix = (lat: number, lng: number, accuracy: number, source: TrustInput["source"] = null) => ({
  lat,
  lng,
  accuracy,
  source,
});

/** 静止中の端末が繰り返し返す位置。実データでは居住地だった。 */
const STATIONARY = { lat: 35.6812, lng: 139.7671 };

describe("looksDatabaseAccuracy", () => {
  it("実データに出た離散値は全部拾う", () => {
    for (const a of [100, 200, 300, 400, 500]) expect(looksDatabaseAccuracy(a)).toBe(true);
  });

  it("衛星測位の精度は実数で、拾わない", () => {
    for (const a of [9.965, 22.5, 26.4, 56.1, 82.5, 87.6, 104.1]) {
      expect(looksDatabaseAccuracy(a)).toBe(false);
    }
  });

  it("整数でも 100 の倍数でなければ拾わない", () => {
    // 8, 20, 30, 60, 110 は実データにある整数値。GPS 由来の点にも整数は出る。
    for (const a of [8, 20, 30, 60, 110]) expect(looksDatabaseAccuracy(a)).toBe(false);
  });

  it("将来 1000 や 2000 が出ても同じ性質とみなす", () => {
    expect(looksDatabaseAccuracy(1000)).toBe(true);
    expect(looksDatabaseAccuracy(2000)).toBe(true);
  });
});

describe("isSpike — 2026-09-03 15:41:58 に 33km 跳んだ点", () => {
  // 15:26:15 実際の位置 → 15:41:58 33km 先 → 15:43:46 元の位置（57m 以内）
  const before = fix(35.80696, 139.86663, 7.3);
  const away = fix(35.72611, 139.51218, 56.1);
  const after = fix(35.8066, 139.86618, 3.8);

  it("accuracy 56.1 は離散値ではないので、誤差の形では拾えない", () => {
    expect(looksDatabaseAccuracy(away.accuracy)).toBe(false);
  });

  it("前後を見れば拾える", () => {
    expect(distanceMeters(before, away)).toBeGreaterThan(30_000);
    expect(distanceMeters(before, after)).toBeLessThan(100);
    expect(isSpike(before, away, after)).toBe(true);
  });

  it("前後の点自身はスパイクではない", () => {
    expect(isSpike(undefined, before, away)).toBe(false);
    expect(isSpike(away, after, undefined)).toBe(false);
  });

  it("前後どちらかが無ければ判定しない（最新位置ではこの理由で疑えない）", () => {
    expect(isSpike(before, away, undefined)).toBe(false);
    expect(isSpike(undefined, away, after)).toBe(false);
  });
});

describe("isSpike — 本当に遠くへ行った場合は拾わない", () => {
  it("行って戻らない移動はスパイクではない", () => {
    // 前後の点同士が離れていれば、本人が動いたということ。
    const start = fix(STATIONARY.lat, STATIONARY.lng, 15);
    const midway = fix(35.8609, 139.9164, 12);
    const far = fix(36.292, 140.243, 10);
    expect(isSpike(start, midway, far)).toBe(false);
  });
});

describe("distrustReason", () => {
  it("2026-08-29 の既知の誤り 10 点は、すべて誤差の形で拾える", () => {
    // 10 点はいくつかの座標を行き来するだけで、精度は 100 か 300 しか出なかった。
    const points = [300, 100, 100, 300, 100, 300, 100, 300, 100, 300].map((a) =>
      fix(35.82493, 139.84451, a),
    );
    for (const p of points) expect(distrustReason(p)).toBe("database_accuracy");
  });

  it("静止中に最もよく記録される点は、network 測位だが信じてよい", () => {
    // 精度 22.5 は network プロバイダの出力と一致した。場所としては正しい。
    expect(distrustReason(fix(STATIONARY.lat, STATIONARY.lng, 22.5, "satellite"))).toBeNull();
    expect(distrustReason(fix(STATIONARY.lat, STATIONARY.lng, 22.5, null))).toBeNull();
  });

  it("自己申告の satellite は、誤差の形が離散値なら信じない", () => {
    // 561 件の satellite 判定は根拠が壊れていた。申告より形を優先する。
    expect(distrustReason(fix(35.682206, 139.768339, 300, "satellite"))).toBe("database_accuracy");
  });

  it("自己申告の network / unknown は、他に理由が無くても疑う", () => {
    expect(distrustReason(fix(35.6821, 139.7682, 45.2, "network"))).toBe("reported_network");
    expect(distrustReason(fix(35.6821, 139.7682, 45.2, "unknown"))).toBe("reported_unknown");
  });

  it("報告が無い (null) だけでは疑わない", () => {
    // 報告に対応する前のアプリと iOS 版。正しい測位に警告を付けてはいけない。
    expect(distrustReason(fix(35.6821, 139.7682, 12.3, null))).toBeNull();
    expect(isTrustedFix(fix(35.6821, 139.7682, 12.3, null))).toBe(true);
  });
});

describe("trustedHistory", () => {
  it("履歴の中のスパイクだけを疑い、前後は信じる", () => {
    const history = [
      fix(35.80696, 139.86663, 7.3),
      fix(35.72611, 139.51218, 56.1), // 33km 先へ跳んで戻る
      fix(35.8066, 139.86618, 3.8),
      fix(35.80346, 139.86403, 7),
    ];
    expect(trustedHistory(history)).toEqual([true, false, true, true]);
  });

  it("離散値の精度はスパイクでなくても疑う", () => {
    const history = [
      fix(STATIONARY.lat, STATIONARY.lng, 22.5),
      // 158m しか離れておらず場所は大きく外れていないが、形は database
      fix(35.682206, 139.768339, 300),
      fix(STATIONARY.lat, STATIONARY.lng, 26.4),
    ];
    expect(trustedHistory(history)).toEqual([true, false, true]);
  });

  it("空の履歴", () => {
    expect(trustedHistory([])).toEqual([]);
  });
});
