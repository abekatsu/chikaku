import type { DeviceLatest } from "../api/types";
import {
  accuracyLabel,
  batteryLabel,
  FRESHNESS_LABEL,
  absoluteTime,
  freshness,
  relativeTime,
} from "../lib/format";

interface DeviceListProps {
  devices: DeviceLatest[];
  selectedDeviceId: string | null;
  onSelect: (deviceId: string) => void;
  onFocus: (deviceId: string) => void;
  onRevoke: (device: DeviceLatest) => void;
}

export function DeviceList({
  devices,
  selectedDeviceId,
  onSelect,
  onFocus,
  onRevoke,
}: DeviceListProps) {
  if (devices.length === 0) {
    return (
      <div className="empty">
        <p className="empty__title">まだ端末が登録されていません</p>
        <p className="empty__body">
          「招待コードを発行」を押して、表示されたコードを親御さんのスマホの
          アプリに入力してもらってください。
        </p>
      </div>
    );
  }

  return (
    <ul className="device-list">
      {devices.map((device) => (
        <DeviceCard
          key={device.device_id}
          device={device}
          selected={device.device_id === selectedDeviceId}
          onSelect={() => onSelect(device.device_id)}
          onFocus={() => onFocus(device.device_id)}
          onRevoke={() => onRevoke(device)}
        />
      ))}
    </ul>
  );
}

interface DeviceCardProps {
  device: DeviceLatest;
  selected: boolean;
  onSelect: () => void;
  onFocus: () => void;
  onRevoke: () => void;
}

function DeviceCard({ device, selected, onSelect, onFocus, onRevoke }: DeviceCardProps) {
  const fix = device.latest;
  const state = freshness(fix?.recorded_at ?? null);
  const battery = fix ? batteryLabel(fix.battery_level) : null;

  return (
    <li className={`device${selected ? " device--selected" : ""}`}>
      <button className="device__main" onClick={onSelect} aria-pressed={selected}>
        <div className="device__head">
          <span className="device__name">{device.device_name}</span>
          <span className={`chip chip--${state}`}>{FRESHNESS_LABEL[state]}</span>
        </div>

        {fix ? (
          <>
            <div className="device__when">
              <strong>{relativeTime(fix.recorded_at)}</strong>
              <span className="device__at">{absoluteTime(fix.recorded_at)}</span>
            </div>
            <dl className="device__facts">
              <div>
                <dt>測位精度</dt>
                <dd>{accuracyLabel(fix.accuracy)}</dd>
              </div>
              <div>
                <dt>電池</dt>
                <dd>{battery ?? "不明"}</dd>
              </div>
            </dl>
            {/*
              測位した時刻と、サーバーに届いた時刻がずれているのは
              圏外でキューに溜まっていたことを意味する。見守る側にとっては
              「連絡が取れていなかった」情報なので、隠さず出す。
            */}
            {isDelayed(fix.recorded_at, fix.received_at) && (
              <p className="device__note">
                この位置は {relativeTime(fix.received_at)}に届きました（圏外だった可能性があります）
              </p>
            )}
          </>
        ) : (
          <p className="device__none">まだ位置情報を受け取っていません</p>
        )}

        {device.last_seen_at && (
          <p className="device__seen">最後の通信 {relativeTime(device.last_seen_at)}</p>
        )}
      </button>

      <div className="device__actions">
        <button className="btn btn--quiet" onClick={onFocus} disabled={!fix}>
          地図で見る
        </button>
        <button className="btn btn--danger-quiet" onClick={onRevoke}>
          接続を解除
        </button>
      </div>
    </li>
  );
}

/** 5 分以上の開きがあれば「遅れて届いた」とみなす。 */
function isDelayed(recordedAt: string, receivedAt: string): boolean {
  const gap = new Date(receivedAt).getTime() - new Date(recordedAt).getTime();
  return Number.isFinite(gap) && gap > 5 * 60_000;
}
