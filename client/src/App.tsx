import { useCallback, useEffect, useMemo, useState } from "react";

import { api, ApiError, NetworkError } from "./api/client";
import type { DeviceLatest, HistoryEvent, LatestResponse, Profile } from "./api/types";
import { ConfirmDialog } from "./components/ConfirmDialog";
import { DeviceList } from "./components/DeviceList";
import { HistoryPanel } from "./components/HistoryPanel";
import { InvitePanel } from "./components/InvitePanel";
import { MapView } from "./components/MapView";
import { usePolling } from "./hooks/usePolling";
import { relativeTime } from "./lib/format";

/**
 * 最新位置を取り直す間隔。
 *
 * 端末は「位置が変わったときだけ」送ってくるので、これより細かく
 * 聞いても新しい情報は出てこない。タブが見えていない間は止まる。
 */
const POLL_INTERVAL_MS = 30_000;

export function App() {
  const [profile, setProfile] = useState<Profile | null>(null);
  const [bootError, setBootError] = useState<Error | null>(null);

  // 起動時に一度だけ「自分が誰でどの家族か」を確定させる。
  // ログイン画面は無い。認証は Cloudflare Access が済ませている (ADR-3)。
  useEffect(() => {
    const controller = new AbortController();
    void (async () => {
      try {
        setProfile(await api.me(controller.signal));
      } catch (e) {
        if (controller.signal.aborted) return;
        setBootError(e instanceof Error ? e : new Error(String(e)));
      }
    })();
    return () => controller.abort();
  }, []);

  if (bootError) return <BootFailure error={bootError} />;
  if (!profile) return <Splash />;
  return <Dashboard profile={profile} />;
}

function Dashboard({ profile }: { profile: Profile }) {
  const [selectedDeviceId, setSelectedDeviceId] = useState<string | null>(null);
  const [history, setHistory] = useState<HistoryEvent[] | null>(null);
  const [focusNonce, setFocusNonce] = useState(0);
  const [showInvite, setShowInvite] = useState(false);
  const [revokeTarget, setRevokeTarget] = useState<DeviceLatest | null>(null);
  const [revoking, setRevoking] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);

  const fetcher = useCallback(
    (signal: AbortSignal) => api.latest(profile.family_id, signal),
    [profile.family_id],
  );
  const { data, error, loading, updatedAt, refresh } = usePolling<LatestResponse>(
    fetcher,
    POLL_INTERVAL_MS,
  );

  const devices = useMemo(() => data?.devices ?? [], [data]);

  // 端末が1台だけなら選ぶ手間を省く。
  useEffect(() => {
    if (selectedDeviceId === null && devices.length === 1) {
      setSelectedDeviceId(devices[0]!.device_id);
    }
  }, [devices, selectedDeviceId]);

  const focusDevice = (deviceId: string) => {
    setSelectedDeviceId(deviceId);
    setFocusNonce((n) => n + 1);
  };

  const confirmRevoke = async () => {
    if (!revokeTarget) return;
    setRevoking(true);
    try {
      await api.revokeDevice(profile.family_id, revokeTarget.device_id);
      setNotice(`「${revokeTarget.device_name}」の接続を解除しました。`);
      setRevokeTarget(null);
      refresh();
    } catch (e) {
      setNotice(e instanceof ApiError ? e.message : "解除できませんでした。");
    } finally {
      setRevoking(false);
    }
  };

  return (
    <div className="layout">
      <header className="header">
        <div className="header__brand">
          <span className="header__mark" aria-hidden="true" />
          <h1 className="header__title">みまもり</h1>
        </div>
        <div className="header__meta">
          <span className="header__user">{profile.display_name} さん</span>
          <button className="btn btn--quiet" onClick={refresh}>
            更新
          </button>
        </div>
      </header>

      {error && <ConnectionBanner error={error} />}
      {notice && (
        <p className="alert alert--info alert--bar" role="status">
          {notice}
          <button className="alert__close" onClick={() => setNotice(null)} aria-label="閉じる">
            ×
          </button>
        </p>
      )}

      <main className="main">
        <div className="main__map">
          <MapView
            devices={devices}
            selectedDeviceId={selectedDeviceId}
            onSelectDevice={setSelectedDeviceId}
            history={history}
            focusNonce={focusNonce}
          />
        </div>

        <aside className="main__side">
          <section className="panel">
            <div className="panel__head">
              <h2 className="panel__title">見守り中の端末</h2>
              <button className="btn btn--primary btn--small" onClick={() => setShowInvite(true)}>
                招待コードを発行
              </button>
            </div>

            {loading ? (
              <p className="panel__hint">読み込んでいます…</p>
            ) : (
              <DeviceList
                devices={devices}
                selectedDeviceId={selectedDeviceId}
                onSelect={setSelectedDeviceId}
                onFocus={focusDevice}
                onRevoke={setRevokeTarget}
              />
            )}

            {updatedAt && (
              <p className="panel__updated">
                この画面の情報 {relativeTime(new Date(updatedAt).toISOString())}取得
              </p>
            )}
          </section>

          <HistoryPanel
            familyId={profile.family_id}
            devices={devices}
            deviceId={selectedDeviceId}
            onChange={setHistory}
          />
        </aside>
      </main>

      {showInvite && (
        <InvitePanel familyId={profile.family_id} onClose={() => setShowInvite(false)} />
      )}

      {revokeTarget && (
        <ConfirmDialog
          title="接続を解除しますか？"
          body={`「${revokeTarget.device_name}」からの位置情報を受け取らなくなります。再開するには招待コードの入力からやり直しが必要です。`}
          confirmLabel="解除する"
          destructive
          busy={revoking}
          onConfirm={confirmRevoke}
          onCancel={() => setRevokeTarget(null)}
        />
      )}
    </div>
  );
}

/**
 * 通信が途切れているときの帯。
 *
 * 表示中の位置は消さない。消すと「見守れていない」ように見えるが、
 * 実際には少し前の情報が手元にある状態なので、その旨だけを伝える。
 */
function ConnectionBanner({ error }: { error: Error }) {
  if (error instanceof ApiError && error.needsSignIn) {
    return (
      <p className="alert alert--warn alert--bar" role="alert">
        サインインの期限が切れました。
        <button className="btn btn--link" onClick={() => window.location.reload()}>
          再読み込みしてサインイン
        </button>
      </p>
    );
  }
  const message =
    error instanceof NetworkError || error instanceof ApiError
      ? error.message
      : "情報を更新できませんでした。";
  return (
    <p className="alert alert--warn alert--bar" role="alert">
      {message}（表示中の位置は最後に受け取ったものです）
    </p>
  );
}

function Splash() {
  return (
    <div className="centered">
      <p className="centered__text">読み込んでいます…</p>
    </div>
  );
}

function BootFailure({ error }: { error: Error }) {
  if (error instanceof ApiError && error.status === 403) {
    return (
      <div className="centered">
        <h1 className="centered__title">まだ利用が許可されていません</h1>
        <p className="centered__text">
          サインインはできましたが、このアカウントは見守りの対象として登録されていません。
          管理している家族の方に、あなたのメールアドレスの登録を依頼してください。
        </p>
      </div>
    );
  }

  if (error instanceof ApiError && error.needsSignIn) {
    return (
      <div className="centered">
        <h1 className="centered__title">サインインが必要です</h1>
        <button className="btn btn--primary" onClick={() => window.location.reload()}>
          再読み込みしてサインイン
        </button>
      </div>
    );
  }

  return (
    <div className="centered">
      <h1 className="centered__title">画面を表示できませんでした</h1>
      <p className="centered__text">
        {error instanceof NetworkError || error instanceof ApiError
          ? error.message
          : "予期しない問題が起きました。"}
      </p>
      <button className="btn btn--primary" onClick={() => window.location.reload()}>
        再読み込み
      </button>
    </div>
  );
}
