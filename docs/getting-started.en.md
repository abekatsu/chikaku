# Development setup and deployment

[日本語](getting-started.md) | English

## Prerequisites

| | |
|---|---|
| Node.js | **22 or newer** (required by wrangler 4). Pinned in `.tool-versions` |
| Rust | `rustup target add wasm32-unknown-unknown` |
| worker-build | `cargo install worker-build` (0.8.1 or newer) |
| JDK | **17 or newer**, for the Android app (the JBR bundled with Android Studio works) |
| Xcode | Only if you touch the iPhone app |

For the server and dashboard alone, Node and Rust are enough.

## Running locally

From the repository root:

```sh
npm install
npm run dev        # → http://localhost:5173
```

This starts the Worker, a local D1, and a **stand-in for Cloudflare Access**
together. The page opens already signed in as a development child account
(`dev@example.com`).

**There is no local escape hatch that disables JWT verification.** Such a switch
could leak into production, so the dev harness mints a real JWT that passes the
same verification instead.

### Tests

```sh
npm run test:server   # 33 server e2e tests
npm run typecheck     # dashboard type check

cd server
cargo test
cargo clippy --target wasm32-unknown-unknown -- -D warnings
```

## Android app

```sh
cd android-app
./gradlew :app:assembleDebug
```

If your system JDK is too new, point at a JDK between 17 and 21:

```sh
JAVA_HOME=/path/to/jdk-21 ./gradlew :app:assembleDebug
```

The server URL goes in `local.properties`, which is not committed:

```properties
chikaku.serverBaseUrl=https://your-worker.workers.dev/
```

**The build succeeds without it** — the URL can be overridden at runtime from the
app's advanced settings. Release signing is read from the same file
(see §8.3 of [implementation-status.md](implementation-status.md), Japanese).

```sh
./gradlew :app:testDebugUnitTest   # 13 unit tests
./gradlew :app:lintVitalRelease
```

**Install the release build on the parent's device, not the debug build.** The
debug build logs HTTP bodies — which contain locations — and `run-as` can read
the app's on-device data.

## iPhone app

```sh
cd ios-app
cp Config/Chikaku.xcconfig.example Config/Chikaku.xcconfig   # set the server URL
xcodebuild -project Chikaku.xcodeproj -scheme Chikaku -sdk iphonesimulator build
```

You can also just open `Chikaku.xcodeproj` in Xcode. There are no external
dependencies.

**In an xcconfig file, everything after `//` is stripped as a comment.** Writing
`https://` directly leaves you with just the scheme and fails silently, so the
template interpolates the slashes through a variable. **Keep that shape.**

```sh
npm run test:ios   # 12 wire-contract tests (starts a local Worker automatically)
```

## Deploying to Cloudflare

[`server/README.md`](../server/README.md) has the full procedure (Japanese).
The essentials:

### 1. Create the D1 database

```sh
npx wrangler d1 create chikaku
```

Put the returned `database_id` into `wrangler.jsonc` at the repository root.

```sh
npm run db:migrate     # wrangler d1 migrations apply chikaku --remote
```

### 2. Configure Cloudflare Access

Create a self-hosted application under Zero Trust > Access > Applications.

| # | Path | Policy |
|---|---|---|
| 1 | `/api/v1/devices/register` | **Bypass** |
| 2 | `/api/v1/location` | **Bypass** |
| 3 | `/api/v1/healthz` | **Bypass** |
| 4 | the whole host | Allow (list the children's email addresses) |

**Bypasses 1 to 3 are mandatory.** The parent apps cannot log in interactively,
so they break the moment Access asks them to sign in. Those endpoints are
protected by the invite code and the `device_token` instead
([ADR-3](architecture-decisions.md), Japanese).

Copy application 4's **AUD tag** and your **team domain** into `vars` in
`wrangler.jsonc`. **If either is wrong, JWT verification fails and the entire
dashboard returns 401.**

### 3. Register a family and its children

Insert the rows directly with `wrangler d1 execute` (see
[`server/README.md`](../server/README.md)). **Only someone listed in both the
Access policy and `children_accounts` can get in**, so adding a sibling means
updating two places.

### 4. Deploy

```sh
npm run deploy     # builds the client, then wrangler deploy
```

**Apply migrations before deploying, not after.** An additive migration will not
break the old Worker, but the reverse order leaves a new Worker selecting columns
that do not exist yet.

## Settings on the parent's device

Installing the app is not enough. **If any of these are off, monitoring breaks
silently.**

1. Settings → Apps → (the app) → Battery → **Unrestricted**
2. Allow notifications, on the same screen
3. Location permission set to **Allow all the time**
4. Exempt it from any OEM power-saving feature as well (for example SHARP's
   "Long-life battery switch")

**When any of them is off, a warning appears both on the app's status screen and
on the children's dashboard** ([#4](https://github.com/abekatsu/chikaku/issues/4)).
