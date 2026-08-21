# chikaku-server

高齢者見守り位置情報システムのバックエンド（CLAUDE.md §3）。
Android アプリからの位置情報を受け取って保存し、子側ダッシュボードに配信する。

- Rust / Axum 0.8 / SQLite (sqlx)
- 認証はサーバー保持のトークン。パスワードは Argon2id
- 位置履歴は保持期間（既定 90 日）で自動削除

FCM プッシュ通知は未実装。MVP（フェーズ1）はダッシュボードのポーリングで
成立するため、リアルタイム化はフェーズ2で足す。

## 動かす

```sh
cp .env.example .env          # 必要なら編集
cargo run -- create-family \
  --family-name "我が家" \
  --email "child@example.com" \
  --display-name "長男" \
  --password "12文字以上のパスワード"
cargo run                     # 既定で serve
```

`create-family` が出力する `family_id` はダッシュボードから使う。

サインアップ用の公開エンドポイントは意図的に無い。家族数人で使う前提なので、
誰でもアカウントを作れる口を開けるより運用者が CLI で作るほうが安全。
きょうだいの追加は `add-child --family-id <id> ...`。

パスワードをシェル履歴に残したくない場合は `CHIKAKU_ADMIN_PASSWORD` で渡せる。

### TLS

このサーバーは TLS を終端しない。CLAUDE.md §5 の「通信は全て TLS 必須」は
前段のリバースプロキシ（nginx / Caddy）で満たす前提で、既定の待ち受けを
`127.0.0.1` にしてプロキシを経由せずに外へ出ないようにしている。
`CHIKAKU_BIND` を `0.0.0.0` にすると平文が外部に晒されるので注意。

Android アプリは release ビルドで `https` 以外の送信先を拒否する。

## API

エラー本文は全て `{"error": "<機械可読コード>", "message": "<日本語>"}`。
アプリは 4xx のとき `message` を高齢の利用者にそのまま表示するため、
この文言に専門用語を入れない。

### 親端末（Android）

`Authorization: Bearer <device_token>`

| メソッド | パス | 用途 |
|---|---|---|
| POST | `/api/v1/devices/register` | 招待コードで端末を登録（認証不要） |
| POST | `/api/v1/location` | 位置情報を 1 件送る |

`POST /api/v1/devices/register`

```json
→ {"invite_code": "NX6DC7VC", "device_name": "お父さんのスマホ", "device_model": "Pixel 9"}
← 200 {"device_id": "...", "device_token": "...", "family_id": "..."}
```

`device_token` は原文をこの応答で一度だけ返す。サーバーには SHA-256 しか残らない。
招待コードは大文字小文字とハイフン・空白を無視して照合する（読み上げて入力する運用のため）。

`POST /api/v1/location`

```json
→ {"device_id":"...","lat":35.6812,"lng":139.7671,"accuracy":18.0,
   "timestamp":"2026-08-21T08:36:03.143Z","battery_level":62}
← 202 {"stored": true}
```

`stored: false` は重複排除で既存行に畳まれたことを示す。端末側の挙動は変わらない。

### 子アカウント（ダッシュボード）

`Authorization: Bearer <session token>`

| メソッド | パス | 用途 |
|---|---|---|
| POST | `/api/v1/auth/login` | ログイン（認証不要） |
| POST | `/api/v1/auth/logout` | 今のセッションだけ失効 |
| GET | `/api/v1/auth/me` | セッションの有効性確認 |
| GET | `/api/v1/families/{family_id}/latest` | 全端末の最新位置 |
| GET | `/api/v1/families/{family_id}/history` | 移動履歴 |
| POST | `/api/v1/families/{family_id}/invites` | 招待コード発行 |
| POST | `/api/v1/families/{family_id}/devices/{device_id}/revoke` | 端末を無効化 |

`history` のクエリ: `from` `to`（RFC 3339、省略時は直近 24 時間）、
`device_id`（1 端末に絞る）、`limit`（既定 1000 / 上限 5000）。
上限で打ち切られた場合は応答の `truncated` が `true` になる。

`revoke` は端末を紛失したとき用。アプリの「接続を解除する」は端末内のデータを
消すだけでトークンは生きたままなので、サーバー側にもこの操作が要る。

### ステータスコードの意味

アプリの再送ロジック（`ApiClient.kt` / `UploadWorker.kt`）がこれに依存している。

| コード | サーバーが返す状況 | アプリの挙動 |
|---|---|---|
| 202 | 位置情報を受理 | キューから削除 |
| 400 | 時刻・座標・電池残量が不正 | 再試行を打ち切って捨てる |
| 401 | トークン失効、招待コード不正、他端末になりすまし | ペアリングし直し |
| 404 | 他家族の ID を指した | ― |
| 5xx | サーバー側の問題 | 時間をおいて再送 |

他家族の `family_id` を指したとき 403 ではなく 404 を返すのは、
その ID が実在するという事実自体を漏らさないため（CLAUDE.md §5）。

## 設計上の判断

**時刻は UTC の Unix エポックミリ秒（INTEGER）で保存する。**
SQLite の TEXT 日時は表記ゆれ（秒精度・オフセット表記）で大小比較が壊れ、
履歴の範囲検索が静かに間違う。JSON では RFC 3339 に変換して出す。

**重複排除を `(device_id, recorded_at)` の一意制約で行う。**
アプリはオフライン時にキューを持ち WorkManager で再送するため、
応答だけが失われた再送で同じ測位が二度届きうる。

**sqlx のコンパイル時検証マクロ（`query!`）を使わず実行時検証のクエリにしている。**
`query!` はビルド時に DB か `.sqlx` オフラインキャッシュを要求し、
そのために sqlx-cli の導入が前提になる。型の取り違えはテストで捕まえる。

**保持期間の掃除は 6 時間ごとの常駐タスク。**
`cargo run -- sweep` で手動実行もできる。位置履歴に加えて、失効セッションと
使われなかった招待コードも落とす。

## 開発

```sh
cargo test                              # 単体 6 + 結合 23
cargo clippy --all-targets -- -D warnings
cargo fmt --check
```

結合テストは Android 側が前提にしている契約 ―― ステータスコードの意味と
JSON の形 ―― を固定している。アプリの `ApiClient.kt` を変える際は
`tests/api.rs` も併せて見ること。

## 未実装

- FCM HTTP v1 でのプッシュ通知（フェーズ2）
- ジオフェンス通過イベントの受信（フェーズ3）
- パスワード変更・リセット
- レート制限（招待コードの総当たり対策は現状「短い有効期限」のみ）
