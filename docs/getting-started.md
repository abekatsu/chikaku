# 開発環境の構築と配備

日本語 | [English](getting-started.en.md)

## 前提

| | |
|---|---|
| Node.js | **22 以上**（wrangler 4 の要求）。`.tool-versions` で固定済み |
| Rust | `rustup target add wasm32-unknown-unknown` |
| worker-build | `cargo install worker-build`（0.8.1 以上） |
| JDK | **17 以上**（Android アプリ。Android Studio 同梱の JBR でもよい） |
| Xcode | iPhone アプリを触る場合のみ |

サーバーとダッシュボードだけなら Node と Rust があれば動く。

## ローカルで動かす

リポジトリ直下で実行する。

```sh
npm install
npm run dev        # → http://localhost:5173
```

Worker・ローカル D1・**Cloudflare Access の代役**をまとめて起動する。
開発用の子アカウント（`dev@example.com`）でサインイン済みの状態で開く。

**ローカルに Access は居ないが、検証を無効化する抜け道は作っていない。**
本番に混入しかねないため、代わりに検証を通る本物の JWT を用意する方式にしている。

### テスト

```sh
npm run test:server   # サーバー e2e 33件
npm run typecheck     # ダッシュボードの型検査

cd server
cargo test
cargo clippy --target wasm32-unknown-unknown -- -D warnings
```

## Android アプリ

```sh
cd android-app
./gradlew :app:assembleDebug
```

システムの JDK が新しすぎて動かない場合は、JDK 17〜21 を明示する。

```sh
JAVA_HOME=/path/to/jdk-21 ./gradlew :app:assembleDebug
```

接続先は `local.properties`（リポジトリに含まれない）に書く。

```properties
chikaku.serverBaseUrl=https://your-worker.workers.dev/
```

**未設定でもビルドは通る。** アプリ内の「詳細設定」から実行時に上書きできる。
リリース署名も同じファイルから読む（[implementation-status.md §8.3](implementation-status.md)）。

```sh
./gradlew :app:testDebugUnitTest   # 単体 13件
./gradlew :app:lintVitalRelease
```

**親の端末には release ビルドを入れること。** debug ビルドは HTTP の本文
（位置情報を含む）をログに出し、`run-as` で端末内のデータを読めてしまう。

## iPhone アプリ

```sh
cd ios-app
cp Config/Chikaku.xcconfig.example Config/Chikaku.xcconfig   # 接続先を書く
xcodebuild -project Chikaku.xcodeproj -scheme Chikaku -sdk iphonesimulator build
```

`Chikaku.xcodeproj` をそのまま Xcode で開いてもよい。外部依存は無い。

**xcconfig では `//` 以降が行コメントとして落ちる。** `https://` をそのまま
書くとスキームだけになって静かに壊れるため、雛形ではスラッシュを変数経由で
挟んでいる。**この形を崩さないこと。**

```sh
npm run test:ios   # 通信の契約確認 12件（ローカル Worker を自動で立てる）
```

## Cloudflare へ配備する

手順は [`server/README.md`](../server/README.md) に詳しい。要点だけ挙げる。

### 0. 設定ファイルを用意する

**`wrangler.jsonc` は追跡していない。** アカウント固有の値が入るため、
`local.properties` や `Chikaku.xcconfig` と同じ扱いにしてある。

```sh
cp wrangler.jsonc.example wrangler.jsonc
```

`REPLACE_WITH_` で始まる3箇所を、以下の手順で得た値に差し替える。

### 1. D1 を作る

```sh
npx wrangler d1 create chikaku
```

出力された `database_id` を `wrangler.jsonc` の
`REPLACE_WITH_D1_DATABASE_ID` に書き込む。

```sh
npm run db:migrate     # wrangler d1 migrations apply chikaku --remote
```

### 2. Cloudflare Access を設定する

Zero Trust > Access > Applications で self-hosted アプリケーションを作る。

| # | パス | ポリシー |
|---|---|---|
| 1 | `/api/v1/devices/register` | **Bypass** |
| 2 | `/api/v1/location` | **Bypass** |
| 3 | `/api/v1/healthz` | **Bypass** |
| 4 | `<host>` 全体 | Allow（子のメールアドレスを列挙） |

**1〜3 のバイパスは必須。** 親アプリは対話的ログインができないため、Access が
サインインを要求すると動かなくなる。これらは招待コードと `device_token` で
守られている（[ADR-3](architecture-decisions.md)）。

アプリケーション 4 の **AUD タグ**と**チームドメイン**を `wrangler.jsonc` の
`vars`（`REPLACE_WITH_ACCESS_APPLICATION_AUD` と `REPLACE_WITH_TEAM`）に書く。
**誤っていると JWT 検証が通らず、ダッシュボードが全て 401 になる。**

### 3. 家族と子アカウントを登録する

`wrangler d1 execute` で直接入れる（[`server/README.md`](../server/README.md)）。
**Access のポリシーと `children_accounts` の両方に載っている人だけ**が
アクセスできる。きょうだいを足すときは 2 箇所を揃える必要がある。

### 4. 配備する

```sh
npm run deploy     # client をビルドしてから wrangler deploy
```

**マイグレーションはデプロイより先に当てる。** 列を足すだけの追加変更なら旧
Worker は壊れないが、逆順にすると新 Worker がまだ無い列を読んで落ちる。

## 親の端末側の設定

インストールしただけでは足りない。**この 3 つが外れていると見守りが静かに壊れる。**

1. 設定 → アプリ → （アプリ名）→ 電池 → **「制限なし」**
2. 同じ画面で**通知を許可**
3. 位置情報を**「常に許可」**
4. OEM 独自の省電力機能（SHARP の「長エネスイッチ」など）からも除外

**外れていればアプリの状態画面と、子側のダッシュボードの両方に警告が出る**
（[#4](https://github.com/abekatsu/chikaku/issues/4)）。
