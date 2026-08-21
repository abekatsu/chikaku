# 実装状況 — 高齢者見守り位置情報システム

離れて暮らす親の居場所を、子供（複数人）が把握できるようにするシステム。
このドキュメントは**現在何が動いていて、何がまだ動いていないか**を記録する。

- 構成上の判断と理由 → [architecture-decisions.md](architecture-decisions.md)
- 認証・認可の設計 → [authentication.md](authentication.md)
- 各コンポーネントの使い方 → `server/README.md`, `client/README.md`

最終更新: 2026-08-21

---

## 1. 全体像

```
  親のスマホ                Cloudflare                    子のブラウザ
┌──────────────┐        ┌────────────────────────┐        ┌──────────────┐
│ Android app  │  HTTPS │  Access（認証）         │        │  ダッシュボード │
│ (Kotlin)     │───────▶│    ↓                   │◀──────▶│  (React SPA) │
│              │ 位置が  │  Worker (Rust/wasm)    │  同一   │              │
│ Foreground   │ 変化した│    ↕                   │ オリジン │  Leaflet 地図 │
│ Service      │ ときだけ│  D1 (SQLite)           │        │              │
└──────────────┘        └────────────────────────┘        └──────────────┘
                          Cron: 90日で履歴を削除
```

| コンポーネント | 状態 | 中身 |
|---|---|---|
| **Androidアプリ（親側）** | 実装完了 | Kotlin 23ファイル。debug / release 両方ビルド成功 |
| **サーバー** | 実装完了 | Cloudflare Workers (Rust) + D1。単体9件 / e2e29件 |
| **ダッシュボード（子側）** | 実装完了 | Vite + React + TypeScript。地図・履歴・招待・解除 |
| **実環境への配備** | **未実施** | Cloudflare アカウント側の設定が未了（§7） |
| **実機での疎通確認** | **未実施** | Android 実機 → Worker → 画面の通しは未検証 |

CLAUDE.md §6 のフェーズでいうと、**フェーズ1（MVP疎通確認）の実装が3コンポーネントとも完了**し、
配備と実機確認だけが残っている状態。

**1つの Worker が API とダッシュボードの静的ファイルを同じオリジンで配る。**
デプロイ対象はサーバーとクライアントで分かれていない（ADR-2）。

---

## 2. データの流れ

実装済みの経路を通しで書くと以下になる。

### 2.1 ペアリング（1回だけ）

1. 子がダッシュボードで「招待コードを発行」を押す → 8文字のコード（有効24時間・一回きり）
2. 子が口頭やメモで親にコードを伝える
3. 親がアプリを起動 → 同意画面 → コード入力 → 権限の許可（4ステップ）
4. サーバーが `device_id` と `device_token` を返し、端末が家族に紐づく

招待コードは見間違えやすい `0 1 2 B I L O S Z` を除いた27文字から作る。
入力時は大文字小文字・ハイフン・空白を無視して照合する。

### 2.2 位置の送信（常時）

1. 前回位置から50m以上動いたときだけ測位コールバックが発生する
2. アプリ側でさらに間引く（50m未満なら送らない／動きがなくても30分に1回は送る）
3. Room のキューに書いてから HTTPS で送信、成功したらキューから削除
4. サーバーは `(device_id, recorded_at)` の一意制約で重複を畳む

**圏外なら送信は失敗するが、キューは残る。** 電波が戻ると WorkManager が
指数バックオフで再送する。応答だけが失われた場合の重複はサーバー側で畳まれる。

### 2.3 表示（子が見たいとき）

1. 子がダッシュボードを開く → Cloudflare Access がサインインを要求
2. Worker が Access の JWT を検証し、メールアドレスで家族を特定
3. 30秒ごとに最新位置を取得（タブが見えていない間は止まる）
4. 地図にピンと測位誤差の円、必要なら移動経路を重ねる

プッシュ通知はまだ無い。ポーリングで成立させている（フェーズ2で FCM に置き換える）。

---

## 3. Androidアプリ（親側）

パッケージ / applicationId: `com.damburisoft.chikaku.watch`
アプリ表示名: **みまもり** ／ バージョン `0.1.0` (versionCode 1)

### 3.1 構成

```
app/src/main/java/com/damburisoft/chikaku/watch/
├── ChikakuApp.kt              Application。通知チャンネル作成、定期ワーカー予約
├── Graph.kt                   簡易サービスロケータ（DIライブラリは使わない）
├── MainActivity.kt            画面ルーティング、権限リクエストの受け口
├── data/
│   ├── SettingsStore.kt       DataStore。同意状態・ペアリング情報・最終送信時刻
│   ├── AppDatabase.kt         Room
│   ├── PendingLocation.kt     送信待ちキューの Entity + DAO
│   ├── ApiClient.kt           OkHttp。ApiResult で失敗理由を型で区別
│   └── ApiModels.kt           リクエスト/レスポンスDTO（kotlinx.serialization）
├── location/
│   ├── LocationTuning.kt      測位パラメータと閾値の集約点
│   └── LocationRepository.kt  「送るべきか」の判定とキュー投入
├── service/
│   ├── LocationTrackingService.kt  Foreground Service
│   ├── BootReceiver.kt             再起動・アプリ更新後の自動復帰
│   └── TrackingNotification.kt     常駐通知
├── work/
│   ├── UploadWorker.kt        キューの掃き出し
│   ├── WatchdogWorker.kt      1時間ごとの死活監視
│   └── UploadScheduler.kt     WorkManager への登録
└── ui/
    ├── DisclosureScreen.kt    プロミネントディスクロージャー（同意画面）
    ├── PairingScreen.kt       招待コード入力
    ├── PermissionScreen.kt    権限を4ステップに分割して取得
    ├── StatusScreen.kt        定常状態の画面
    ├── MainViewModel.kt
    ├── Common.kt              共通コンポーネント
    └── theme/Theme.kt
```

### 3.2 バッテリー戦略（CLAUDE.md §2.2 準拠）

「常時ポーリングしない」を二段構えで実装している。

**第1段: OSレベルで更新自体を抑制**

| パラメータ | 値 | 意図 |
|---|---|---|
| `setMinUpdateDistanceMeters` | 50m | **これが核**。動かない限りコールバック自体が発生しない |
| `setPriority` | `BALANCED_POWER_ACCURACY` | GPS単独を避け Wi-Fi/セル測位を使う |
| `setIntervalMillis` | 5分 | 歩行者の速度なら十分 |
| `setMinUpdateIntervalMillis` | 2分 | 更新の下限 |
| `setMaxUpdateDelayMillis` | 15分 | バッチ配信でCPUウェイクアップ回数を削減 |

**第2段: アプリ側で送信を間引く**（`LocationRepository`）

- 前回キュー投入地点から **50m以上** 動いたときのみ送信対象
- 動きがなくても **30分に1回**はヘルスチェックとして送る
  （「静止している」と「異常が起きている」を子側が区別できるように）
- 精度 **500m超** の測位結果は誤差が大きすぎるため破棄

### 3.3 オフライン耐性

測位結果は必ず Room のキューへ書き、送信成功後に削除する。

| 事象 | 挙動 |
|---|---|
| 圏外・通信失敗 | `Result.retry()` → WorkManager の指数バックオフ（初回1分） |
| サーバー 5xx / 408 / 429 | `Result.retry()` |
| 401 / 403 | `Result.failure()`。ペアリングやり直しが必要 |
| その他 4xx | 試行10回で該当データを破棄 |
| 長期圏外 | キュー上限500件、古いものから間引き |
| サービスが落ちた | 1時間ごとの `WatchdogWorker` が検知して再起動 |
| 端末再起動 / アプリ更新 | `BootReceiver` が自動再開（FGS起動を拒否された場合は Watchdog が拾う） |

### 3.4 UI（高齢者向けの配慮）

- 本文 20sp / タイトル 24〜30sp、主要ボタンは最小高 64dp
- 画面遷移: **同意 → ペアリング → 権限（4ステップ）→ 状態画面**
- 権限を一度にまとめて要求せず、「何のための許可か」を1つずつ説明して進める
- Android 11+ は「常に許可」がダイアログに出ないため、設定画面へ誘導する分岐を実装
- 状態画面は「最終送信」「未送信件数」「端末名」のみ。停止・接続解除は確認ダイアログ付き

### 3.5 プライバシー・セキュリティ（CLAUDE.md §5）

- TLS必須。`https` 以外は debug ビルドでのみ許可
  （`network_security_config` で localhost / 10.0.2.2 / 192.168.x に限定）
- HTTPログ出力は debug ビルドのみ（本文に位置情報が含まれるため）
- クラウドバックアップ・端末間転送から全ドメインを除外
- 「家族との接続を解除」で端末内のペアリング情報と未送信キューを全消去
- 初回起動時に必ず同意画面を通す（同意なしでは見守り開始不可）

### 3.6 ツールチェーン

開発マシンに JDK 25 / 26 しか無く、当初設定の Gradle 8.9 が動作しないため現行安定版へ更新した。
コードが未記述の段階だったため移行コストはゼロ。

| | 変更前 | 変更後 |
|---|---|---|
| Gradle | 8.9（wrapper未生成） | **9.7.1** |
| AGP | 8.7.3 | **9.3.1** |
| Kotlin | 2.0.21 | **2.3.21** |
| KSP | 2.0.21-1.0.28 | **2.3.11** |
| compileSdk / targetSdk | 35 | **37** |
| minSdk | 26 | 26（据え置き） |

主要ライブラリ: Compose BOM 2026.08.00 / core-ktx 1.19.0 / lifecycle 2.11.0 /
activity-compose 1.13.0 / play-services-location 21.4.0 / Room 2.8.4 /
WorkManager 2.11.2 / DataStore 1.2.1 / OkHttp 5.5.0 / kotlinx-serialization 1.11.0

**移行で判明した注意点**

1. **AGP 9 では Kotlin サポートが組み込みになった。**
   `org.jetbrains.kotlin.android` を適用するとビルドが失敗するため削除済み。
2. **Kotlin は 2.3.21 に固定。** 最新は 2.4.10 だが KSP 2.3.11 との組み合わせを優先した。
   lint が更新を勧める警告を出すが意図的。
3. **fragment を 1.9.0 に明示的に引き上げ。** `play-services-base` が古い 1.1.0 を引き込み
   `lintVitalRelease` が fatal を出すため。アプリ自体は Fragment を使っていない。
4. **Android SDK の追加インストールが必要**: `platforms;android-37.0`, `build-tools;37.0.0` ほか

---

## 4. サーバー（Cloudflare Workers）

Rust を wasm にビルドして Workers 上で動かす。ルーティングは axum ではなく
workers-rs の経路分岐。**当初 Axum + SQLite で実装したものを移植した**（ADR-4/5）。

### 4.1 エンドポイント

**親端末（Cloudflare Access のバイパス対象）**

| メソッド | パス | 守り方 |
|---|---|---|
| POST | `/api/v1/devices/register` | 招待コード（一回きり） |
| POST | `/api/v1/location` | `Bearer device_token` |
| GET | `/api/v1/healthz` | 認証不要 |

**子アカウント（Access の内側）**

| メソッド | パス |
|---|---|
| GET | `/api/v1/me` |
| GET | `/api/v1/families/{id}/latest` |
| GET | `/api/v1/families/{id}/history` |
| POST | `/api/v1/families/{id}/invites` |
| POST | `/api/v1/families/{id}/devices/{id}/revoke` |

`invites` と `revoke` は CLAUDE.md §3.2 に無いが追加している。
前者が無いと端末登録の経路そのものが存在せず、後者が無いと端末を紛失しても止められない。

### 4.2 データ

テーブルは `families` / `children_accounts` / `parent_devices` / `invite_codes` / `location_events`。

**時刻は UTC のエポックミリ秒（INTEGER）で保存する。** SQLite の TEXT 日時は
表記ゆれで大小比較が壊れ、履歴の範囲検索が静かに間違うため。JSON では RFC 3339 に変換する。

位置履歴は Cron Trigger（6時間ごと）が90日で削除する。使われないまま
期限切れになった招待コードも同時に消す。

### 4.3 認証

主体ごとに経路が完全に分かれている。詳細は [authentication.md](authentication.md)。

| | 親端末 | 子アカウント |
|---|---|---|
| 資格情報 | `device_token`（32バイト乱数） | Cloudflare Access の JWT |
| 保存 | **SHA-256 のみ**（原文は残さない） | **保存しない** |
| 期限 | なし（revoke で失効） | Access のセッション設定 |

**パスワードは一切保存していない。** Access に委ねたことで、レート制限・
パスワードリセット・セッション管理が自分の課題ではなくなった。

Static Assets を持つ Worker には Access のコンテキストが渡らないため、
JWT の署名・`aud`・`iss`・`exp`・`kid` を Worker 自身で検証している。
`alg` は JWT の申告に従わず RS256 固定（`alg=none` 差し替えを塞ぐ）。

### 4.4 移植で踏んだ制約

D1 と wasm 固有の落とし穴。ハマると原因が見えにくいので記録する。

| 制約 | 対処 |
|---|---|
| **D1 は JavaScript の BigInt を受け付けない** | `i64` を `f64` として bind する（`db::num()`）。エポックミリ秒は 2^53 に遠く及ばない |
| D1 の行はタプルにできない | `#[derive(Deserialize)]` の構造体で受ける |
| **`batch` は文の失敗でしかロールバックしない**（更新0行では起きない） | 招待コードの引き換えを条件付き INSERT + EXISTS 付き UPDATE にし、両方が1行のときだけ成立とみなす |
| `getrandom` が wasm で動かない | `wasm_js` フィーチャ + `.cargo/config.toml` で cfg 指定 |
| `serde_wasm_bindgen` は JS の Map を作る | SubtleCrypto に渡す JWK は `JSON.parse` で素のオブジェクトにする |
| wrangler 4 は Node 22 以上 | `.tool-versions` で固定 |

---

## 5. ダッシュボード（子側）

Vite + React + TypeScript の SPA。Next.js は使わない（ADR-1）。
地図は Leaflet + OpenStreetMap。**ログイン画面は無い**（Access が済ませている）。

### 5.1 画面の考えかた

見守る側が最初に知りたいのは座標ではなく **「その情報がどれだけ新しいか」**。
そのため端末カードは相対時刻（「14分前」）を最大の文字で出し、
鮮度を色つきチップ（最新 / やや古い / 古い / 未受信）で示す。

**測位した時刻と、サーバーに届いた時刻を区別している。** 5分以上離れていれば
「圏外だった可能性があります」と添える。見守る側にとっては「その間は連絡が
取れていなかった」という情報そのものなので隠さない。

地図では測位誤差を円で重ねる。点だけを打つと精度を過大に見せてしまう。

**通信が切れても表示中の位置は消さない。** 消すと「見守れていない」ように
見えるが、実際には少し前の情報が手元にある状態なので、帯でその旨だけを伝える。

### 5.2 実装上の判断

- **react-leaflet を使わない。** React のバージョンに追随する中間ライブラリが
  不要になり、更新タイミングを自分で制御できる。既定のマーカー画像は
  バンドラで URL が壊れるため `DivIcon` で自前に描く
- **ポーリングはタブが見えていない間は止める。** 端末は位置が変わったときだけ
  送るので、30秒より細かく聞いても新しい情報は出てこない
- **Leaflet はコンテナのリサイズに気づかない。** `ResizeObserver` から
  `invalidateSize()` を呼ぶ（この不具合はブラウザでの確認中に発見した）

### 5.3 構成

| | |
|---|---|
| `src/api/` | サーバーの JSON と対応する型、fetch ラッパ |
| `src/hooks/usePolling.ts` | 一定間隔の再取得。タブが見えていない間は止まる |
| `src/components/MapView.tsx` | Leaflet を直接扱う |
| `src/lib/format.ts` | 相対時刻・鮮度・電池・誤差の表示 |

`src/api/types.ts` は `server/src/routes/*.rs` の Serialize 構造体と対。
**サーバー側を変えたらここも変える。**

---

## 6. 検証状況

### 6.1 確認できていること

| 対象 | 内容 |
|---|---|
| Android | `assembleDebug` / `assembleRelease` 成功（15MB / 1.8MB、R8 + lintVitalRelease 通過）、`lintDebug` エラー0・警告4（すべて既知・意図的） |
| サーバー | 単体 **9件**、e2e **29件**、clippy `-D warnings` クリーン |
| クライアント | `tsc -b`（strict 設定）・`vite build` 成功 |
| 画面 | ヘッドレスブラウザで描画と操作を確認（端末選択→経路表示、招待コード発行、狭い画面への切り替え） |

**e2e テストは Cloudflare Access の検証を迂回していない。** テスト用の RSA 鍵で
JWKS を配るローカルサーバーを立て、Worker をそこへ向ける。署名検証・`aud`・`iss`・
`exp`・`kid` の判定は本番と同じ経路を通り、署名改ざん・**クレームだけの差し替え**・
期限切れ・サービストークンの拒否を実際に確認している。

Android 側が前提にするステータスコードの意味も e2e で固定している。
**サーバーのコードを変えるとアプリの再送ロジックが変わる。**

| コード | 状況 | アプリの挙動 |
|---|---|---|
| 202 | 位置情報を受理 | キューから削除 |
| 400 | 時刻・座標・電池残量が不正 | 再試行を打ち切って捨てる |
| 401 | トークン失効 / 招待コード不正 / なりすまし / JWT 不正 | ペアリングし直し |
| 403 | Access は通ったが未登録のメール | ― |
| 404 | 他家族の ID を指した | ― |
| 5xx | サーバー側の問題 | 時間をおいて再送 |

### 6.2 確認できていないこと

- **実環境で動いていない。** Cloudflare へのデプロイは未実施
- **Android 実機からの通し確認が無い。** アプリ → Worker → 画面の疎通は
  e2e テスト（HTTP レベル）でしか確認していない
- **バッテリー消費の実測が無い。** 設計上は省電力だが、実機での数値は未取得
- **Cloudflare Access の実設定が無い。** バイパス設定の正しさは未検証
- ダッシュボードは開発用のダミーデータでしか動かしていない

---

## 7. 開発とデプロイ

### 7.1 ローカルで動かす

**サーバー + ダッシュボード**（リポジトリ直下で実行）

```sh
npm install
npm run dev        # → http://localhost:5173
```

Worker・ローカル D1・**Cloudflare Access の代役**をまとめて起動する。
開発用の子アカウント（`dev@example.com`）でサインイン済みの状態で開く。

ローカルには Access が居ないが、**検証を無効化する抜け道を Worker に作ると
本番に混入しかねない**ので、代わりに検証を通る本物の JWT を用意する方式にしている。

```sh
npm run test:server   # e2e 29件
cd server && cargo test && cargo clippy --target wasm32-unknown-unknown -- -D warnings
cd client && npm run typecheck
```

**Androidアプリ**

システムの JDK が新しすぎるため、CLI からは Android Studio 同梱の JBR を指定する。

```sh
export JAVA_HOME="/Users/YOUR_NAME/Applications/Android Studio.app/Contents/jbr/Contents/Home"
cd android-app && ./gradlew :app:assembleDebug
```

サーバーURLは `local.properties`（リポジトリに含まれない）に書く。
未設定でもビルドは通り、アプリ内の「詳細設定」から実行時に上書きできる。

### 7.2 配備に必要な作業（未実施）

1. **D1 を作る** → `npx wrangler d1 create chikaku`
   出力された `database_id` を `wrangler.jsonc` に書く → `npm run db:migrate`
2. **Cloudflare Access を設定する**
   self-hosted アプリケーションを作り、**親端末用の2経路を Bypass にする**。
   ここを誤ると、付け忘れればアプリが止まり、開けすぎればダッシュボードが無防備になる
3. **チームドメインと AUD タグ**を `wrangler.jsonc` の `vars` に書く
4. **家族と子アカウントを登録する** → `wrangler d1 execute`（CLI は Workers に置けない）
5. **デプロイ** → `npm run deploy`（client のビルドを先に走らせる）

手順の詳細は `server/README.md`。
`wrangler.jsonc` には3つのプレースホルダが残っている。

---

## 8. 残課題

### 配備まわり（次にやること）

- Cloudflare アカウント側の設定（§7.2 の1〜4）
- Android 実機での疎通確認
- バッテリー消費の実測

### 機能

- **レート制限。** 招待コード発行・端末登録の総当たり対策が無い。
  防御は有効期限の短さだけに依存している。**公開前に塞ぐべき最優先項目**
  （Cloudflare の Rate Limiting Rules で対処できる）
- FCM Web Push 統合（フェーズ2）
- ジオフェンス（フェーズ3）
- 家族・子アカウントの管理画面（現在は `wrangler d1 execute`）
- 監査ログ（「誰がいつ位置を見たか」が追えない）
- Android / サーバーともに自動テストは契約レベルのみ。
  Android は単体テストが無い

### CLAUDE.md §8 の未確定事項の現状

| 項目 | 状態 |
|---|---|
| フロントエンドの技術選定 | **決定**: Vite + React + TypeScript（ADR-1） |
| ホスティング環境 | **決定**: Cloudflare Workers + D1（ADR-2/4） |
| 親デバイスと family の紐付け | **決定**: 招待コード（8文字・一回きり・24時間） |
| 位置情報履歴の保持期間 | **決定**: 90日（Cron で自動削除） |
