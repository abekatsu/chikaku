import { useCallback, useEffect, useRef, useState } from "react";

import { ApiError, NetworkError } from "../api/client";

export interface PollingState<T> {
  data: T | null;
  error: Error | null;
  /** 初回読み込み中。2回目以降の更新では立てない（画面がちらつくため）。 */
  loading: boolean;
  /** 最後に取得できた時刻。「いつの情報か」を出すために使う。 */
  updatedAt: number | null;
  refresh: () => void;
}

/**
 * 一定間隔で取得を繰り返す。
 *
 * MVP はプッシュ通知を使わずポーリングで成立させる（CLAUDE.md §6 フェーズ1）。
 * ただし**タブが見えていない間は止める**。見ていない画面のために
 * 通信し続ける必要はないし、Workers の呼び出し数も無駄に増える。
 */
export function usePolling<T>(
  fetcher: (signal: AbortSignal) => Promise<T>,
  intervalMs: number,
  enabled = true,
): PollingState<T> {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<Error | null>(null);
  const [loading, setLoading] = useState(true);
  const [updatedAt, setUpdatedAt] = useState<number | null>(null);
  const [tick, setTick] = useState(0);

  // fetcher は毎レンダリングで作り直されることが多いので、
  // 実体を ref に逃がして effect の再実行トリガーから外す。
  const fetcherRef = useRef(fetcher);
  fetcherRef.current = fetcher;

  const refresh = useCallback(() => setTick((n) => n + 1), []);

  useEffect(() => {
    if (!enabled) return;

    const controller = new AbortController();
    let cancelled = false;

    const load = async () => {
      try {
        const result = await fetcherRef.current(controller.signal);
        if (cancelled) return;
        setData(result);
        setError(null);
        setUpdatedAt(Date.now());
      } catch (e) {
        if (cancelled || controller.signal.aborted) return;
        // 一時的な通信断で、それまで表示していた位置を消してしまうと
        // 「見守れていない」ように見える。data は残したままエラーだけ出す。
        if (e instanceof ApiError || e instanceof NetworkError) {
          setError(e);
        } else {
          setError(e instanceof Error ? e : new Error(String(e)));
        }
      } finally {
        if (!cancelled) setLoading(false);
      }
    };

    void load();

    const timer = window.setInterval(() => {
      if (document.visibilityState === "visible") void load();
    }, intervalMs);

    // 画面に戻ってきたら間隔を待たずに取り直す。
    const onVisible = () => {
      if (document.visibilityState === "visible") void load();
    };
    document.addEventListener("visibilitychange", onVisible);

    return () => {
      cancelled = true;
      controller.abort();
      window.clearInterval(timer);
      document.removeEventListener("visibilitychange", onVisible);
    };
  }, [intervalMs, enabled, tick]);

  return { data, error, loading, updatedAt, refresh };
}
