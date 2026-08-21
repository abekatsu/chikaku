# アーキテクチャ決定記録 (ADR)

CLAUDE.md §8「未確定事項」として残されていた項目を含む、構成上の判断とその理由。
**このファイルは CLAUDE.md §8 の内容を更新するものとして扱う。**

各記録は「決定」「背景」「なぜそうしなかったか（却下案）」「結果として受け入れること」で構成する。

| # | 決定 | 状態 | 日付 |
|---|---|---|---|
| [1](#adr-1-フロントエンドは-vite--react--typescript) | フロントエンドは Vite + React + TypeScript | 採用 | 2026-08-21 |
| [2](#adr-2-デプロイ先は-cloudflare-workers単一オリジン) | デプロイ先は Cloudflare Workers（単一オリジン） | 採用 | 2026-08-21 |
| [3](#adr-3-子アカウントの認証は-cloudflare-access-に委ねる) | 子アカウントの認証は Cloudflare Access に委ねる | 採用 | 2026-08-21 |
| [4](#adr-4-データストアは-d1sqlite-互換) | データストアは D1（SQLite 互換） | 採用 | 2026-08-21 |
| [5](#adr-5-worker-は-rust-workers-rs-で書く) | Worker は Rust (workers-rs) で書く | 採用 | 2026-08-21 |

---

## ADR-1: フロントエンドは Vite + React + TypeScript

### 決定

素の React を Vite でビルドした SPA にする。Next.js は使わない。

### 背景

CLAUDE.md §8 に「素の JS / React / 他」として未決のまま残っていた。
React と Next.js はレイヤーが違う。React は UI を描くライブラリで、
ルーティング・ビルド・配信は何も決めない。Next.js はその外側を決めた
フレームワークで、**本番では Node.js プロセスが常駐する**。

TypeScript はどちらも一級サポートで、判断材料にならない。

### なぜ Next.js を採らないか

- **サーバーが増える。** API は Rust 側が全部持っているので、Next.js を
  入れると常駐プロセスが2つになる。家族数人のシステムで運用対象を
  倍にする価値がない
- **SSR の利点がゼロ。** ダッシュボードはログインの内側にあり、
  SEO の対象になるページがない。表示するのは親の現在地という
  リアルタイムかつ非公開の情報で、サーバーで先に描く意味がない
- **地図ライブラリと相性が悪い。** Leaflet は `window` を直接触るため
  SSR で落ちる。`dynamic(..., { ssr: false })` で回避することになり、
  Next.js の目玉機能を切って使う形になる

`output: 'export'` で静的出力にする道もあるが、そうすると Next.js が
足しているものがほぼ全部無効になる。それなら最初から Vite を使う。

### 受け入れること

- ルーティングは自分で選ぶ（React Router 等）
- 将来この画面に公開ページ（サービス紹介等）を持たせたくなった場合は
  この判断を見直す

---

## ADR-2: デプロイ先は Cloudflare Workers（単一オリジン）

### 決定

1つの Worker が SPA の静的ファイルと API の両方を**同じオリジンで**配る。
Workers Static Assets の `run_worker_first` で API のパスだけ Worker に回す。

```jsonc
"assets": {
  "directory": "./client/dist/",
  "not_found_handling": "single-page-application",
  "binding": "ASSETS",
  "run_worker_first": ["/api/*"]
}
```

```
https://<host>/          → React の静的ファイル
https://<host>/api/v1/*  → Worker (Rust)
```

### 背景

CLAUDE.md §8 の「ホスティング環境（自宅サーバー / VPS / クラウド）」に対する決定。

### なぜ単一オリジンが効くか

**Cookie が素直に使えるようになる。** 別オリジンの SPA だとセッションを
`localStorage` に置くことになり、XSS で盗まれる。同一オリジンなら
`HttpOnly` Cookie が使える（実際には ADR-3 により Access が Cookie を管理する）。

副次的に **CORS の設定自体が不要**になる。Axum 実装にあった
`CHIKAKU_CORS_ORIGINS` は役目を終える。

### 受け入れること

- **Worker のデプロイ前に client のビルドが必要**になる（`assets.directory` が
  `client/dist` を指すため）。デプロイ手順がこの順序に依存する
- Cloudflare への依存が深まる。他所へ移す場合は Workers 固有の API
  （D1 バインディング、Static Assets）を書き換えることになる

---

## ADR-3: 子アカウントの認証は Cloudflare Access に委ねる

### 決定

ダッシュボードの手前に Cloudflare Access（Zero Trust）を置き、
**自前のログイン画面・パスワード保存・セッション管理を持たない**。

Worker は `Cf-Access-Jwt-Assertion` ヘッダの JWT を検証し、
その `email` クレームで `children_accounts` を引いて `family_id` を得る。

### 背景

Axum 版では自前でパスワード（Argon2id）とセッショントークンを持っていた。
`docs/authentication.md` §11 に挙げた限界のうち複数が、この方式では
自力で埋めるしかなかった。

### 何が解決するか

| 旧設計の限界 | Access ではどうなるか |
|---|---|
| レート制限が無い（総当たり可能） | Cloudflare 側の問題になる |
| パスワード変更・リセットが無い | ID プロバイダ側の機能になる |
| パスワードハッシュを自前で持つ | **そもそも保存しない** |
| セッション管理を自前で持つ | Access が管理する |

無料枠が 50 ユーザーまであり、家族数人の規模なら費用は増えない。

### JWT を自分で検証する必要がある

Worker が Static Assets を持つ場合、**Access のコンテキスト (`ctx.access`) が
Worker に渡らない**ことが Cloudflare のドキュメントに明記されている。
したがって `Cf-Access-Jwt-Assertion` の JWT を Worker 自身で検証する。

検証内容:

1. ヘッダの `alg` が `RS256`、`kid` に対応する鍵を JWKS から選ぶ
2. `{TEAM_DOMAIN}/cdn-cgi/access/certs` の公開鍵で署名を検証
3. `aud` が Access アプリケーションの AUD タグと一致
4. `iss` がチームドメインと一致
5. `exp` が未来

**ヘッダを信用するだけでは不十分**（なりすまし可能）。署名の検証まで行う。

### 親端末（Android）は Access の外に置く

アプリは対話的ログインができないため、以下の2経路は Access のバイパスにする。
これらは従来どおり招待コードと `device_token` で守る。

```
POST /api/v1/devices/register   ← 招待コード（一回きり）
POST /api/v1/location           ← Bearer device_token
```

結果として、**認証経路が主体ごとに完全に分かれる**。
親端末は Bearer トークン、子アカウントは Access JWT。
片方の資格情報でもう片方の API を叩くことは構造上できない。

### 受け入れること

- **Cloudflare への依存が認証にまで及ぶ。** Access をやめる場合は
  ログイン機能を作り直すことになる
- Access のバイパス設定を誤ると、親端末用エンドポイントが
  サインインを要求して**アプリが動かなくなる**。逆に広く開けすぎると
  ダッシュボードが無防備になる。ここは設定ミスが直接穴になる箇所
- `children_accounts` はメールアドレスで ID プロバイダと突き合わせる。
  Access で認証が通っても、この表に無いメールは 403 にする
  （Access のポリシーと二重で絞る）

---

## ADR-4: データストアは D1（SQLite 互換）

### 決定

SQLite ファイル + sqlx をやめ、Cloudflare D1 に移す。
スキーマ（`migrations/0001_init.sql`）は D1 が SQLite 互換のためほぼそのまま使える。

### なぜ書き換えが必要か

**Workers にファイルシステムが無い。** SQLite ファイルを置く場所がない。
Containers で Rust バイナリをそのまま動かす案も検討したが、
Cloudflare のドキュメントに「**ディスクは全て ephemeral**、コンテナが
眠ると次回起動時は新しいディスクになる」と明記されており、
SQLite ファイルの置き場所にはならない。

sqlx は D1 を喋れないため、データアクセス層は `worker::D1Database` に置き換える。

### 実装上わかった制約

移植前に最小構成で検証した結果、以下が判明した。**どれもハマると原因が
わかりにくいので記録しておく。**

| 制約 | 対処 |
|---|---|
| **D1 は JavaScript の BigInt を受け付けない**（`D1_TYPE_ERROR`）。Rust の `i64` を bind すると bigint になり失敗する | `f64` として bind する。エポックミリ秒は 2^53 に遠く及ばず、INTEGER 列の型親和性で整数として格納される。読み出しは `i64` のまま正確に戻る（検証済み） |
| D1 の行はタプルにデシリアライズできない | `#[derive(Deserialize)]` の構造体で受ける |
| プレースホルダは `?` と `?NNNN` のみ（名前付き不可） | 既存の SQL は `?1` 形式なのでそのまま使える |
| 対話的トランザクションが無い | `batch()` が SQL トランザクションとして働く（失敗時は全体をロールバック）。招待コードの引き換えはこれで原子性を保つ |

### 常駐タスクの置き換え

保持期間の掃除は `tokio::spawn` の無限ループだったが、Workers に
常駐プロセスは無い。**Cron Triggers**（`scheduled()` ハンドラ）に置き換える。

### 受け入れること

- ローカル開発は `wrangler dev --local`（Miniflare 上の D1）になる。
  `cargo test` だけでは API を通しで検証できない
- D1 には読み取りレプリカ・Time Travel など独自機能があるが、
  当面は使わない

---

## ADR-5: Worker は Rust (workers-rs) で書く

### 決定

Worker を TypeScript で書き直すのではなく、既存の Rust を workers-rs で移植する。
ルーティングは axum ではなく workers-rs の `Router` を使う。

### 背景

CLAUDE.md はバックエンドを Rust と定めている。Axum 実装の
**認証・認可・バリデーションのロジックはそのまま移植でき**、
書き換えが必要なのはデータアクセス層とルーティングの外枠だけ。

TypeScript にすれば JWT 検証は `jose` で3行になり明らかに簡単だが、
Rust の実装を捨てることになる。

### 移植で必要になったこと

| 項目 | 対処 |
|---|---|
| `worker` crate のバージョン | `worker-build` 0.8.1 は `worker` 0.8 以上を要求する |
| 乱数（`getrandom`） | `wasm_js` フィーチャ + `.cargo/config.toml` で `--cfg getrandom_backend="wasm_js"` |
| `uuid` v4 | `rng-getrandom` フィーチャが必要 |
| 時刻 | `chrono::Utc::now()` ではなく `worker::Date::now()` |
| パスワードハッシュ | ADR-3 により **argon2 の依存ごと削除** |
| Web Crypto の呼び出し | `serde_wasm_bindgen` は JS の `Map` を作るため SubtleCrypto が読めない。`js_sys::JSON::parse` で素のオブジェクトにする |

### 受け入れること

- WASM 特有の落とし穴が多い。上の表は実際に踏んだものの記録
- ビルドに `worker-build` と wasm32 ターゲットが要る
- **wrangler 4 は Node.js 22 以上を要求する。** リポジトリ直下の
  `.tool-versions` で 22 系に固定している

---

## 変更されない判断

移植しても以下は Axum 実装から引き継ぐ。理由は `docs/authentication.md` を参照。

- 時刻は UTC のエポックミリ秒で保存する（TEXT 日時の表記ゆれを避ける）
- `(device_id, recorded_at)` の一意制約で再送の重複を畳む
- `device_token` は SHA-256 のみ保存し、原文を残さない
- 他家族の `family_id` は 403 ではなく 404 を返す
- 位置履歴は保持期間（既定 90 日）で自動削除する
