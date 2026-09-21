/**
 * サーバーが返す JSON の形。`server/src/routes/*.rs` の Serialize 構造体と対。
 * **サーバー側を変えたらここも変える。**
 */

export interface Profile {
  child_id: string;
  family_id: string;
  email: string;
  display_name: string;
}

/** 1回の測位。 */
export interface Fix {
  lat: number;
  lng: number;
  /** 測位誤差の半径（メートル） */
  accuracy: number;
  /** 端末が測位した時刻 (RFC 3339) */
  recorded_at: string;
  /** サーバーが受信した時刻。圏外で溜まっていた分はここが大きく遅れる。 */
  received_at: string;
  /** 取得できなかった場合は -1 */
  battery_level: number;
  /**
   * 測位の出どころ（Issue #13）。`satellite` / `network` / `unknown`。
   * **null は「衛星測位だった」ではなく「報告が無い」。**
   * 報告に対応する前のアプリと iOS 版は送ってこない。
   */
  source: LocationSourceKind | null;
}

/**
 * 測位の出どころ（端末の自己申告）。
 *
 * `network`（Wi-Fi・基地局）は**実測ではなくデータベース上の登録位置**で、
 * アクセスポイントが移動していると大きく外れる。実際、100m を自称しながら
 * 7km 外した測位が記録された。
 *
 * **ただしこの申告は当てにならない (Issue #16)。** 端末側の判定根拠
 * （高度・速度・方位の有無）がどれも出どころを区別せず、1,770 件中
 * `network` は 4 件しか付かなかった。表示の根拠は `lib/trust.ts` に移し、
 * 精度の申告の形（100 / 200 / 300 ちょうど）と前後の点との関係で判定する。
 * この値は残すが、主役ではない。
 */
export type LocationSourceKind = "satellite" | "network" | "unknown";

/**
 * 親端末の設定のうち、見守りの成否を左右するもの（Issue #4）。
 * 端末が位置情報と一緒に報告する。
 */
export interface DeviceHealth {
  /** 電池の最適化から除外されているか。false だと位置が数十分遅れて届く。 */
  battery_unrestricted: boolean;
  /** 常駐通知を表示できるか。false だと親が動作を確認できない。 */
  notifications_enabled: boolean;
  /** 位置情報が「常に許可」か。false だと画面を消した間の測位が止まる。 */
  background_location: boolean;
  /** この状態を受け取った時刻 (RFC 3339) */
  reported_at: string;
}

export interface DeviceLatest {
  device_id: string;
  device_name: string;
  device_model: string;
  /** 最後にサーバーと通信できた時刻。位置が動かなくても更新される。 */
  last_seen_at: string | null;
  /** 一度も送信していない端末では null */
  latest: Fix | null;
  /**
   * 端末設定の健康状態。報告に対応する前のアプリでは null。
   * **null は「問題なし」ではなく「分からない」。** 警告を出してはいけない。
   */
  health: DeviceHealth | null;
}

export interface LatestResponse {
  family_id: string;
  devices: DeviceLatest[];
}

export interface HistoryEvent {
  device_id: string;
  lat: number;
  lng: number;
  accuracy: number;
  recorded_at: string;
  battery_level: number;
  /** **null は「報告が無い」。** [[Fix.source]] を参照。 */
  source: LocationSourceKind | null;
}

export interface HistoryResponse {
  family_id: string;
  from: string;
  to: string;
  /** 上限に達して打ち切られたか。true なら範囲を狭めて引き直す。 */
  truncated: boolean;
  events: HistoryEvent[];
}

export interface InviteResponse {
  code: string;
  expires_at: string;
}

/** エラー本文。`message` は日本語でそのまま表示してよい。 */
export interface ErrorBody {
  error: string;
  message: string;
}
