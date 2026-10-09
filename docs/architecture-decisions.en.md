*[日本語版](architecture-decisions.md)*

# Architecture decision records

Structural decisions and the reasoning behind them, including the items left
open as "undecided" in CLAUDE.md §8. **This file supersedes that section.**

Each record is written as decision, context, why the alternatives were
rejected, and what has to be accepted as a result.

| # | Decision | Status | Date |
|---|---|---|---|
| [1](#adr-1-vite--react--typescript-for-the-front-end) | Vite + React + TypeScript for the front end | Adopted | 2026-08-21 |
| [2](#adr-2-deploy-to-cloudflare-workers-on-a-single-origin) | Deploy to Cloudflare Workers on a single origin | Adopted | 2026-08-21 |
| [3](#adr-3-hand-child-account-authentication-to-cloudflare-access) | Hand child-account authentication to Cloudflare Access | Adopted | 2026-08-21 |
| [4](#adr-4-store-data-in-d1-sqlite-compatible) | Store data in D1 (SQLite compatible) | Adopted | 2026-08-21 |
| [5](#adr-5-write-the-worker-in-rust-workers-rs) | Write the Worker in Rust (workers-rs) | Adopted | 2026-08-21 |
| [6](#adr-6-build-ios-continuous-positioning-from-slc-plus-standard-updates) | Build iOS continuous positioning from SLC plus standard updates | Adopted | 2026-08-25 |

---

## ADR-1: Vite + React + TypeScript for the front end

### Decision

A plain React SPA built with Vite. No Next.js.

### Context

CLAUDE.md §8 left this open as "plain JS / React / something else". React and
Next.js are not at the same layer. React is a library for drawing UI and
decides nothing about routing, building or serving. Next.js is a framework
around all of that, and **in production it means a resident Node.js process.**

TypeScript is a first-class citizen in both, so it does not help decide.

### Why not Next.js

- **It adds a server.** The API lives entirely on the Rust side, so adding
  Next.js means two resident processes. Doubling what has to be operated is
  not worth it for a system serving one family.
- **Server-side rendering buys nothing here.** The dashboard sits behind a
  login and has no page that search engines could index. What it displays is a
  parent's current position — real-time and private — so there is nothing worth
  rendering ahead of time.
- **It fights the map library.** Leaflet touches `window` directly and breaks
  under SSR. The workaround is `dynamic(..., { ssr: false })`, which means
  switching off the feature Next.js is there for.

Static output via `output: 'export'` is possible, but that disables almost
everything Next.js adds. In which case: use Vite from the start.

### Accepted consequences

- Routing is a separate choice (React Router or similar)
- If this app ever needs public pages — a service description, say — the
  decision should be revisited

---

## ADR-2: Deploy to Cloudflare Workers on a single origin

### Decision

One Worker serves both the SPA's static files and the API **from the same
origin.** Workers Static Assets routes only the API paths to the Worker via
`run_worker_first`.

```jsonc
"assets": {
  "directory": "./client/dist/",
  "not_found_handling": "single-page-application",
  "binding": "ASSETS",
  "run_worker_first": ["/api/*"]
}
```

```
https://<host>/          → React static files
https://<host>/api/v1/*  → Worker (Rust)
```

### Context

The decision for "hosting environment (home server / VPS / cloud)" in
CLAUDE.md §8.

### Why a single origin pays off

**Cookies become usable without contortions.** An SPA on a separate origin
ends up keeping the session in `localStorage`, where XSS can take it. Same
origin means `HttpOnly` cookies are available — though in practice ADR-3 hands
cookie management to Access.

As a side effect, **CORS configuration disappears entirely.** The
`CHIKAKU_CORS_ORIGINS` setting from the Axum implementation has no job left.

### Accepted consequences

- **The client must be built before the Worker is deployed**, since
  `assets.directory` points at `client/dist`. The deployment procedure depends
  on that order.
- Deeper dependency on Cloudflare. Moving elsewhere means rewriting the
  Workers-specific APIs (D1 bindings, Static Assets).

---

## ADR-3: Hand child-account authentication to Cloudflare Access

### Decision

Put Cloudflare Access (Zero Trust) in front of the dashboard and **keep no
login screen, no stored passwords and no session management of our own.**

The Worker verifies the JWT in the `Cf-Access-Jwt-Assertion` header and uses
its `email` claim to look up `children_accounts` and obtain the `family_id`.

### Context

The Axum version carried its own passwords (Argon2id) and session tokens.
Several of the limitations listed in
[authentication.en.md](authentication.en.md) §11 would have had to be closed by
hand under that design.

### What it resolves

| Limitation of the old design | Under Access |
|---|---|
| No rate limiting (brute force possible) | Becomes Cloudflare's problem |
| No password change or reset | Becomes the identity provider's feature |
| Password hashes held locally | **Nothing is stored at all** |
| Session management written by hand | Access manages it |

The free tier covers 50 users, so a system for one family costs nothing extra.

### The JWT must be verified in our own code

Cloudflare's documentation states plainly that when a Worker also serves Static
Assets, **the Access context (`ctx.access`) is not passed to the Worker.** So
the Worker verifies the `Cf-Access-Jwt-Assertion` JWT itself:

1. Header `alg` is `RS256`; select the key matching `kid` from the JWKS
2. Verify the signature against the public keys at
   `{TEAM_DOMAIN}/cdn-cgi/access/certs`
3. `aud` matches the Access application's AUD tag
4. `iss` matches the team domain
5. `exp` is in the future

**Trusting the header alone is not enough** — it can be spoofed. The signature
is checked.

### The parent's Android device sits outside Access

The app cannot perform an interactive login, so these two routes bypass Access.
They remain protected by the invite code and `device_token` as before:

```
POST /api/v1/devices/register   ← invite code (single use)
POST /api/v1/location           ← Bearer device_token
```

The result is that **the authentication paths are fully separated by subject.**
Parent devices use a bearer token, child accounts an Access JWT. Neither
credential can reach the other's API by construction.

### Accepted consequences

- **The Cloudflare dependency now extends to authentication.** Dropping Access
  means building a login system from scratch.
- Misconfiguring the Access bypass either makes the parent-device endpoints
  demand a sign-in — **breaking the app** — or opens them too wide and leaves
  the dashboard unprotected. This is the one place where a configuration
  mistake is directly a hole.
- `children_accounts` is matched to the identity provider by email address. An
  address that passes Access but is absent from that table gets a 403 — two
  independent gates, Access's policy and this one.

---

## ADR-4: Store data in D1 (SQLite compatible)

### Decision

Drop the SQLite file plus sqlx and move to Cloudflare D1. The schema
(`migrations/0001_init.sql`) carries over almost unchanged, since D1 is SQLite
compatible.

### Why a rewrite was necessary

**Workers have no filesystem.** There is nowhere to put a SQLite file. Running
the Rust binary as-is in Containers was considered, but Cloudflare's
documentation states that **all disk is ephemeral** and a container that goes
to sleep gets a fresh disk on the next start — which is not somewhere to keep a
database file.

sqlx cannot speak to D1, so the data access layer is replaced with
`worker::D1Database`.

### Constraints discovered during the port

These came out of a minimal prototype built before the port. **Each one is
hard to diagnose from the symptom, which is why they are written down.**

| Constraint | Handling |
|---|---|
| **D1 rejects JavaScript BigInt** (`D1_TYPE_ERROR`). Binding a Rust `i64` produces a bigint and fails | Bind as `f64`. Epoch milliseconds are nowhere near 2^53, and INTEGER column affinity stores them as integers. Reading back as `i64` is exact (verified) |
| D1 rows cannot be deserialized into tuples | Receive them in a `#[derive(Deserialize)]` struct |
| Placeholders are only `?` and `?NNNN` — no named parameters | The existing SQL already uses the `?1` form |
| No interactive transactions | `batch()` acts as a SQL transaction, rolling the whole thing back on failure. Invite-code redemption relies on this for atomicity |

### Replacing the resident task

Retention cleanup used to be an infinite loop under `tokio::spawn`, but Workers
have no resident process. It becomes a **Cron Trigger** (the `scheduled()`
handler).

### Accepted consequences

- Local development runs on `wrangler dev --local` (D1 under Miniflare).
  `cargo test` alone cannot exercise the API end to end.
- D1 has its own features — read replicas, Time Travel — that go unused for now.

---

## ADR-5: Write the Worker in Rust (workers-rs)

### Decision

Port the existing Rust to workers-rs rather than rewriting the Worker in
TypeScript. Routing uses workers-rs's `Router`, not axum.

### Context

CLAUDE.md specifies Rust for the backend. **The authentication, authorization
and validation logic from the Axum implementation ports directly;** only the
data access layer and the outer routing needed rewriting.

In TypeScript, JWT verification would be three lines with `jose` and obviously
easier — at the cost of discarding the Rust implementation.

### What the port required

| Item | Handling |
|---|---|
| `worker` crate version | `worker-build` 0.8.1 requires `worker` 0.8 or later |
| Randomness (`getrandom`) | `wasm_js` feature plus `--cfg getrandom_backend="wasm_js"` in `.cargo/config.toml` |
| `uuid` v4 | Needs the `rng-getrandom` feature |
| Time | `worker::Date::now()`, not `chrono::Utc::now()` |
| Password hashing | Removed along with the argon2 dependency, per ADR-3 |
| Calling Web Crypto | `serde_wasm_bindgen` produces a JS `Map`, which SubtleCrypto cannot read. Use `js_sys::JSON::parse` to get a plain object |

### Accepted consequences

- WASM has a lot of sharp edges. The table above is a record of the ones
  actually hit.
- The build needs `worker-build` and the wasm32 target.
- **wrangler 4 requires Node.js 22 or later.** Pinned to the 22 series in
  `.tool-versions` at the repository root.

---

## ADR-6: Build iOS continuous positioning from SLC plus standard updates

### Decision

On the parent's iPhone, run **Significant Location Change (SLC) and standard
location updates (`startUpdatingLocation`) at the same time.** Set
`pausesLocationUpdatesAutomatically` to `false`.

### Context

The Android version stays resident as a foreground service, with `WorkManager`
handling liveness and retries. iOS has neither.

- No resident service. The app can be terminated at any time.
- No ongoing notification. There is no way to pin "this is running" on screen.
- The nearest equivalent to `WorkManager` is `BGTaskScheduler`, which **makes
  no guarantee about when it runs.**

What iOS does have is SLC, whose power draw is close to negligible and which
**wakes the app even after it has been terminated, and even after the device
has rebooted.** One API covers the roles of Android's `BootReceiver` and
`WatchdogWorker` together. It fires roughly every 500 m of movement or every
few minutes, so the granularity is coarse.

So: granularity comes from standard updates (`distanceFilter` 50 m), and the
ability to come back comes from SLC. CLAUDE.md §2.2's "triggered by change, not
by polling" is satisfied by `distanceFilter`.

### Why not set `pausesLocationUpdatesAutomatically` to true

With it true, the OS decides there is no movement and stops updates. That is
attractive for battery, but **resumption is not guaranteed.** A parent sitting
at home who walks 200 m to the shops does not cross the SLC threshold, and the
paused standard updates do not come back — so the watch quietly develops a gap.

Consumption while stationary is already held down by `distanceFilter`: with no
movement there is no callback. There is nothing more to stop.
**"The watch stopped to save battery" is backwards for this purpose.**

### Why not `CLBackgroundActivitySession`

That exists to continue background positioning under "while using the app"
permission, at the cost of a persistent indicator at the top of the screen.
This app assumes "always" permission — the permission screen walks the user
there — so it is unnecessary.

### Why the health check is weaker than on Android

Android sends once every 30 minutes even with no movement, and `WorkManager`
guarantees it. Doing the same on iOS means relying on `BGAppRefreshTask`, and
**the OS decides when that runs, so a 30-minute interval is not honoured.**

The iOS health check is therefore best-effort. From the child's side, "a parent
on an iPhone gives coarser liveness confirmation while stationary than a parent
on Android". The dashboard shows freshness as a chip (§5.1), so the difference
surfaces as how often it reads "slightly old".

### Accepted consequences

- The stationary health-check interval is less certain than on Android
- "Always" permission is required; "while using the app" does not work as
  designed
- If the parent picks "while using the app" in one of iOS's periodic permission
  prompts, the watch stops. That is OS behaviour and cannot be blocked, so the
  design relies on the freshness display to make it noticeable
- Further power savings via geofencing (`CLMonitor`) are not implemented. That
  is phase 3 in CLAUDE.md §6

## Decisions carried over unchanged

These survive the port from the Axum implementation. Reasoning is in
[authentication.en.md](authentication.en.md).

- Times are stored as UTC epoch milliseconds (avoiding inconsistent TEXT
  datetime formats)
- A uniqueness constraint on `(device_id, recorded_at)` folds duplicate
  retransmissions together
- Only the SHA-256 of `device_token` is stored; the original is never kept
- Another family's `family_id` returns 404, not 403
- Location history is deleted automatically after the retention period
  (90 days by default)
