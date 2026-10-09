*[English](README.en.md)*

# みまもり — iPhone アプリ（親側）

親の iPhone で位置の変化を検知し、変わったときだけサーバーへ送る。
[android-app](../android-app) と**同じサーバー契約・同じ画面遷移・同じ閾値**で動く。
サーバーとダッシュボードには一切変更を入れていない。

- 構成上の判断 → [../docs/architecture-decisions.md](../docs/architecture-decisions.md)（iOS は **ADR-6**）
- 実装状況 → [../docs/implementation-status.md](../docs/implementation-status.md)
- サーバーの契約 → [../server/README.md](../server/README.md)

| | |
|---|---|
| Bundle ID | `com.damburisoft.chikaku.watch` |
| 表示名 | みまもり |
| 最低 iOS | 17.0（iPhone XS 以降） |
| 言語 | Swift 6 / SwiftUI / SwiftData |
| 外部依存 | **なし**（CocoaPods / SPM ともに使っていない） |

---

## 1. 用意するもの

```
Xcode 26 以降
```

接続先を書く。`Config/Chikaku.xcconfig` は `.gitignore` 済み。

```sh
cp Config/Chikaku.xcconfig.example Config/Chikaku.xcconfig
$EDITOR Config/Chikaku.xcconfig
```

> **xcconfig では `//` 以降が行コメントとして落ちる。**
> `https://` をそのまま書くとスキームだけになって静かに壊れるため、
> 雛形ではスラッシュを変数経由で挟んでいる。この形を崩さないこと。

未設定でもビルドは通る。その場合はアプリ内の「詳細設定（サーバーURL）」から
実行時に入力できる。

## 2. ビルドと実行

```sh
# シミュレータ
xcodebuild -project Chikaku.xcodeproj -scheme Chikaku \
  -sdk iphonesimulator -configuration Debug build

# 実機向け（署名なしの確認用）
xcodebuild -project Chikaku.xcodeproj -scheme Chikaku \
  -sdk iphoneos -configuration Release \
  CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO build
```

Xcode で開く場合は `Chikaku.xcodeproj` をそのまま開く。
プロジェクトファイルは Xcode 16 以降の**ファイルシステム同期グループ**を使っており、
`Chikaku/` にファイルを足せば自動で target に入る（pbxproj の編集は要らない）。

### 実機で動かすには

**位置情報のバックグラウンド取得は実機でしか意味のある確認ができない。**
シミュレータは端末の移動も電池残量も再現しない。

無料プロビジョニング（Personal Team）でも実機インストールはできるが、
プロファイルが **7日で失効**する。親の端末に入れっぱなしにするには
Apple Developer Program の有効なメンバーシップが要る。

## 3. 通信の契約を確かめる

サーバーのステータスコードを変えると、アプリの再送ロジックの意味が変わる。
その対応をローカルの Worker に対して機械的に確かめる。

```sh
npm run test:ios     # リポジトリ直下で
```

`Chikaku/Data/ApiClient.swift` などアプリ本体のソースをそのままコンパイルして
叩くので、**テスト用の複製を持たない**。UI は経由しない。

| 確認していること | 期待 |
|---|---|
| 招待コードでの登録 | 成功し `device_id` / `device_token` が返る |
| 使用済みコードの再利用 | `unauthorized`（ペアリングやり直し） |
| 正常な位置情報 | `success`（キューから削除） |
| 同一測位の再送 | `success`（重複はサーバーが畳む） |
| 不正な緯度 / 範囲外の電池残量 | `clientError` 400（再試行せず破棄） |
| 電池残量 `-1`（取得不能） | 受理される |
| 誤ったトークン / 他端末の `device_id` | `unauthorized` |
| RFC 3339 の整形 | ミリ秒付き UTC |

---

## 4. 構成

```
Chikaku/
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
│   ├── ApiModels.swift       リクエスト/レスポンス DTO
│   └── Timestamp.swift       RFC 3339 の整形
├── Location/
│   ├── LocationTuning.swift    測位パラメータと閾値の集約点
│   ├── LocationRepository.swift 「送るべきか」の判定とキュー投入
│   └── LocationTracker.swift   CLLocationManager（SLC + 標準更新）
├── Upload/
│   ├── Uploader.swift              キューの掃き出し
│   └── BackgroundTaskScheduler.swift BGTaskScheduler への予約
└── UI/
    ├── RootView.swift        画面ルーティング
    ├── DisclosureView.swift  同意画面
    ├── PairingView.swift     招待コード入力
    ├── PermissionView.swift  権限を段階に分けて取得
    ├── StatusView.swift      定常状態の画面
    ├── AppModel.swift
    ├── Common.swift
    └── Theme.swift
```

### Android 版との対応

| Android | iOS | 備考 |
|---|---|---|
| Foreground Service | `startUpdatingLocation` + Background Modes | 常駐通知は無い |
| `BootReceiver` / `WatchdogWorker` | Significant Location Change | 終了・再起動から OS が起こす |
| `WorkManager`（指数バックオフ） | 位置更新ごとの掃き出し + `BGTaskScheduler` | **iOS は実行時刻を保証しない** |
| Room | SwiftData | バックアップ除外済み |
| DataStore | UserDefaults + Keychain | トークンだけ Keychain |
| OkHttp | URLSession | 依存を増やさない |
| `res/values/strings.xml` | `Strings.swift` | 日本語単一言語 |

## 5. バッテリー戦略（CLAUDE.md §2.2 準拠）

iOS には更新間隔の概念が無いため、Android の「間隔」に当たるつまみが無い。
省電力は**どの測位サービスを使うか**で作る。詳細は ADR-6。

**第1段: OS レベルで更新自体を抑制**

| パラメータ | 値 | 意図 |
|---|---|---|
| `distanceFilter` | 50m | **これが核**。動かない限りコールバック自体が発生しない |
| `desiredAccuracy` | `kCLLocationAccuracyHundredMeters` | GPS 単独を避け Wi-Fi / セル測位を使う |
| Significant Location Change | 併走 | 消費はほぼ無視でき、終了・再起動からアプリを起こす |
| `pausesLocationUpdatesAutomatically` | **false** | true だと再開が保証されず見守りが穴を開ける（ADR-6） |

**第2段: アプリ側で送信を間引く**（`LocationRepository`）

- 前回キュー投入地点から **50m 以上** 動いたときのみ送信対象
- 動きがなくても **30分に1回**はヘルスチェックとして送る（iOS では best-effort）
- 精度 **500m 超** の測位結果は破棄
- 測位時刻が **5分以上前**のものは破棄。CoreLocation は起動直後に
  キャッシュ済みの古い位置を返すことがあり、それを「今いる場所」として
  送ると子側に嘘を見せる

## 6. オフライン耐性

測位結果は必ず SwiftData のキューへ書き、送信成功後に削除する。

| 事象 | 挙動 |
|---|---|
| 圏外・通信失敗 | `retryLater`。次の位置更新か `BGTaskScheduler` で再試行 |
| サーバー 5xx / 408 / 429 | `retryLater` |
| 401 / 403 | `needsRepairing`。ペアリングやり直しが必要 |
| その他 4xx | 試行10回で該当データを破棄 |
| 長期圏外 | キュー上限500件、古いものから間引き |
| アプリが終了させられた | SLC が位置変化で起こし直す |
| 端末再起動 | 同上（`BootReceiver` に当たるものは無い） |

## 7. プライバシー・セキュリティ（CLAUDE.md §5）

- TLS 必須。`https` 以外は debug ビルドでのみ許可（`resolve()` で判定）
- ATS は `NSAllowsLocalNetworking` のみ。`NSAllowsArbitraryLoads` は使わない
- `device_token` は Keychain。`kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`
  - `WhenUnlocked` にしない: 位置情報による起動は画面ロック中にも起きる。
    再起動後に親が画面を開くまで送信が全部失敗し、原因も見えなくなる
  - `ThisDeviceOnly`: バックアップ復元や端末間転送でトークンが複製されない
- 未送信キューは iCloud バックアップから除外（`-wal` / `-shm` も含めて）
- ログに位置情報そのものを出さない
- 「家族との接続を解除」で端末内のペアリング情報と未送信キューを全消去
- 初回起動時に必ず同意画面を通す（同意なしでは見守り開始不可）

## 8. 開発用の細工

`#if DEBUG` でのみ有効な起動引数がある。リリースビルドには含まれない。

```sh
# 同意済み・未ペアリング（ペアリング画面）
xcrun simctl launch <dev> com.damburisoft.chikaku.watch -chikakuSeedConsent

# ペアリング済み（権限画面 → 状態画面）
xcrun simctl launch <dev> com.damburisoft.chikaku.watch -chikakuSeedPairing

# 位置情報の許可をシミュレータ側で与える
xcrun simctl privacy <dev> grant location-always com.damburisoft.chikaku.watch
```

## 9. まだ無いもの

- **プッシュ通知**。ダッシュボードのポーリングで成立させている（フェーズ2）
- **ジオフェンス**（`CLMonitor`）による更なる省電力化（フェーズ3）
- **見守りが止まったときの通知**。権限は取っているが送出はまだ実装していない
- **Apple Watch 版**。watchOS は SLC もリージョン監視も非対応で、
  バックグラウンド測位を開始できるのがフォアグラウンドの間だけのため、
  「一度アプリを閉じたら再開できない」。見守り端末としては成立しない
