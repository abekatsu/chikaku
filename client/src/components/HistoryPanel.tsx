import { useEffect, useState } from "react";

import { api, ApiError, NetworkError } from "../api/client";
import type { DeviceLatest, HistoryEvent } from "../api/types";
import { timeOnly } from "../lib/format";

/** 期間の選択肢。どれも「昨日どこに行ったか」を追える粒度に寄せている。 */
const RANGES = [
  { id: "off", label: "表示しない", hours: 0 },
  { id: "6h", label: "6時間", hours: 6 },
  { id: "24h", label: "24時間", hours: 24 },
  { id: "3d", label: "3日間", hours: 72 },
] as const;

type RangeId = (typeof RANGES)[number]["id"];

interface HistoryPanelProps {
  familyId: string;
  devices: DeviceLatest[];
  /** 経路は1端末ぶんだけ描く。複数を重ねると読めなくなるため。 */
  deviceId: string | null;
  onChange: (events: HistoryEvent[] | null) => void;
}

export function HistoryPanel({ familyId, devices, deviceId, onChange }: HistoryPanelProps) {
  const [range, setRange] = useState<RangeId>("off");
  const [events, setEvents] = useState<HistoryEvent[] | null>(null);
  const [truncated, setTruncated] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  const selectedDevice = devices.find((d) => d.device_id === deviceId) ?? null;

  useEffect(() => {
    const hours = RANGES.find((r) => r.id === range)?.hours ?? 0;
    if (hours === 0 || !deviceId) {
      setEvents(null);
      setTruncated(false);
      setError(null);
      onChange(null);
      return;
    }

    const controller = new AbortController();
    let cancelled = false;
    setLoading(true);

    void (async () => {
      try {
        const to = new Date();
        const from = new Date(to.getTime() - hours * 3_600_000);
        const result = await api.history(
          familyId,
          { from: from.toISOString(), to: to.toISOString(), deviceId, limit: 5000 },
          controller.signal,
        );
        if (cancelled) return;
        setEvents(result.events);
        setTruncated(result.truncated);
        setError(null);
        onChange(result.events);
      } catch (e) {
        if (cancelled || controller.signal.aborted) return;
        setError(
          e instanceof ApiError || e instanceof NetworkError
            ? e.message
            : "履歴を取得できませんでした。",
        );
        setEvents(null);
        onChange(null);
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();

    return () => {
      cancelled = true;
      controller.abort();
    };
    // onChange は親で毎回作り直されるため依存に入れない（無限ループになる）。
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [familyId, deviceId, range]);

  return (
    <section className="panel">
      <h2 className="panel__title">移動の履歴</h2>

      {!deviceId ? (
        <p className="panel__hint">端末を選ぶと、その端末の移動経路を地図に重ねられます。</p>
      ) : (
        <>
          <p className="panel__hint">
            <strong>{selectedDevice?.device_name}</strong> の経路をさかのぼって表示します。
          </p>
          <div className="segmented" role="group" aria-label="表示する期間">
            {RANGES.map((r) => (
              <button
                key={r.id}
                className={`segmented__item${range === r.id ? " segmented__item--on" : ""}`}
                aria-pressed={range === r.id}
                onClick={() => setRange(r.id)}
              >
                {r.label}
              </button>
            ))}
          </div>

          {loading && <p className="panel__hint">読み込んでいます…</p>}
          {error && <p className="alert alert--error">{error}</p>}

          {events && !loading && (
            <p className="panel__result">
              {events.length === 0 ? (
                "この期間に記録された移動はありません。"
              ) : (
                <>
                  {events.length} 件の記録（
                  {timeOnly(events[0]!.recorded_at)} 〜{" "}
                  {timeOnly(events[events.length - 1]!.recorded_at)}）
                </>
              )}
            </p>
          )}

          {truncated && (
            <p className="alert alert--warn">
              件数が多いため途中で打ち切られています。期間を短くしてください。
            </p>
          )}
        </>
      )}
    </section>
  );
}
