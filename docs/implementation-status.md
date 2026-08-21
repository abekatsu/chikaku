# 実装状況サマリー — 高齢者見守り位置情報システム

最終更新: 2026-08-21 / 対象: `android-app`（親側 Androidアプリ）

---

## 1. 全体の進捗

| コンポーネント | 状態 | 備考 |
|---|---|---|
| **Androidアプリ（親側）** | **MVP実装完了** | debug / release 両方ビルド成功、lint エラー0 |
| バックエンドサーバー（Rust） | 未着手 | `server/` は空。APIの契約はアプリ側で先行定義済み（§6） |
| Webダッシュボード（子側） | 未着手 | `client/` は空 |

CLAUDE.md §6 の開発フェーズでいうと、**フェーズ1（MVP疎通確認）の Android 側が完了**した段階。
サーバーが立ち上がれば、そのまま疎通確認に入れる。

---

## 2. Androidアプリ 実装内容

### 2.1 パッケージ・構成

- パッケージ名 / applicationId: `com.damburisoft.chikaku.watch`
- Gradleプロジェクト名: `chikaku-watch`
- アプリ表示名: **みまもり**
- バージョン: `0.1.0` (versionCode 1)

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

Kotlinソース **23ファイル**。

### 2.2 バッテリー戦略（CLAUDE.md §2.2 準拠）

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
- 動きがなくても **30分に1回**はヘルスチェックとして送る（「静止」と「異常」を子側が区別できるように）
- 精度 **500m超** の測位結果は誤差が大きすぎるため破棄

### 2.3 オフライン耐性

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

### 2.4 UI（高齢者向けの配慮）

- 本文 20sp / タイトル 24〜30sp、主要ボタンは最小高 64dp
- 画面遷移: **同意 → ペアリング → 権限（4ステップ）→ 状態画面**
- 権限を一度にまとめて要求せず、「何のための許可か」を1つずつ説明して進める
- Android 11+ は「常に許可」がダイアログに出ないため、設定画面へ誘導する分岐を実装
- 状態画面では「最終送信」「未送信件数」「端末名」のみを表示。停止・接続解除は確認ダイアログ付き

### 2.5 プライバシー・セキュリティ（CLAUDE.md §5）

- TLS必須。`https` 以外は debug ビルドでのみ許可（`network_security_config` で localhost / 10.0.2.2 / 192.168.x に限定）
- HTTPログ出力は debug ビルドのみ（本文に位置情報が含まれるため）
- クラウドバックアップ・端末間転送から全ドメインを除外
- 「家族との接続を解除」で端末内のペアリング情報と未送信キューを全消去
- 初回起動時に必ず同意画面を通す（同意なしでは見守り開始不可）

---

## 3. ツールチェーン

開発マシンに JDK 25 / 26 しか無く、当初設定の Gradle 8.9 が動作しないため現行安定版へ更新した。
新規プロジェクトでコードが未記述の段階だったため、移行コストはゼロ。

| | 変更前 | 変更後 |
|---|---|---|
| Gradle | 8.9（wrapper未生成） | **9.7.1** |
| AGP | 8.7.3 | **9.3.1** |
| Kotlin | 2.0.21 | **2.3.21** |
| KSP | 2.0.21-1.0.28 | **2.3.11** |
| compileSdk / targetSdk | 35 | **37** |
| minSdk | 26 | 26（据え置き） |

主要ライブラリ: Compose BOM 2026.08.00 / core-ktx 1.19.0 / lifecycle 2.11.0 / activity-compose 1.13.0 /
play-services-location 21.4.0 / Room 2.8.4 / WorkManager 2.11.2 / DataStore 1.2.1 / OkHttp 5.5.0 /
kotlinx-serialization 1.11.0 / kotlinx-coroutines-play-services 1.11.0

### 移行で判明した注意点

1. **AGP 9 では Kotlin サポートが組み込みになった。**
   `org.jetbrains.kotlin.android` プラグインを適用するとビルドが失敗するため削除済み。
2. **Kotlin は 2.3.21 に固定している。**
   最新は 2.4.10 だが、KSP 2.3.11 との組み合わせを優先した。lint が更新を勧める警告を出すが意図的。
3. **fragment を 1.9.0 に明示的に引き上げている。**
   `play-services-base` が古い fragment 1.1.0 を引き込み、`lintVitalRelease` が fatal を出すため。
   アプリ自体は Fragment を使っていない。
4. **Android SDK の追加インストールが必要だった。**
   `platforms;android-36`, `platforms;android-37.0`, `build-tools;36.1.0`, `build-tools;37.0.0`

---

## 4. ビルド方法

システムのJDKは 26 で Gradle 9.7.1 が未対応のため、CLIからビルドする場合は Android Studio 同梱のJBRを指定する。

```bash
export JAVA_HOME="/Users/YOUR_NAME/Applications/Android Studio.app/Contents/jbr/Contents/Home"
cd android-app
./gradlew :app:assembleDebug
```

Android Studio から開く場合は、Studio が自身のJDKを使うため設定不要。

サーバーURLは `local.properties` に書く（リポジトリには含まれない）。

```properties
chikaku.serverBaseUrl=https://your-server.example.com/
```

未設定でもビルドは通り、アプリ内の「詳細設定（サーバーURL）」から実行時に上書きできる。

### 検証済みの結果

```
:app:assembleDebug    BUILD SUCCESSFUL   app-debug.apk            15MB
:app:assembleRelease  BUILD SUCCESSFUL   app-release-unsigned.apk 1.8MB  (R8 + lintVitalRelease 通過)
:app:lintDebug        エラー 0 / 警告 4
```

残る警告4件はいずれも既知・意図的なもの。

| 警告 | 理由 |
|---|---|
| `BatteryLife` | 電池最適化の除外要求。家族内サイドロード前提の選択（マニフェストに明記） |
| `NewerVersionAvailable` ×2 | Kotlin 2.4.10 の存在。KSP との組み合わせを優先して 2.3.21 に固定 |
| `ObsoleteSdkInt` | `mipmap-anydpi-v26`。`-v26` を外すと AAPT がリソースを解決できなくなるため据え置き |

---

## 5. サーバー実装時に必要な契約

> サーバーは実装済み（`server/`、Cloudflare Workers + D1）。
> 構成の判断は [architecture-decisions.md](architecture-decisions.md)、認証は [authentication.md](authentication.md) を参照。

**これらの形はアプリ側で先に決めたもの。Rust 側の実装をここへ合わせる必要がある。**

### `POST /api/v1/devices/register`

```jsonc
// リクエスト
{ "invite_code": "...", "device_name": "お父さんのスマホ", "device_model": "Google Pixel 8" }
// レスポンス
{ "device_id": "...", "device_token": "...", "family_id": "..." }
```

### `POST /api/v1/location`

ヘッダ `Authorization: Bearer <device_token>`

```jsonc
{
  "device_id": "...",
  "lat": 35.6812,
  "lng": 139.7671,
  "accuracy": 24.5,          // メートル。取得できない場合は -1
  "timestamp": "2026-08-21T06:12:00Z",  // ISO-8601 (UTC)
  "battery_level": 78        // パーセント。取得できない場合は -1
}
```

1リクエスト1件。キューの消化はクライアントがこれを繰り返して行う。

### アプリが期待するステータスコードの扱い

| コード | アプリの挙動 |
|---|---|
| 2xx | キューから削除し、最終送信時刻を更新 |
| 401 / 403 | 送信を止める。ペアリングやり直しが必要と判断 |
| 408 / 429 / 5xx | 一時的な障害とみなし、バックオフして再試行 |
| その他 4xx | 再試行しても無駄と判断し、10回試行後に破棄 |

エラー本文は `{"message": "..."}` または `{"error": "..."}` を読む（無くても動作する）。

---

## 6. 未着手・残課題

### Androidアプリ

- **ユニットテストが未作成。** `LocationRepository` の距離判定は `android.location.Location` に
  依存するため、Robolectric の追加が前提になる
- ジオフェンス（CLAUDE.md §2.2 発展形）は未実装。フェーズ3の想定どおり後回し
- Activity Recognition API による静止時の追加最適化も未実装
- リリース署名設定（`*.jks` は `.gitignore` 済み、署名configは未定義）

### プロジェクト全体

- `server/`（Rust + Axum）の実装
- `client/`（Webダッシュボード）の実装。CLAUDE.md の構成案では `dashboard/` という名前
- FCM Web Push 統合（フェーズ2）
- **このディレクトリはまだ git リポジトリではない**（`.gitignore` は用意済みだが `git init` 未実行）

### CLAUDE.md §8 の未確定事項の現状

| 項目 | 状態 |
|---|---|
| フロントエンドの技術選定 | 未決定 |
| ホスティング環境 | 未決定 |
| 親デバイスとfamilyの紐付け方法 | **招待コード方式で実装済み**（サーバー側の発行ロジックは未実装） |
| 位置情報履歴の保持期間 | 未決定（サーバー実装時に決める） |
