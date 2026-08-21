-- 高齢者見守りシステム 初期スキーマ (CLAUDE.md §3.4)
--
-- 時刻は全て「UTC の Unix エポックミリ秒 (INTEGER)」で保持する。
-- SQLite の TEXT 日時は表記ゆれ（秒精度・オフセット表記）で
-- 大小比較が壊れるため、履歴の範囲検索を安全に行える整数表現を採る。
-- PostgreSQL へ移す際は BIGINT のまま移すか to_timestamp() で変換できる。

CREATE TABLE families (
    id         TEXT    PRIMARY KEY,
    name       TEXT    NOT NULL,
    created_at INTEGER NOT NULL
);

CREATE TABLE children_accounts (
    id            TEXT    PRIMARY KEY,
    family_id     TEXT    NOT NULL REFERENCES families(id) ON DELETE CASCADE,
    email         TEXT    NOT NULL,
    display_name  TEXT    NOT NULL,
    -- Argon2id の PHC 文字列。ソルトとパラメータを内包する。
    password_hash TEXT    NOT NULL,
    created_at    INTEGER NOT NULL
);

-- 大文字小文字を無視して一意にする（ログイン時も lower() で引く）
CREATE UNIQUE INDEX idx_children_email ON children_accounts (lower(email));
CREATE INDEX idx_children_family ON children_accounts (family_id);

-- 子アカウントのログインセッション。
-- トークン原文は保存せず SHA-256 のみ持つ（DB 流出時に成りすませないようにする）。
CREATE TABLE child_sessions (
    token_hash TEXT    PRIMARY KEY,
    child_id   TEXT    NOT NULL REFERENCES children_accounts(id) ON DELETE CASCADE,
    created_at INTEGER NOT NULL,
    expires_at INTEGER NOT NULL
);

CREATE INDEX idx_sessions_expiry ON child_sessions (expires_at);
CREATE INDEX idx_sessions_child ON child_sessions (child_id);

-- 親端末。device_token も原文は保存しない。
CREATE TABLE parent_devices (
    id           TEXT    PRIMARY KEY,
    family_id    TEXT    NOT NULL REFERENCES families(id) ON DELETE CASCADE,
    device_name  TEXT    NOT NULL,
    device_model TEXT    NOT NULL,
    token_hash   TEXT    NOT NULL UNIQUE,
    created_at   INTEGER NOT NULL,
    last_seen_at INTEGER,
    revoked_at   INTEGER
);

CREATE INDEX idx_devices_family ON parent_devices (family_id);

-- ペアリング用の招待コード。子アカウントが発行し、親が端末で入力する。
CREATE TABLE invite_codes (
    code           TEXT    PRIMARY KEY,
    family_id      TEXT    NOT NULL REFERENCES families(id) ON DELETE CASCADE,
    created_by     TEXT             REFERENCES children_accounts(id) ON DELETE SET NULL,
    created_at     INTEGER NOT NULL,
    expires_at     INTEGER NOT NULL,
    used_at        INTEGER,
    used_by_device TEXT             REFERENCES parent_devices(id) ON DELETE SET NULL
);

CREATE INDEX idx_invites_family ON invite_codes (family_id);

CREATE TABLE location_events (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    device_id     TEXT    NOT NULL REFERENCES parent_devices(id) ON DELETE CASCADE,
    -- family_id は parent_devices から辿れるが、参照制御と履歴検索が
    -- 必ずこの列を通るため非正規化して持たせ、単一インデックスで引けるようにする。
    family_id     TEXT    NOT NULL REFERENCES families(id) ON DELETE CASCADE,
    lat           REAL    NOT NULL,
    lng           REAL    NOT NULL,
    accuracy      REAL    NOT NULL,
    -- 端末が測位した時刻
    recorded_at   INTEGER NOT NULL,
    -- サーバーが受信した時刻。圏外キューの滞留を見るために分けて持つ。
    received_at   INTEGER NOT NULL,
    battery_level INTEGER NOT NULL
);

-- アプリはオフライン時にキューを持ち WorkManager で再送するため、
-- 応答が失われた再送で重複が入りうる。同一端末・同一測位時刻は 1 件に畳む。
CREATE UNIQUE INDEX idx_events_dedupe ON location_events (device_id, recorded_at);
CREATE INDEX idx_events_family_time ON location_events (family_id, recorded_at DESC);
CREATE INDEX idx_events_retention ON location_events (recorded_at);
