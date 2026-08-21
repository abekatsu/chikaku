import { useState } from "react";

import { api, ApiError } from "../api/client";
import type { InviteResponse } from "../api/types";
import { absoluteTime, groupInviteCode } from "../lib/format";

interface InvitePanelProps {
  familyId: string;
  onClose: () => void;
}

/**
 * 招待コードの発行。
 *
 * このコードは口頭や紙で親御さんに伝える前提なので、
 * 大きく・区切って・読み上げやすい形で出す。
 */
export function InvitePanel({ familyId, onClose }: InvitePanelProps) {
  const [invite, setInvite] = useState<InviteResponse | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [copied, setCopied] = useState(false);

  const issue = async () => {
    setBusy(true);
    setError(null);
    try {
      setInvite(await api.createInvite(familyId));
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "招待コードを発行できませんでした。");
    } finally {
      setBusy(false);
    }
  };

  const copy = async () => {
    if (!invite) return;
    try {
      await navigator.clipboard.writeText(invite.code);
      setCopied(true);
      window.setTimeout(() => setCopied(false), 2000);
    } catch {
      // クリップボードが使えない環境でも画面に出ているので致命ではない。
      setError("コピーできませんでした。画面の文字をお使いください。");
    }
  };

  return (
    <div className="modal-backdrop" role="dialog" aria-modal="true" aria-label="招待コードの発行">
      <div className="modal">
        <h2 className="modal__title">端末をつなぐ</h2>

        {!invite ? (
          <>
            <p className="modal__body">
              招待コードを発行します。親御さんのスマホでアプリを開き、
              表示されたコードを入力してもらってください。
            </p>
            <p className="modal__note">コードは一度しか使えません。有効期限は 24 時間です。</p>
            {error && <p className="alert alert--error">{error}</p>}
            <div className="modal__actions">
              <button className="btn btn--ghost" onClick={onClose}>
                やめる
              </button>
              <button className="btn btn--primary" onClick={issue} disabled={busy}>
                {busy ? "発行しています…" : "招待コードを発行"}
              </button>
            </div>
          </>
        ) : (
          <>
            <p className="modal__body">このコードを親御さんに伝えてください。</p>
            <p className="invite-code" aria-label={`招待コード ${[...invite.code].join(" ")}`}>
              {groupInviteCode(invite.code)}
            </p>
            <p className="modal__note">
              有効期限 {absoluteTime(invite.expires_at)} まで／一度使うと無効になります
            </p>
            {error && <p className="alert alert--error">{error}</p>}
            <div className="modal__actions">
              <button className="btn btn--ghost" onClick={copy}>
                {copied ? "コピーしました" : "コピー"}
              </button>
              <button className="btn btn--primary" onClick={onClose}>
                閉じる
              </button>
            </div>
          </>
        )}
      </div>
    </div>
  );
}
