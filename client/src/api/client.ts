import type {
  HistoryResponse,
  InviteResponse,
  LatestResponse,
  Profile,
} from "./types";

/**
 * API 呼び出しの失敗。`status` で場合分けできるようにしている。
 *
 * - 401: Cloudflare Access のセッション切れ。**再読み込みでサインインし直す**
 * - 403: Access は通ったが children_accounts に未登録
 * - 404: 他家族の ID を指した（存在を秘匿するため 403 ではなく 404）
 */
export class ApiError extends Error {
  readonly status: number;
  readonly code: string;

  constructor(status: number, code: string, message: string) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.code = code;
  }

  /** Access のサインインが切れている。画面を再読み込みすれば復帰する。 */
  get needsSignIn(): boolean {
    return this.status === 401;
  }
}

/** 通信そのものができなかった（オフライン等）。再試行の価値がある。 */
export class NetworkError extends Error {
  constructor(cause: unknown) {
    super("サーバーに接続できませんでした。通信状態をご確認ください。");
    this.name = "NetworkError";
    this.cause = cause;
  }
}

/**
 * 本番では Worker が同じオリジンで静的ファイルと API を配るため、
 * ベース URL は常に相対 (ADR-2)。開発時は Vite が転送する。
 */
const BASE = "/api/v1";

async function request<T>(
  path: string,
  init?: RequestInit & { signal?: AbortSignal },
): Promise<T> {
  let response: Response;
  try {
    response = await fetch(`${BASE}${path}`, {
      ...init,
      headers: {
        ...(init?.body ? { "Content-Type": "application/json" } : {}),
        ...init?.headers,
      },
      // Access の Cookie を必ず載せる。同一オリジンなので既定でも載るが、
      // 意図を明示しておく。
      credentials: "same-origin",
    });
  } catch (cause) {
    // fetch が例外になるのは通信不能のときだけ。HTTP エラーはここに来ない。
    throw new NetworkError(cause);
  }

  if (response.status === 204) {
    return undefined as T;
  }

  const text = await response.text();
  let body: unknown = null;
  if (text) {
    try {
      body = JSON.parse(text);
    } catch {
      body = null;
    }
  }

  if (!response.ok) {
    const parsed = body as Partial<ErrorShape> | null;
    throw new ApiError(
      response.status,
      parsed?.error ?? "unknown",
      parsed?.message ?? `サーバーが ${response.status} を返しました。`,
    );
  }

  return body as T;
}

interface ErrorShape {
  error: string;
  message: string;
}

export const api = {
  /** 自分が誰で、どの家族に属するか。起動時に一度だけ呼ぶ。 */
  me(signal?: AbortSignal): Promise<Profile> {
    return request<Profile>("/me", signal ? { signal } : {});
  },

  /** 家族の全端末の最新位置。ポーリングで繰り返し呼ぶ。 */
  latest(familyId: string, signal?: AbortSignal): Promise<LatestResponse> {
    return request<LatestResponse>(
      `/families/${encodeURIComponent(familyId)}/latest`,
      signal ? { signal } : {},
    );
  },

  /** 移動履歴。時刻は RFC 3339。 */
  history(
    familyId: string,
    params: { from?: string; to?: string; deviceId?: string; limit?: number },
    signal?: AbortSignal,
  ): Promise<HistoryResponse> {
    const query = new URLSearchParams();
    if (params.from) query.set("from", params.from);
    if (params.to) query.set("to", params.to);
    if (params.deviceId) query.set("device_id", params.deviceId);
    if (params.limit) query.set("limit", String(params.limit));
    const suffix = query.size > 0 ? `?${query}` : "";
    return request<HistoryResponse>(
      `/families/${encodeURIComponent(familyId)}/history${suffix}`,
      signal ? { signal } : {},
    );
  },

  /** 親端末をペアリングするための使い切りコードを発行する。 */
  createInvite(familyId: string): Promise<InviteResponse> {
    return request<InviteResponse>(
      `/families/${encodeURIComponent(familyId)}/invites`,
      { method: "POST" },
    );
  },

  /** 端末を紛失したときに、その端末のトークンだけを無効化する。 */
  revokeDevice(familyId: string, deviceId: string): Promise<void> {
    return request<void>(
      `/families/${encodeURIComponent(familyId)}/devices/${encodeURIComponent(deviceId)}/revoke`,
      { method: "POST" },
    );
  },
};
