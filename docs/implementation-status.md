# 実装状況 — 高齢者見守り位置情報システム

離れて暮らす親の居場所を、子供（複数人）が把握できるようにするシステム。
このドキュメントは**現在何が動いていて、何がまだ動いていないか**を記録する。

- 構成上の判断と理由 → [architecture-decisions.md](architecture-decisions.md)
- 認証・認可の設計 → [authentication.md](authentication.md)
- 各コンポーネントの使い方 → `server/README.md`, `client/README.md`, `ios-app/README.md`
- 配備後の作業（子アカウントの追加・削除）→ `server/README.md` の「運用」

最終更新: 2026-08-25

---

## 1. 全体像

```
  親のスマホ                Cloudflare                    子のブラウザ
┌──────────────┐        ┌────────────────────────┐        ┌──────────────┐
│ Android app  │  HTTPS │  Access（認証）         │        │  ダッシュボード │
│ (Kotlin)     │───────▶│    ↓                   │◀──────▶│  (React SPA) │
│ Foreground   │ 位置が  │  Worker (Rust/wasm)    │  同一   │              │
│ Service      │ 変化した│    ↕                   │ オリジン │  Leaflet 地図 │
├──────────────┤ ときだけ│  D1 (SQLite)           │        │              │
│ iPhone app   │───────▶│                        │        │              │
│ (Swift)      │        └────────────────────────┘        └──────────────┘
│ SLC + 標準更新│          Cron: 90日で履歴を削除
└──────────────┘
```

**親側は2機種が同じ契約で喋る。** サーバーから見ると Android と iPhone の
区別は無く、`device_model` が違うだけ。

| コンポーネント | 状態 | 中身 |
|---|---|---|
| **Androidアプリ（親側）** | 実装完了 | Kotlin 23ファイル。debug / release 両方ビルド成功 |
| **iPhoneアプリ（親側）** | 実装完了・実機未検証 | Swift 25ファイル。debug / release 両方ビルド成功。**配布には Apple Developer Program の更新が要る**（§4.5） |
| **サーバー** | 実装完了 | Cloudflare Workers (Rust) + D1。単体9件 / e2e30件 |
| **ダッシュボード（子側）** | 実装完了 | Vite + React + TypeScript。地図・履歴・招待・解除 |
| **実環境への配備** | 完了 | `https://chikaku.<subdomain>.workers.dev`。D1・Access・Cron すべて稼働（§8.2） |
| **実機での疎通確認** | 一部完了 | 実機のペアリングと位置送信は成立（§7.2）。移動・圏外・電池は未検証（§7.3） |

CLAUDE.md §6 のフェーズでいうと、**フェーズ1（MVP疎通確認）は Android で完了**。
実機からの位置情報が実環境に届いている。iPhone 版は実装とサーバーとの
契約確認まで終わっているが、実機での確認は会費の更新待ち（§4.5）。

**1つの Worker が API とダッシュボードの静的ファイルを同じオリジンで配る。**
デプロイ対象はサーバーとクライアントで分かれていない（ADR-2）。

---

## 2. データの流れ

実装済みの経路を通しで書くと以下になる。

### 2.1 ペアリング（1回だけ）

1. 子がダッシュボードで「招待コードを発行」を押す → 8文字のコード（有効24時間・一回きり）
2. 子が口頭やメモで親にコードを伝える
3. 親がアプリを起動 → 同意画面 → コード入力 → 権限の許可（段階的に）
4. サーバーが `device_id` と `device_token` を返し、端末が家族に紐づく

招待コードは見間違えやすい `0 1 2 B I L O S Z` を除いた27文字から作る。
入力時は大文字小文字・ハイフン・空白を無視して照合する。

### 2.2 位置の送信（常時）

1. 前回位置から50m以上動いたときだけ測位コールバックが発生する
2. アプリ側でさらに間引く（50m未満なら送らない／動きがなくても30分に1回は送る）
3. 端末内のキューに書いてから HTTPS で送信、成功したらキューから削除
4. サーバーは `(device_id, recorded_at)` の一意制約で重複を畳む

**圏外なら送信は失敗するが、キューは残る。** 応答だけが失われた場合の重複は
サーバー側で畳まれる。

**ここから先が2機種で違う。** キューの実体と再送の駆動が別物になる。

| | Android | iOS |
|---|---|---|
| キュー | Room | SwiftData |
| 再送の駆動 | WorkManager が指数バックオフで回す | 次の位置更新で掃き出す。`BGTaskScheduler` は補助 |
| 静止中の30分ヘルスチェック | WorkManager が保証する | OS 任せで**間隔は守られない** |

iOS が「位置更新のたびに掃き出す」形になるのは、アプリが起きている時間が
その瞬間に集中するため。詳細は §4.2 と ADR-6。

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

## 4. iPhoneアプリ（親側）

Bundle ID: `com.damburisoft.chikaku.watch`（Android と同じ。プラットフォームが違えば衝突しない）
アプリ表示名: **みまもり** ／ バージョン `0.1.0` ／ 最低 iOS 17.0（iPhone XS 以降）

**サーバーとダッシュボードには変更を入れていない。** 親端末側の契約
（`POST /api/v1/devices/register` と `POST /api/v1/location`）がプラットフォームに
依存しない形だったため、iOS 側を契約に合わせるだけで済んだ。`battery_level` の
「取得不能なら -1」という規約も `UIDevice.batteryLevel`（不明時 -1.0）と噛み合う。

Swift 6 / SwiftUI / SwiftData。**外部依存はゼロ**（CocoaPods / SPM ともに使っていない）。
使い方とビルド手順は [`ios-app/README.md`](../ios-app/README.md)。

### 4.1 構成

```
ios-app/Chikaku/
├── ChikakuApp.swift        @main。AppDelegate で位置情報による起動を受ける
├── Graph.swift             簡易サービスロケータ（DIライブラリは使わない）
├── Config.swift            xcconfig → Info.plist 経由の設定
├── Strings.swift           文言。Android の strings.xml と一対一
├── Log.swift               os.Logger。位置情報そのものは出さない
├── Data/
│   ├── SettingsStore.swift   UserDefaults + Keychain
│   ├── Keychain.swift        device_token 専用
│   ├── PendingLocation.swift SwiftData の Entity
│   ├── LocationQueue.swift   送信待ちキューへの操作
│   ├── ApiClient.swift       URLSession。ApiResult で失敗理由を型で区別
│   ├── ApiModels.swift       リクエスト/レスポンスDTO
│   └── Timestamp.swift       RFC 3339 の整形
├── Location/
│   ├── LocationTuning.swift     測位パラメータと閾値の集約点
│   ├── LocationRepository.swift 「送るべきか」の判定とキュー投入
│   └── LocationTracker.swift    CLLocationManager（SLC + 標準更新）
├── Upload/
│   ├── Uploader.swift               キューの掃き出し
│   └── BackgroundTaskScheduler.swift BGTaskScheduler への予約
└── UI/
    ├── RootView.swift        画面ルーティング
    ├── DisclosureView.swift  プロミネントディスクロージャー（同意画面）
    ├── PairingView.swift     招待コード入力
    ├── PermissionView.swift  権限を段階に分けて取得
    ├── StatusView.swift      定常状態の画面
    ├── AppModel.swift
    ├── Common.swift
    └── Theme.swift
```

Xcode プロジェクトは**ファイルシステム同期グループ**（Xcode 16+）で作ってあり、
`Chikaku/` にファイルを足せば pbxproj を触らずに target へ入る。

### 4.2 Android 版と何が違うか

**移植ではなく設計し直しになった部分がある。** Android の
「Foreground Service + WorkManager」に一対一で対応するものが iOS に無い。

| Android | iOS | 差の中身 |
|---|---|---|
| `setMinUpdateDistanceMeters(50m)` | `distanceFilter = 50` | ほぼ同等 |
| `setIntervalMillis` / `setMaxUpdateDelayMillis` | **存在しない** | iOS に更新間隔の概念が無い |
| Foreground Service（常駐通知） | Background Modes + `allowsBackgroundLocationUpdates` | **常駐通知が無い**。「動いている」ことを画面に固定できない |
| `BootReceiver` / `WatchdogWorker` | Significant Location Change | 終了・再起動から OS がアプリを起こす |
| WorkManager（指数バックオフ） | 位置更新ごとの掃き出し + `BGTaskScheduler` | **iOS は実行時刻を保証しない** |
| Room | SwiftData | バックアップ除外済み（`-wal` / `-shm` も） |
| DataStore | UserDefaults + Keychain | トークンだけ Keychain |
| OkHttp | URLSession | 依存を増やさない |
| 権限4ステップ | 使用中のみ → 常に → 正確な位置 → 通知 | 表示される手順だけで番号を振り直す |

### 4.3 バッテリー戦略（CLAUDE.md §2.2 準拠）

省電力は「間隔」ではなく**どの測位サービスを使うか**で作る。判断の理由は ADR-6。

**第1段: OS レベルで更新自体を抑制**

| パラメータ | 値 | 意図 |
|---|---|---|
| `distanceFilter` | 50m | **これが核**。動かない限りコールバック自体が発生しない |
| `desiredAccuracy` | `kCLLocationAccuracyHundredMeters` | GPS 単独を避け Wi-Fi / セル測位を使う |
| Significant Location Change | 併走 | 消費はほぼ無視でき、終了・再起動からアプリを起こす |
| `pausesLocationUpdatesAutomatically` | **false** | true だと再開が保証されず、見守りが静かに穴を開ける |

**第2段: アプリ側で送信を間引く**（`LocationRepository`）

Android と同じ閾値を使う。50m / 30分ヘルスチェック / 精度500m超は破棄。
**iOS だけ1つ多い**: 測位時刻が5分以上前の結果も捨てる。CoreLocation は
起動直後にキャッシュ済みの古い位置を返すことがあり、それを「今いる場所」
として送ると子側に嘘を見せるため。

**ヘルスチェックは Android より弱い。** 動きが無いときの30分ごとの送信を
支えるのが `BGAppRefreshTask` しかなく、OS が実行時刻を決めるため間隔は
守られない。子側からは「iPhone の親は静止中の生存確認が粗い」として、
鮮度チップの「やや古い」の頻度に現れる（§6.1）。

### 4.4 プライバシー・セキュリティ（CLAUDE.md §5）

- TLS必須。`https` 以外は debug ビルドでのみ許可（`resolve()` で判定）
- ATS は `NSAllowsLocalNetworking` のみ。`NSAllowsArbitraryLoads` は使わない
- `device_token` は Keychain の `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`
  - **`WhenUnlocked` にしてはいけない。** 位置情報によるバックグラウンド起動は
    画面ロック中にも起きる。`WhenUnlocked` だと再起動後に親が一度も画面を
    開くまで送信が全部失敗し、しかも原因が見えない
  - `ThisDeviceOnly`: バックアップ復元・端末間転送でトークンが複製されない
- 未送信キューは iCloud バックアップから除外
- ログに位置情報そのものを出さない
- 「家族との接続を解除」で端末内のペアリング情報と未送信キューを全消去

### 4.5 配布の制約（未解決）

**Apple Developer Program のメンバーシップが今年度未更新。**
そのため現時点で親の iPhone に入れて動かし続けることはできない。

| | 無料（Personal Team） | 要・支払い |
|---|---|---|
| ビルド、自分の実機での検証 | ○ | |
| 親の iPhone に入れて動かし続ける | | ● |

無料プロビジョニングはプロファイルが7日で失効するため、遠方の親の端末には
使えない。支払い後の配布方法は3通りあり、再インストール頻度が違う。

| 方法 | 有効期間 | 親側の手間 |
|---|---|---|
| TestFlight | 90日でビルド失効 | 3ヶ月ごとに再インストール |
| Ad Hoc | 1年（会費更新と同期） | 年1回。事前に UDID 登録が要る |
| Unlisted App Distribution | 無期限 | リンクから入れるだけ。以後は自動更新 |

長期運用なら3番目が最も楽だが、これは配備段階の判断で今は決めていない。

### 4.6 Apple Watch を作らない理由

**watchOS では成立しない。** 技術的な壁が3つあり、どれも回避策が無い。

- **バックグラウンド測位の API が無い。** watchOS は
  `startMonitoringSignificantLocationChanges` もリージョン監視も非対応。
  測位を継続するには `CLBackgroundActivitySession` 等が要るうえ、
  `startUpdatingLocation()` の開始はフォアグラウンドでしかできない。
  つまり**一度アプリを閉じたら再開できない**
- **電池が持たない。** 通常使用で約18時間の端末で連続測位はできない。
  バッテリー最優先という本システムの設計思想と正面から衝突する
- **単独で通信できないモデルが多い。** セルラー版でなければ
  iPhone が圏外・離れている間は送信できない

現実的な watchOS の役割は「トラッカー本体」ではなく、文字盤コンプリケーションで
見守り状態を表示する**子側のビューア**。加えて、親が Apple Watch を持つなら
純正の「探す」の位置情報共有とファミリー共有で目的の大半は満たせる。

---

## 5. サーバー（Cloudflare Workers）

Rust を wasm にビルドして Workers 上で動かす。ルーティングは axum ではなく
workers-rs の経路分岐。**当初 Axum + SQLite で実装したものを移植した**（ADR-4/5）。

### 5.1 エンドポイント

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

### 5.2 データ

テーブルは `families` / `children_accounts` / `parent_devices` / `invite_codes` / `location_events`。

**時刻は UTC のエポックミリ秒（INTEGER）で保存する。** SQLite の TEXT 日時は
表記ゆれで大小比較が壊れ、履歴の範囲検索が静かに間違うため。JSON では RFC 3339 に変換する。

位置履歴は Cron Trigger（6時間ごと）が90日で削除する。使われないまま
期限切れになった招待コードも同時に消す。

### 5.3 認証

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

### 5.4 移植で踏んだ制約

D1 と wasm 固有の落とし穴。ハマると原因が見えにくいので記録する。

| 制約 | 対処 |
|---|---|
| **D1 は JavaScript の BigInt を受け付けない** | `i64` を `f64` として bind する（`db::num()`）。エポックミリ秒は 2^53 に遠く及ばない |
| D1 の行はタプルにできない | `#[derive(Deserialize)]` の構造体で受ける |
| **`batch` は文の失敗でしかロールバックしない**（更新0行では起きない） | 招待コードの引き換えを条件付き INSERT + EXISTS 付き UPDATE にし、両方が1行のときだけ成立とみなす |
| `getrandom` が wasm で動かない | `wasm_js` フィーチャ + `.cargo/config.toml` で cfg 指定 |
| `serde_wasm_bindgen` は JS の Map を作る | SubtleCrypto に渡す JWK は `JSON.parse` で素のオブジェクトにする |
| wrangler 4 は Node 22 以上 | `.tool-versions` で固定 |

### 5.5 レート制限

招待コードの総当たり対策。**WAF の Rate Limiting Rules は使えない。**
あれはゾーン配下の機能で、本システムは独自ドメインを持たず
workers.dev のホスト名で動く（§8.2）。代わりに Worker 内蔵の
`ratelimit` バインディングで刻む。

| 経路 | 鍵 | 上限 | 鍵の選び方の理由 |
|---|---|---|---|
| `POST /api/v1/devices/register` | 送信元 IP | 5回/60秒 | 認証前の経路なので IP しか手がかりが無い |
| `POST /api/v1/families/{id}/invites` | 子アカウント ID | 10回/60秒 | 認証済み。同じ家から複数人が見ても互いに巻き込まれない |

`POST /api/v1/location` は**意図的に刻んでいない。** 長期圏外から復帰した
端末は溜めたキューを一気に掃き出すため、正常な動作を絞ってしまう。
あちらは 32 バイトの `device_token` で守られており総当たりは成立しない。

**上限は絶対的なものではない。** カウンタは Cloudflare の
ロケーション単位で持たれるため、接続を分散されれば設定値を超えられる。
実測でも、curl を毎回起動し直した8回は全て通り（接続ごとに別のマシンへ
振られた）、接続を再利用した40回は5回で止まった。**単一接続の総当たりを
鈍らせる速度制限であって、試行回数の上限ではない。**
招待コードの本命の防御は有効期限の短さ（24時間）と 27^8 の空間のままで、
これはその上に重ねる層でしかない。

**バインディングが無い場合は 500 で落とす。** 素通りさせると
「レート制限を入れた」という前提だけが残って無防備になるため
（`config::required` と同じ考え方）。この判断のせいで e2e テスト用の
`wrangler.test.jsonc` にもバインディング宣言が要る。上限だけを緩めてある。

超過時は **429** を返す。Android は 429 を一時的な失敗として再試行に
回すため（`ApiClient.execute` の 408/429 分岐）、アプリ側の変更は要らない。

---

## 6. ダッシュボード（子側）

Vite + React + TypeScript の SPA。Next.js は使わない（ADR-1）。
地図は Leaflet + OpenStreetMap。**ログイン画面は無い**（Access が済ませている）。

### 6.1 画面の考えかた

見守る側が最初に知りたいのは座標ではなく **「その情報がどれだけ新しいか」**。
そのため端末カードは相対時刻（「14分前」）を最大の文字で出し、
鮮度を色つきチップ（最新 / やや古い / 古い / 未受信）で示す。

**測位した時刻と、サーバーに届いた時刻を区別している。** 5分以上離れていれば
「圏外だった可能性があります」と添える。見守る側にとっては「その間は連絡が
取れていなかった」という情報そのものなので隠さない。

地図では測位誤差を円で重ねる。点だけを打つと精度を過大に見せてしまう。

**通信が切れても表示中の位置は消さない。** 消すと「見守れていない」ように
見えるが、実際には少し前の情報が手元にある状態なので、帯でその旨だけを伝える。

### 6.2 実装上の判断

- **react-leaflet を使わない。** React のバージョンに追随する中間ライブラリが
  不要になり、更新タイミングを自分で制御できる。既定のマーカー画像は
  バンドラで URL が壊れるため `DivIcon` で自前に描く
- **ポーリングはタブが見えていない間は止める。** 端末は位置が変わったときだけ
  送るので、30秒より細かく聞いても新しい情報は出てこない
- **Leaflet はコンテナのリサイズに気づかない。** `ResizeObserver` から
  `invalidateSize()` を呼ぶ（この不具合はブラウザでの確認中に発見した）

### 6.3 構成

| | |
|---|---|
| `src/api/` | サーバーの JSON と対応する型、fetch ラッパ |
| `src/hooks/usePolling.ts` | 一定間隔の再取得。タブが見えていない間は止まる |
| `src/components/MapView.tsx` | Leaflet を直接扱う |
| `src/lib/format.ts` | 相対時刻・鮮度・電池・誤差の表示 |

`src/api/types.ts` は `server/src/routes/*.rs` の Serialize 構造体と対。
**サーバー側を変えたらここも変える。**

---

## 7. 検証状況

### 7.1 確認できていること

| 対象 | 内容 |
|---|---|
| Android | `assembleDebug` / `assembleRelease` 成功（15MB / 1.8MB、R8 + lintVitalRelease 通過）、`lintDebug` エラー0・警告4（すべて既知・意図的） |
| iOS | `iphonesimulator` Debug / `iphoneos` Release ともにビルド成功（736KB）、警告0（Swift 6 strict concurrency 有効） |
| iOS ↔ サーバー | ローカル Worker に対して**アプリ本体の `ApiClient` を直接叩く契約確認 12件**（`npm run test:ios`） |
| サーバー | 単体 **9件**、e2e **30件**、clippy `-D warnings` クリーン |
| クライアント | `tsc -b`（strict 設定）・`vite build` 成功 |
| 画面（ダッシュボード） | ヘッドレスブラウザで描画と操作を確認（端末選択→経路表示、招待コード発行、狭い画面への切り替え） |
| 画面（iOS） | シミュレータで4画面すべて描画を確認（同意 → ペアリング → 権限 → 状態） |

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
| 429 | レート制限に掛かった（§5.5） | 時間をおいて再送 |
| 5xx | サーバー側の問題 | 時間をおいて再送 |

**iOS 側も同じ表を機械的に確かめている。** `npm run test:ios` が
ローカルに Worker を立て、招待コードを発行し、`Chikaku/Data/ApiClient.swift`
などアプリ本体のソースをそのままコンパイルして叩く。**テスト用の複製を持たない**ので、
アプリの通信層を変えれば必ずここを通る。

| 確認していること | 期待 |
|---|---|
| 招待コードでの登録 / 使用済みコードの再利用 | 成功 / `unauthorized` |
| 正常な位置情報 / 同一測位の再送 | どちらも `success`（重複はサーバーが畳む） |
| 不正な緯度 / 範囲外の電池残量 | `clientError` 400（再試行せず破棄） |
| 電池残量 `-1`（取得不能） | 受理される |
| 誤ったトークン / 他端末の `device_id` | `unauthorized` |
| RFC 3339 の整形 | ミリ秒付き UTC（Android の `ISO_INSTANT` と同じ） |

D1 に実際に行が入るところまで確認している（登録1件・位置2件、重複は畳まれた）。

### 7.2 本番環境で確認したこと

2026-08-24 の配備後、`https://chikaku.<subdomain>.workers.dev` に対して実際に確認した。

**経路ごとの Access の効き方**（バイパスの過不足はここでしか分からない）

| リクエスト | 結果 | 意味 |
|---|---|---|
| `GET /` | 302 → Access ログイン | ダッシュボードは保護されている |
| `GET /api/v1/me` | 302 → Access ログイン | 同上 |
| `GET /api/v1/healthz` | 200 | バイパスが効いている |
| `POST /api/v1/location`（トークン無し） | 401（Worker 由来） | バイパス通過後に Worker が拒否 |
| `POST /api/v1/devices/register`（不正コード） | 401 `invalid_invite_code` | 同上 |
| 偽の `Cf-Access-Jwt-Assertion` を付与 | 302（Worker に届かない） | Access がヘッダを信用させない |

**通しの動作**（招待コード → 端末登録 → 位置送信 → 表示）

| 検証 | 結果 |
|---|---|
| 招待コードを `k7qm-4xdf` と小文字＋ハイフンで入力 | 正規化されて成立 |
| 位置送信 | 202 `{"stored":true}` |
| 同一測位の再送 | 202 `{"stored":false}`（重複排除が効く） |
| 使用済み招待コードの再利用 | 401（一回きりが守られている） |
| 不正な緯度 `999.0` | 400 |
| ブラウザからのサインインとダッシュボード表示 | One-time PIN で成立 |
| 端末登録を同一接続で連続40回 | 5回通過後すべて 429（設定どおり） |

**実機からの通し**（2026-08-25、AQUOS Sense 8 / SHARP SH-54D、debug ビルド）

APK にはビルド時に `local.properties` の `chikaku.serverBaseUrl` が
埋め込まれる。release には署名設定が無く未署名 APK は実機に入らないため、
確認は debug ビルドで行った。

| | 1件目 | 2件目 |
|---|---|---|
| 測位時刻 (JST) | 10:45:23 | 10:48:21 |
| サーバー受信 (JST) | 10:48:23 | 10:48:24 |
| 遅延 | 179秒 | 2秒 |
| 精度 | 100m | 19.7m |

**1件目の179秒の遅延は設計どおり。** 起動直後の測位を Room のキューに
書き、WorkManager が次の実行機会に掃き出した形。測位時刻と受信時刻を
別々に保存している意味がここで出る（§6.1）。

**精度が 100m → 19.7m に絞られている。** `BALANCED_POWER_ACCURACY` が
まず Wi-Fi / セル測位で答え、その後 GPS が利いた形。地図では誤差の円の
大きさとして現れる。

### 7.3 まだ確認できていないこと

- **50m の移動による送信トリガーが未検証。** 実機で確認できているのは
  起動直後の測位2件だけで、`setMinUpdateDistanceMeters` と
  アプリ側の間引き（§3.2）が実際に効くところは見ていない
- **オフライン再送の実地確認が無い。** 圏外を再現した検証はしていない
- **バッテリー消費の実測が無い。** 設計上は省電力だが、実機での数値は未取得
- **release ビルドが無い。** 署名設定が未作成。debug ビルドは HTTP の
  本文（位置情報を含む）をログに出すため、常用する端末には向かない
- **Cron Trigger の実動作は未観測。** 登録はされているが、90日経過データの
  削除が実際に走るのはまだ先
- **iOS は実機で一度も動かしていない。** バックグラウンド測位・SLC による
  復帰・`BGTaskScheduler` の実行間隔は、いずれもシミュレータでは再現されない。
  Apple Developer Program の更新が前提になる（§4.5）
- **無料プロビジョニングでバックグラウンド測位が通るかが未確認。**
  `UIBackgroundModes` は entitlement ではなく Info.plist のキーなので
  通るはずだが、実機ビルド1回で判明する話なので着手時に先に潰す

---

## 8. 開発とデプロイ

### 8.1 ローカルで動かす

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
npm run test:server   # e2e 30件
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

**iPhoneアプリ**

```sh
cd ios-app
cp Config/Chikaku.xcconfig.example Config/Chikaku.xcconfig   # 接続先を書く
xcodebuild -project Chikaku.xcodeproj -scheme Chikaku -sdk iphonesimulator build
```

`Chikaku.xcodeproj` をそのまま Xcode で開いてもよい。外部依存は無い。

```sh
npm run test:ios   # 通信の契約確認 12件（ローカル Worker を自動で立てる）
```

**xcconfig では `//` 以降が行コメントとして落ちる。** `https://` をそのまま
書くとスキームだけになって静かに壊れるため、雛形ではスラッシュを変数経由で
挟んでいる。この形を崩さないこと。

### 8.2 配備の実施内容（2026-08-24 完了）

| | |
|---|---|
| 公開 URL | `https://chikaku.<subdomain>.workers.dev` |
| Cloudflare アカウント | `REPLACE_WITH_CLOUDFLARE_ACCOUNT_ID` |
| D1 | `chikaku` / `REPLACE_WITH_D1_DATABASE_ID`（APAC） |
| Zero Trust チームドメイン | `https://REPLACE_WITH_TEAM.cloudflareaccess.com` |
| ID プロバイダー | One-time PIN のみ（外部 IdP は未設定） |
| Cron | `0 */6 * * *` |

**独自ドメインは使っていない。** Cloudflare Access は self-hosted
アプリケーションのドメインとして `workers.dev` のホスト名をそのまま指定でき、
MVP の段階で独自ドメインを用意する理由が無いため。将来 Custom Domain へ
移す場合は、Access アプリの `destinations` と Android 側の
`chikaku.serverBaseUrl` を差し替える。

**Access アプリケーションは 2 つ。** パス単位のアプリがホスト全体のアプリより
優先される（Cloudflare は「最も具体的なルールが先に適用される」）ため、
この 2 つで親端末の 3 経路だけが開き、それ以外は保護される。

| アプリ | 対象 | ポリシー |
|---|---|---|
| `chikaku-device-api` | `/api/v1/devices/register`, `/api/v1/location`, `/api/v1/healthz` | Bypass（Everyone） |
| `chikaku` | ホスト全体 | Allow（子アカウントのメールアドレス） |

AUD タグは後者のもの（`f521d219…`）を `wrangler.jsonc` に書く。
**前者の AUD ではない。** ダッシュボードの JWT は後者のアプリが発行する。

Access アプリの作成には Zero Trust の API 権限が要り、`wrangler login` の
OAuth トークンには含まれない（`workers` / `d1` などのみ）。
`Access: Apps and Policies (Edit)` を持つ API トークンを別途発行して
`POST /accounts/{id}/access/apps` で作成した。**このトークンは作業後に失効させる。**

再デプロイは `npm run deploy` のみでよい（client のビルドを含む）。
`wrangler.jsonc` にプレースホルダは残っていない。

---

## 9. 残課題

### 配備まわり（次にやること）

- **Android 実機での疎通確認。** `local.properties` の
  `chikaku.serverBaseUrl` は配備先に向けてある。招待コードを
  ダッシュボードで発行して実機をペアリングするところから
- バッテリー消費の実測
- **Apple Developer Program の更新。** iPhone 版は実装が終わっているが、
  これが無いと親の端末で動かし続けられない（§4.5）。失効から時間が経っていると
  更新ではなく再登録になり審査待ちが発生しうるため、日程には先に効いてくる
- **iOS 実機での疎通確認と配布方法の決定**（TestFlight / Ad Hoc / Unlisted）

### 機能

- **レート制限は「速度制限」であって上限ではない**（§5.5）。
  接続を分散されれば設定値を超えられる。総当たりの本命の防御は
  依然として招待コードの有効期限の短さと 27^8 の空間
- FCM Web Push 統合（フェーズ2）
- ジオフェンス（フェーズ3）
- 家族・子アカウントの管理画面。現在は Access のポリシーと
  `wrangler d1 execute` の2箇所を手で揃える運用
  （手順は `server/README.md` の「運用」）
- 監査ログ（「誰がいつ位置を見たか」が追えない）
- Android / iOS / サーバーともに自動テストは契約レベルのみ。
  Android と iOS には単体テストが無い（iOS は通信層だけ `npm run test:ios` が見る）
- **iOS の「見守りが止まった」通知が未実装。** 権限は取得しているが送出していない。
  iOS は権限が剥がれやすい（OS が定期的に「使用中のみ」への変更を促す）ため、
  Android より効果が大きい

### CLAUDE.md §8 の未確定事項の現状

| 項目 | 状態 |
|---|---|
| フロントエンドの技術選定 | **決定**: Vite + React + TypeScript（ADR-1） |
| ホスティング環境 | **決定**: Cloudflare Workers + D1（ADR-2/4） |
| 親デバイスと family の紐付け | **決定**: 招待コード（8文字・一回きり・24時間） |
| 位置情報履歴の保持期間 | **決定**: 90日（Cron で自動削除） |
