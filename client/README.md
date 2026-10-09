*[English](README.en.md)*

# chikaku-client

子側の見守りダッシュボード（CLAUDE.md §4）。
Vite + React + TypeScript の SPA。

- 地図は Leaflet + OpenStreetMap
- **ログイン画面は無い。** 認証は Cloudflare Access が済ませている（ADR-3）
- 本番では Worker が同じオリジンで配るため、API は常に相対パス `/api/v1/...`（ADR-2）

技術選定の理由は [`docs/architecture-decisions.md`](../docs/architecture-decisions.md) の ADR-1 を参照。

## 動かす

**リポジトリ直下から** `npm run dev` を実行する。Worker・ローカル D1・
Access の代役をまとめて起動する。このディレクトリ単体で `npm run dev` を
実行すると API の転送先が無く、すべて 401 になる。

```sh
cd ..        # リポジトリ直下
npm run dev  # → http://localhost:5173
```

開発用の家族と子アカウント（`dev@example.com`）は自動で作られ、
サインイン済みの状態で開く。

```sh
npm run build      # dist/ に出力。wrangler.jsonc の assets.directory がここを指す
npm run typecheck
```

## 画面の考えかた

見守る側が最初に知りたいのは座標ではなく **「その情報がどれだけ新しいか」**。
そのため各端末カードは相対時刻（「14分前」）を最も大きく出し、
鮮度を色つきのチップ（最新 / やや古い / 古い / 未受信）で示す。

**測位した時刻と、サーバーに届いた時刻を区別している。** 両者が5分以上
離れている場合は「圏外だった可能性があります」と添える。見守る側にとっては
「その間は連絡が取れていなかった」という情報そのものなので、隠さない。

地図では測位誤差を円で重ねる。点だけを打つと精度を過大に見せてしまう。

## 構成

| | |
|---|---|
| `src/api/` | サーバーの JSON と対応する型、fetch ラッパ |
| `src/hooks/usePolling.ts` | 一定間隔の再取得。タブが見えていない間は止まる |
| `src/components/MapView.tsx` | Leaflet を直接扱う。react-leaflet は使わない |
| `src/lib/format.ts` | 相対時刻・鮮度・電池・誤差の表示 |

`src/api/types.ts` は `server/src/routes/*.rs` の Serialize 構造体と対。
**サーバー側を変えたらここも変える。**

### react-leaflet を使っていない理由

React のバージョンに追随する中間ライブラリを挟まずに済み、
マーカーやレイヤーの更新タイミングを自分で制御できるため。
Leaflet 既定のマーカー画像はバンドラを通すと URL が壊れるので、
`DivIcon` で自前に描いている。

### ポーリングについて

MVP はプッシュ通知を使わずポーリングで成立させる（CLAUDE.md §6 フェーズ1）。
端末は「位置が変わったときだけ」送るので、30秒より細かく聞いても
新しい情報は出てこない。フェーズ2で FCM Web Push に置き換える。

## 未実装

- FCM Web Push の受信（Service Worker）— フェーズ2
- ジオフェンスの設定 UI — フェーズ3
- 家族・子アカウントの管理画面（現在は `wrangler d1 execute` で登録）
