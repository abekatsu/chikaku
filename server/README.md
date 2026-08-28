# chikaku-server

高齢者見守り位置情報システムのバックエンド（CLAUDE.md §3）。
Cloudflare Workers 上で動く Rust (workers-rs) の Worker。

- **同じ Worker がダッシュボードの静的ファイルも配る**（単一オリジン、ADR-2）
- データストアは D1（SQLite 互換、ADR-4）
- 子アカウントの認証は Cloudflare Access に委ねる（ADR-3）
- 位置履歴は保持期間（既定 90 日）で Cron Trigger により自動削除

構成上の判断とその理由は [`docs/architecture-decisions.md`](../docs/architecture-decisions.md)、
認証の詳細は [`docs/authentication.md`](../docs/authentication.md) を参照。

FCM プッシュ通知は未実装（フェーズ2）。

## 前提

| | |
|---|---|
| Node.js | **22 以上**（wrangler 4 の要求）。リポジトリ直下の `.tool-versions` で固定済み |
| Rust | `rustup target add wasm32-unknown-unknown` |
| worker-build | `cargo install worker-build`（0.8.1 以上） |

## セットアップ

### 0. 設定ファイルを用意する

**`wrangler.jsonc` は追跡していない。** アカウント固有の値が入るため、
`local.properties` や `Chikaku.xcconfig` と同じ扱いにしてある。

```sh
cp wrangler.jsonc.example wrangler.jsonc
```

`REPLACE_WITH_` で始まる3箇所を、以下の手順で得た値に差し替える。

### 1. D1 データベースを作る

```sh
npx wrangler d1 create chikaku
```

出力された `database_id` を `wrangler.jsonc`（リポジトリ直下）の
`REPLACE_WITH_D1_DATABASE_ID` に書き込む。

```sh
npm run db:migrate     # wrangler d1 migrations apply chikaku --remote
```

### 2. Cloudflare Access を設定する

Zero Trust > Access > Applications で **self-hosted アプリケーション**を作る。

| # | ドメイン / パス | ポリシー |
|---|---|---|
| 1 | `<host>/api/v1/devices/register` | **Bypass**（全員） |
| 2 | `<host>/api/v1/location` | **Bypass**（全員） |
| 3 | `<host>/api/v1/healthz` | **Bypass**（全員） |
| 4 | `<host>` | Allow（子アカウントのメールアドレスを列挙） |

**1〜3 のバイパスは必須。** Android アプリは対話的ログインができないため、
Access がサインインを要求すると動かなくなる。これらは招待コードと
`device_token` で守られている（ADR-3）。

アプリケーション 4 の **AUD タグ**と**チームドメイン**を `wrangler.jsonc` の
`vars`（`REPLACE_WITH_ACCESS_APPLICATION_AUD` と `REPLACE_WITH_TEAM`）に書き込む。

```jsonc
"vars": {
  "CHIKAKU_TEAM_DOMAIN": "https://<team>.cloudflareaccess.com",
  "CHIKAKU_POLICY_AUD": "<AUD タグ>"
}
```

これらが誤っていると JWT 検証が通らず、ダッシュボードが全て 401 になる。

### 3. 家族と子アカウントを登録する

Workers に CLI は置けないため、`wrangler d1 execute` で直接入れる。
パスワードは無い（Access が認証するため）。**ここに書いたメールアドレスと
Access のポリシーの両方に載っている人だけがアクセスできる。**

```sh
npx wrangler d1 execute chikaku --remote --command "
  INSERT INTO families (id, name, created_at)
  VALUES (lower(hex(randomblob(16))), '我が家', unixepoch() * 1000);
"

npx wrangler d1 execute chikaku --remote --command "
  INSERT INTO children_accounts (id, family_id, email, display_name, created_at)
  SELECT lower(hex(randomblob(16))), id, 'child@example.com', '長男', unixepoch() * 1000
  FROM families WHERE name = '我が家';
"
```

**きょうだいを後から足す場合は、この INSERT だけでは足りない。**
Access のポリシーにも同じメールアドレスを載せる必要がある。
手順は [運用 — 子アカウントを追加する](#子アカウントを追加する) を見ること。

### 4. デプロイ

```sh
npm --prefix client run build   # 先に client をビルドすること
npm run deploy
```

`wrangler.jsonc` の `assets.directory` が `client/dist` を指しているため、
**client のビルドが先**。この順序を守らないと古い（または存在しない）
静的ファイルがデプロイされる。

## 運用

配備後に繰り返す作業。初回構築の手順は「セットアップ」を参照。

### 子アカウントを追加する

きょうだいが増えたときなど、ダッシュボードを見られる人を足す手順。

**登録先は 2 箇所ある。片方だけでは入れない。**

| # | 場所 | 役割 | 欠けるとどうなるか |
|---|---|---|---|
| 1 | Cloudflare Access のポリシー | サインインを通す | サインイン画面から先に進めない（302 のまま） |
| 2 | D1 の `children_accounts` | どの家族を見られるかを決める | サインインは通るが全 API が **403** |

二重にしているのは意図的で、Access の許可を広げすぎた場合に D1 側が
歯止めになる（ADR-3、[`docs/authentication.md`](../docs/authentication.md) §3）。

**メールアドレスは 2 箇所で完全に一致させること。** 大文字小文字は
`children_accounts` 側が `lower()` で吸収するが、別名やエイリアスは別人として扱われる。

#### 1. Access のポリシーに足す

1. [Zero Trust ダッシュボード](https://one.dash.cloudflare.com/) →
   **Access** → **Applications**
2. **`chikaku`** を選ぶ（`chikaku-device-api` ではない。あちらは親端末用の
   バイパスで、人を通すためのものではない）
3. **Policies** → `allow-children` を編集
4. Include に `Emails` セレクタで追加する
5. 保存

API で行う場合は `Access: Apps and Policies (Edit)` 権限の API トークンが要る。
`wrangler login` の OAuth トークンにこの権限は含まれない。

#### 2. D1 に足す

```sh
npx wrangler d1 execute chikaku --remote --command "
  INSERT INTO children_accounts (id, family_id, email, display_name, created_at)
  SELECT lower(hex(randomblob(16))), id, 'sister@example.com', '長女', unixepoch() * 1000
  FROM families WHERE name = '我が家';
"
```

`display_name` はダッシュボードに出る呼び名。`family_id` を直接書かず
`families` から引いているのは、家族が 1 つしかない前提を持ち込まないため。

#### 3. 確認する

```sh
npx wrangler d1 execute chikaku --remote --command "
  SELECT c.email, c.display_name, f.name
  FROM children_accounts c JOIN families f ON f.id = c.family_id;
"
```

本人にダッシュボードの URL を渡す。**先方でのアカウント作成は要らない。**
メールアドレスに届く PIN を入力するだけでサインインできる（One-time PIN）。

#### 権限の区別は無い

追加した人は既存の子アカウントと**完全に同等**になる。位置の閲覧だけでなく、
招待コードの発行と端末の無効化もできる。「見るだけ」の権限は実装していない
（[`docs/implementation-status.md`](../docs/implementation-status.md) §8）。

### 子アカウントを削除する

追加と同じく 2 箇所から消す。**D1 だけ消しても Access のセッションが
生きている間はサインイン状態が残る**ため、Access のポリシーを先に直す。

```sh
npx wrangler d1 execute chikaku --remote --command "
  DELETE FROM children_accounts WHERE lower(email) = 'sister@example.com';
"
```

発行済みの招待コードは `created_by` が `ON DELETE SET NULL` なので残る。
使われたくない場合は併せて消す。

```sh
npx wrangler d1 execute chikaku --remote --command "
  DELETE FROM invite_codes WHERE used_at IS NULL;
"
```

## API

エラー本文は全て `{"error": "<機械可読コード>", "message": "<日本語>"}`。
アプリは 4xx のとき `message` を高齢の利用者にそのまま表示するため、
この文言に専門用語を入れない。

### 親端末（Android）— Access のバイパス対象

`Authorization: Bearer <device_token>`

| メソッド | パス | 用途 |
|---|---|---|
| POST | `/api/v1/devices/register` | 招待コードで端末を登録（トークン不要） |
| POST | `/api/v1/location` | 位置情報を 1 件送る |

```jsonc
// POST /api/v1/devices/register
→ {"invite_code": "NX6DC7VC", "device_name": "お父さんのスマホ", "device_model": "Pixel 9"}
← 200 {"device_id": "...", "device_token": "...", "family_id": "..."}

// POST /api/v1/location
→ {"device_id":"...","lat":35.6812,"lng":139.7671,"accuracy":18.0,
   "timestamp":"2026-08-21T08:36:03.143Z","battery_level":62}
← 202 {"stored": true}
```

`device_token` は原文をこの応答で一度だけ返す。サーバーには SHA-256 しか残らない。
招待コードは大文字小文字とハイフン・空白を無視して照合する（読み上げて入力する運用のため）。
`stored: false` は重複排除で既存行に畳まれたことを示す。

### 子アカウント（ダッシュボード）— Access の内側

`Cf-Access-Jwt-Assertion` は Cloudflare が付ける。ブラウザ側で用意するものは無い。

| メソッド | パス | 用途 |
|---|---|---|
| GET | `/api/v1/me` | 本人と所属家族の確認 |
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
**変えるとアプリの挙動が変わる。**

| コード | サーバーが返す状況 | アプリの挙動 |
|---|---|---|
| 202 | 位置情報を受理 | キューから削除 |
| 400 | 時刻・座標・電池残量が不正 | 再試行を打ち切って捨てる |
| 401 | トークン失効、招待コード不正、なりすまし、JWT 不正 | ペアリングし直し |
| 403 | Access は通ったが `children_accounts` に無いメール | ― |
| 404 | 他家族の ID を指した | ― |
| 429 | レート制限に掛かった | 時間をおいて再送 |
| 5xx | サーバー側の問題 | 時間をおいて再送 |

## 設計上の判断

**時刻は UTC の Unix エポックミリ秒（INTEGER）で保存する。**
SQLite の TEXT 日時は表記ゆれ（秒精度・オフセット表記）で大小比較が壊れ、
履歴の範囲検索が静かに間違う。JSON では RFC 3339 に変換して出す。

**D1 に整数を渡すときは必ず `db::num()` を通す。**
D1 は JavaScript の BigInt を受け付けず、Rust の `i64` を直接 bind すると
`D1_TYPE_ERROR` になる。`f64` として渡す（エポックミリ秒は 2^53 に遠く及ばない）。

**重複排除を `(device_id, recorded_at)` の一意制約で行う。**
アプリはオフライン時にキューを持ち WorkManager で再送するため、
応答だけが失われた再送で同じ測位が二度届きうる。

**レート制限は Worker 内蔵の `ratelimit` バインディングで刻む。**
WAF の Rate Limiting Rules はゾーン配下の機能で、独自ドメインを持たない
この配備（workers.dev）では使えない。端末登録は送信元 IP で 5 回/60 秒、
招待コードの発行は子アカウント単位で 10 回/60 秒。位置送信は刻まない
（長期圏外から復帰した端末のキュー掃き出しを絞ってしまうため）。
カウンタは Cloudflare のロケーション単位なので、接続を分散されれば
設定値は超えられる。**速度制限であって試行回数の上限ではない。**
詳細は [`docs/implementation-status.md` §4.5](../docs/implementation-status.md)。

**招待コードの引き換えは行数で判定する。**
D1 の `batch` は SQL トランザクションだが、ロールバックされるのは文が
失敗したときだけで「更新 0 行」では起きない。条件付き INSERT と
EXISTS 付き UPDATE を組み合わせ、両方が 1 行のときだけ成立とみなす。

## 開発

```sh
cargo test                                          # 単体 9 件
cargo clippy --target wasm32-unknown-unknown -- -D warnings
cargo fmt --check
npm run test:server                                 # e2e 30 件
```

### e2e テストについて

`server/tests/e2e.mjs` は `wrangler dev --local` に対して実走する。
Android 側が前提にしている契約 ―― ステータスコードの意味と JSON の形 ――
をここで固定している。アプリの `ApiClient.kt` を変える際は併せて見ること。

**Cloudflare Access の検証は迂回していない。** テストは自前の RSA 鍵で
JWKS を配るローカルサーバーを立て、Worker にそこを向かせる。
したがって署名検証・`aud`・`iss`・`exp`・`kid` の判定は本番と同じ経路を通り、
署名改ざん・クレーム差し替え・期限切れを実際に拒否できることを確認している。

ローカル実行には専用の設定 `server/wrangler.test.jsonc` を使う。
本番の `wrangler.jsonc` と分けているのは、静的アセット（`client/dist`）を
要求せず、チームドメインをローカルの JWKS サーバーに向けるため。

## 未実装

- FCM HTTP v1 でのプッシュ通知（フェーズ2）
- ジオフェンス通過イベントの受信（フェーズ3）

