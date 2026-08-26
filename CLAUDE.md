# CLAUDE.md — 高齢者見守り位置情報システム

このファイルは本プロジェクトでClaude Codeが実装を進める際の指針・仕様書です。

## 1. プロジェクト概要

**ゴール**: 離れて暮らす高齢の父母の居場所を、子供（複数人を想定）が常時把握できるようにする。

**構成要素**:

| コンポーネント | 役割 | 技術 |
|---|---|---|
| Androidアプリ（親側） | 位置情報を検知し、変化があったときだけサーバーへ送信 | Kotlin |
| iPhoneアプリ（親側） | 同上。サーバー契約は Android と同一 | Swift（詳細は ADR-6） |
| バックエンドサーバー | 位置情報の受信・保存、子供側へのプッシュ通知配信 | Rust |
| Webダッシュボード（子側） | ブラウザから親の現在地・履歴を確認 | ブラウザ + FCM Web Push |

**設計思想**: 「常時把握」＝常時ポーリングではなく、位置が実際に変化したときのみ通信が発生するイベント駆動型にすることで、親のスマホのバッテリーを守る。

---

## 2. Androidアプリ（親側）

### 2.1 技術スタック
- 言語: Kotlin
- 測位: `FusedLocationProviderClient`（`play-services-location`）
- バックグラウンド動作: Foreground Service（`foregroundServiceType="location"`）
- オフライン時の再送・定期ヘルスチェック: WorkManager
- （任意・推奨）移動/静止判定: Activity Recognition API

### 2.2 位置変化検知の戦略（バッテリー最優先）
`LocationRequest.Builder` の以下のパラメータを組み合わせる：

- `setMinUpdateDistanceMeters(50f〜100f)` — 前回位置から一定距離動かない限り更新自体を発生させない（最重要。ポーリングではなく「変化」をトリガーにする核）
- `setPriority(Priority.PRIORITY_BALANCED_POWER_ACCURACY)` — GPS単独よりWi-Fi/セルベースで消費電力を抑える。屋外精度が必要な場面のみ`PRIORITY_HIGH_ACCURACY`に切替
- `setIntervalMillis` / `setMinUpdateIntervalMillis` — 基本間隔は数分〜十数分単位。歩行者の移動速度を考えれば十分
- `setMaxUpdateDelayMillis` — バッチ処理を有効化し、複数回分の位置更新をまとめてコールバックさせることでCPUウェイクアップ回数を削減

**発展形（推奨）**: 自宅・よく行く場所（病院、スーパー等）に`GeofencingClient`でジオフェンスを設定し、「エリアに出入りしたときだけ」通知する設計にすると、GPS常時測位より遥かに省電力。「今どこにいるか」の詳細位置と、「エリア内/外」の大まかな見守りを組み合わせるハイブリッド構成が現実的。

**静止時の追加最適化**: Activity Recognition APIで`STILL`を検知したら`setMinUpdateIntervalMillis`を大幅に延ばす（例: 自宅で座っている時間帯は数十分に1回で十分）。

### 2.3 バックグラウンド実行の要件（Android 14+を考慮）
- `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION`
- `ACCESS_BACKGROUND_LOCATION`（Android 10+、専用の説明画面を経てユーザーに許可させる必要あり。通常権限と同時request不可）
- `FOREGROUND_SERVICE_LOCATION`（Android 14+、マニフェストで`foregroundServiceType="location"`を明示）
- `POST_NOTIFICATIONS`（Android 13+、Foreground Serviceの常駐通知に必要）
- Playストア公開時は「機微な権限の使用目的」宣言フォームと、アプリ内でのプロミネントディスクロージャー（背景で位置情報を使う理由の明示画面）が必須

### 2.4 サーバー送信
- 位置が閾値を超えて変化した場合のみ HTTPS POST（`/api/v1/location`）
- 圏外・オフライン時はローカルDB（Room）にキューイングし、WorkManagerで再送
- 送信ペイロード例: `{device_id, lat, lng, accuracy, timestamp, battery_level, health}`
  - `health` は端末設定の健康状態 `{battery_unrestricted, notifications_enabled, background_location}`。
    測位時ではなく**送信時**の値を送る。省略可（iOS 版は送らない）

---

## 3. バックエンドサーバー（Rust）

### 3.1 フレームワーク
`Axum`を推奨（Tokioベースで非同期処理が素直に書け、ミドルウェア構成もシンプル）。`Actix-web`も選択肢だが、新規プロジェクトであればAxumの方がエコシステム的に扱いやすい。

### 3.2 主要エンドポイント
- `POST /api/v1/location` — Androidアプリからの位置情報受信
- `GET /api/v1/families/{family_id}/latest` — ダッシュボード向け最新位置取得
- `GET /api/v1/families/{family_id}/history?from=&to=` — 移動履歴取得
- `POST /api/v1/auth/login` — 子供アカウントのログイン
- `POST /api/v1/devices/register` — 親デバイスのペアリング登録

### 3.3 プッシュ通知（FCM）
- 子供側UIはWebなので **FCM Web Push**（Firebase JS SDK + Service Worker + VAPIDキー）で受信する構成
- サーバー側からの送信は **FCM HTTP v1 API**を使用（旧レガシーAPIキー方式は廃止済みのため使用不可）。サービスアカウントのOAuth2認証が必要（`reqwest` + JWT署名、または対応クレートを選定）
- 通知タイミング: 位置が一定距離変化した時、またはジオフェンス通過時

### 3.4 データストア
- 小〜中規模の家族利用が前提であれば SQLite（`sqlx`）で十分。将来的にPostgreSQLへの移行を見据えるならスキーマは移植しやすく設計
- テーブル例: `families`, `children_accounts`, `parent_devices`, `location_events`

---

## 4. Webダッシュボード（子側）

- ログイン後、家族に紐づく親の最新位置を地図上に表示（Leaflet + OpenStreetMap、またはGoogle Maps API）
- Service Worker（`firebase-messaging-sw.js`）でFCM Web Pushを受信し、ブラウザ通知＋画面リアルタイム更新
- 移動履歴の簡易表示（任意）
- 複数の子供（兄弟姉妹）が同じ親を見守れるよう、family単位でアカウントを束ねる

---

## 5. プライバシー・セキュリティ

- 通信は全てTLS必須
- 保存データへのアクセスは family_id ベースで厳格に制御（他家族のデータに到達不可）
- 位置履歴の保持期間を設定し、無期限保存を避ける（例: 90日でローテーション）
- 親本人が「見守られていること」を認識・同意した上で導入する（アプリ初回起動時に説明画面を設置）

---

## 6. 開発フェーズ

1. **MVP**: Android→Rust→DB保存→ダッシュボードでポーリング表示（プッシュ通知なし）で疎通確認
2. FCM Web Push統合でリアルタイム化
3. ジオフェンス機能追加（省電力運用への移行）
4. 複数子供アカウント・権限管理、履歴表示の充実

---

## 7. ディレクトリ構成案

```
project-root/
├── android-app/          # Kotlin, Foreground Service, WorkManager
├── ios-app/              # Swift, Core Location (SLC + 標準更新), SwiftData
├── server/               # Rust (Cloudflare Workers), API
├── client/               # ダッシュボード (Vite + React + TypeScript)
├── docs/                 # ADR・実装状況・認証設計
└── CLAUDE.md
```

> 実際の構成は上記のとおりで、当初案の `dashboard/` は `client/` になり、
> サーバーは Axum ではなく Cloudflare Workers 上の Rust になっている（ADR-2/5）。

---

## 8. 未確定事項（実装開始前に決める）

> **決定済みの項目は [docs/architecture-decisions.md](docs/architecture-decisions.md) に記録している。**
> フロントエンド技術選定・ホスティング環境・親デバイスとfamilyの紐付け方法・履歴の保持期間は決定済み。

- フロントエンドの技術選定（素のJS / React / 他）
- ホスティング環境（自宅サーバー / VPS / クラウド）
- 親デバイスとfamilyの紐付け方法（招待コード等）
- 位置情報履歴の具体的な保持期間
