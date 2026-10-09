*[日本語版](README.md)*

# chikaku-server

The backend for the elderly-monitoring location system (CLAUDE.md §3). A Rust
(workers-rs) Worker running on Cloudflare Workers.

- **The same Worker also serves the dashboard's static files** (single origin,
  ADR-2)
- The data store is D1 (SQLite compatible, ADR-4)
- Child-account authentication is handed to Cloudflare Access (ADR-3)
- Location history is deleted automatically by a Cron Trigger after the
  retention period (90 days by default)

Structural decisions and their reasoning are in
[`docs/architecture-decisions.en.md`](../docs/architecture-decisions.en.md);
the authentication detail is in
[`docs/authentication.en.md`](../docs/authentication.en.md).

FCM push notifications are not implemented (phase 2).

## Prerequisites

| | |
|---|---|
| Node.js | **22 or later** (wrangler 4 requires it). Pinned in `.tool-versions` at the repository root |
| Rust | `rustup target add wasm32-unknown-unknown` |
| worker-build | `cargo install worker-build` (0.8.1 or later) |

## Setup

### 0. Prepare the configuration file

**`wrangler.jsonc` is not tracked.** It holds account-specific values, so it
is treated the same way as `local.properties` and `Chikaku.xcconfig`.

```sh
cp wrangler.jsonc.example wrangler.jsonc
```

Replace the three `REPLACE_WITH_` values using the steps below.

### 1. Create the D1 database

```sh
npx wrangler d1 create chikaku
```

Write the `database_id` it prints into `REPLACE_WITH_D1_DATABASE_ID` in
`wrangler.jsonc` at the repository root.

```sh
npm run db:migrate     # wrangler d1 migrations apply chikaku --remote
```

### 2. Configure Cloudflare Access

Create a **self-hosted application** under Zero Trust > Access >
Applications.

| # | Domain / path | Policy |
|---|---|---|
| 1 | `<host>/api/v1/devices/register` | **Bypass** (everyone) |
| 2 | `<host>/api/v1/location` | **Bypass** (everyone) |
| 3 | `<host>/api/v1/healthz` | **Bypass** (everyone) |
| 4 | `<host>` | Allow (list the child accounts' email addresses) |

**The bypasses on 1–3 are required.** The Android app cannot perform an
interactive login, so it stops working the moment Access demands a sign-in.
Those routes are protected by the invite code and `device_token` (ADR-3).

Write application 4's **AUD tag** and the **team domain** into `vars` in
`wrangler.jsonc` (`REPLACE_WITH_ACCESS_APPLICATION_AUD` and
`REPLACE_WITH_TEAM`).

```jsonc
"vars": {
  "CHIKAKU_TEAM_DOMAIN": "https://<team>.cloudflareaccess.com",
  "CHIKAKU_POLICY_AUD": "<AUD tag>"
}
```

If these are wrong, JWT verification fails and the entire dashboard returns
401.

### 3. Register a family and child accounts

There is no CLI on Workers, so rows go in directly with
`wrangler d1 execute`. There are no passwords, since Access authenticates.
**Only people listed both here and in the Access policy can get in.**

```sh
npx wrangler d1 execute chikaku --remote --command "
  INSERT INTO families (id, name, created_at)
  VALUES (lower(hex(randomblob(16))), 'Our family', unixepoch() * 1000);
"

npx wrangler d1 execute chikaku --remote --command "
  INSERT INTO children_accounts (id, family_id, email, display_name, created_at)
  SELECT lower(hex(randomblob(16))), id, 'child@example.com', 'Eldest son', unixepoch() * 1000
  FROM families WHERE name = 'Our family';
"
```

**Adding a sibling later takes more than this INSERT.** The same email
address has to go into the Access policy as well. See
[Operations — adding a child account](#adding-a-child-account).

### 4. Deploy

```sh
npm --prefix client run build   # build the client first
npm run deploy
```

`assets.directory` in `wrangler.jsonc` points at `client/dist`, so **the
client must be built first.** Out of order, stale (or missing) static files
get deployed.

## Operations

Work that recurs after deployment. For the initial build-out, see "Setup".

### Adding a child account

How to add someone who can see the dashboard — a new sibling, for instance.

**There are two places to register, and one alone does not let them in.**

| # | Place | Role | What breaks without it |
|---|---|---|---|
| 1 | The Cloudflare Access policy | Lets them sign in | They never get past the sign-in screen (stuck at 302) |
| 2 | `children_accounts` in D1 | Decides which family they can see | Sign-in works but every API returns **403** |

The duplication is deliberate: if the Access permissions are ever widened too
far, the D1 side acts as a brake (ADR-3,
[`docs/authentication.en.md`](../docs/authentication.en.md) §3).

**The email address must match exactly in both places.** Case is absorbed by
`lower()` on the `children_accounts` side, but an alias or a different address
is treated as a different person.

#### 1. Add them to the Access policy

1. [Zero Trust dashboard](https://one.dash.cloudflare.com/) →
   **Access** → **Applications**
2. Choose **`chikaku`** — not `chikaku-device-api`, which is the bypass for
   parent devices and is not there to let people through
3. **Policies** → edit `allow-children`
4. Add them to Include with the `Emails` selector
5. Save

Doing it through the API needs a token with `Access: Apps and Policies (Edit)`.
The OAuth token from `wrangler login` does not carry that permission.

#### 2. Add them to D1

```sh
npx wrangler d1 execute chikaku --remote --command "
  INSERT INTO children_accounts (id, family_id, email, display_name, created_at)
  SELECT lower(hex(randomblob(16))), id, 'sister@example.com', 'Eldest daughter', unixepoch() * 1000
  FROM families WHERE name = 'Our family';
"
```

`display_name` is the name shown on the dashboard. The `family_id` is looked
up from `families` rather than written directly, so as not to bake in an
assumption that there is only one family.

#### 3. Check

```sh
npx wrangler d1 execute chikaku --remote --command "
  SELECT c.email, c.display_name, f.name
  FROM children_accounts c JOIN families f ON f.id = c.family_id;
"
```

Give them the dashboard URL. **They do not need to create an account** — a PIN
arrives at their email address and that is the whole sign-in (one-time PIN).

#### There are no permission levels

Someone added this way is **exactly equivalent** to the existing child
accounts. As well as viewing positions, they can issue invite codes and revoke
devices. A view-only role is not implemented
([`docs/implementation-status.en.md`](../docs/implementation-status.en.md) §9).

### Removing a child account

Remove from both places, as when adding. **Deleting from D1 alone leaves them
signed in for as long as the Access session lives**, so fix the Access policy
first.

```sh
npx wrangler d1 execute chikaku --remote --command "
  DELETE FROM children_accounts WHERE lower(email) = 'sister@example.com';
"
```

Invite codes they issued survive, because `created_by` is
`ON DELETE SET NULL`. Delete them too if they should not be usable.

```sh
npx wrangler d1 execute chikaku --remote --command "
  DELETE FROM invite_codes WHERE used_at IS NULL;
"
```

## API

Every error body is `{"error": "<machine-readable code>", "message":
"<Japanese>"}`. The app shows the `message` from a 4xx directly to an elderly
user, so that text contains no jargon.

### Parent devices (Android) — bypassing Access

`Authorization: Bearer <device_token>`

| Method | Path | Purpose |
|---|---|---|
| POST | `/api/v1/devices/register` | Register a device with an invite code (no token needed) |
| POST | `/api/v1/location` | Send one position |

```jsonc
// POST /api/v1/devices/register
→ {"invite_code": "NX6DC7VC", "device_name": "Dad's phone", "device_model": "Pixel 9"}
← 200 {"device_id": "...", "device_token": "...", "family_id": "..."}

// POST /api/v1/location
→ {"device_id":"...","lat":35.6812,"lng":139.7671,"accuracy":18.0,
   "timestamp":"2026-08-21T08:36:03.143Z","battery_level":62}
← 202 {"stored": true}
```

`device_token` is returned in plaintext once, in this response only; the
server keeps nothing but the SHA-256. Invite codes are matched ignoring case,
hyphens and spaces, because they are read aloud and typed in. `stored: false`
means deduplication folded it into an existing row.

### Child accounts (dashboard) — inside Access

Cloudflare adds `Cf-Access-Jwt-Assertion`; there is nothing for the browser to
prepare.

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/v1/me` | Confirm the caller and their family |
| GET | `/api/v1/families/{family_id}/latest` | Latest position of every device |
| GET | `/api/v1/families/{family_id}/history` | Movement history |
| POST | `/api/v1/families/{family_id}/invites` | Issue an invite code |
| POST | `/api/v1/families/{family_id}/devices/{device_id}/revoke` | Revoke a device |

Query parameters for `history`: `from` and `to` (RFC 3339; the last 24 hours
if omitted), `device_id` to narrow to one device, and `limit` (1000 by
default, 5000 maximum). If the limit truncated the result, `truncated` is
`true` in the response.

`revoke` is for a lost device. The app's "disconnect" only erases on-device
data and leaves the token alive, so the server needs this operation too.

### What the status codes mean

The app's retry logic (`ApiClient.kt` / `UploadWorker.kt`) depends on this.
**Changing them changes the app's behaviour.**

| Code | When the server returns it | App behaviour |
|---|---|---|
| 202 | Position accepted | Remove from the queue |
| 400 | Invalid time, coordinates or battery level | Stop retrying and discard |
| 401 | Token revoked, bad invite code, impersonation, bad JWT | Re-pair |
| 403 | Passed Access but the email is not in `children_accounts` | — |
| 404 | Referred to another family's ID | — |
| 429 | Rate limited | Retry later |
| 5xx | Server-side problem | Retry later |

## Design decisions

**Times are stored as UTC Unix epoch milliseconds (INTEGER).** SQLite TEXT
datetimes compare incorrectly when the formatting varies — second precision,
offset notation — which silently breaks range queries over history. They are
converted to RFC 3339 on the way out as JSON.

**Integers always go through `db::num()` on the way into D1.** D1 rejects
JavaScript BigInt, and binding a Rust `i64` directly produces a
`D1_TYPE_ERROR`. They are passed as `f64` (epoch milliseconds are nowhere near
2^53).

**Deduplication uses a uniqueness constraint on `(device_id, recorded_at)`.**
The app queues positions while offline and retries them through WorkManager,
so a retry after a lost response can deliver the same fix twice.

**Rate limiting uses the Worker's built-in `ratelimit` binding.** WAF Rate
Limiting Rules are a zone-level feature and are unavailable on this deployment
(workers.dev, no custom domain). Device registration is 5 per 60 seconds by
source IP; issuing invite codes is 10 per 60 seconds per child account.
Position upload is not limited, because that would throttle a device draining
its queue after a long outage. The counter is held per Cloudflare location, so
spreading connections exceeds the configured value. **It is a speed limit, not
a cap on attempts.** Detail in
[`docs/implementation-status.en.md` §5.5](../docs/implementation-status.en.md).

**Invite redemption is decided on row counts.** A D1 `batch` is a SQL
transaction, but it only rolls back when a statement fails — not when zero rows
are updated. A conditional INSERT is combined with an UPDATE guarded by
EXISTS, and it counts as successful only when both affect exactly one row.

## Development

```sh
cargo test                                          # 9 unit tests
cargo clippy --target wasm32-unknown-unknown -- -D warnings
cargo fmt --check
npm run test:server                                 # 30 e2e
```

### About the e2e tests

`server/tests/e2e.mjs` runs against `wrangler dev --local`. It pins the
contract the Android side depends on — what the status codes mean and the
shape of the JSON. Look at it alongside any change to the app's
`ApiClient.kt`.

**Cloudflare Access verification is not bypassed.** The tests stand up a local
server publishing a JWKS from their own RSA key and point the Worker at it.
Signature verification and the `aud`, `iss`, `exp` and `kid` checks therefore
go through the same path as production, and tampered signatures, substituted
claims and expiry are confirmed to be rejected.

Local runs use a dedicated configuration, `server/wrangler.test.jsonc`. It is
kept separate from the production `wrangler.jsonc` so that it does not require
the static assets in `client/dist` and can point the team domain at the local
JWKS server.

## Not implemented

- Push notifications over FCM HTTP v1 (phase 2)
- Receiving geofence crossing events (phase 3)
