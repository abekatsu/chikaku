interface ConfirmDialogProps {
  title: string;
  body: string;
  confirmLabel: string;
  destructive?: boolean;
  busy?: boolean;
  onConfirm: () => void;
  onCancel: () => void;
}

export function ConfirmDialog({
  title,
  body,
  confirmLabel,
  destructive = false,
  busy = false,
  onConfirm,
  onCancel,
}: ConfirmDialogProps) {
  return (
    <div className="modal-backdrop" role="dialog" aria-modal="true" aria-label={title}>
      <div className="modal">
        <h2 className="modal__title">{title}</h2>
        <p className="modal__body">{body}</p>
        <div className="modal__actions">
          <button className="btn btn--ghost" onClick={onCancel} disabled={busy}>
            キャンセル
          </button>
          <button
            className={`btn ${destructive ? "btn--danger" : "btn--primary"}`}
            onClick={onConfirm}
            disabled={busy}
          >
            {busy ? "処理しています…" : confirmLabel}
          </button>
        </div>
      </div>
    </div>
  );
}
