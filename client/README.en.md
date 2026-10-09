*[日本語版](README.md)*

# chikaku-client

The child-side monitoring dashboard (CLAUDE.md §4). A Vite + React +
TypeScript SPA.

- The map is Leaflet + OpenStreetMap
- **There is no login screen.** Cloudflare Access has already handled
  authentication (ADR-3)
- In production the Worker serves this from the same origin, so the API is
  always a relative `/api/v1/...` path (ADR-2)

The reasoning behind the technology choice is ADR-1 in
[`docs/architecture-decisions.en.md`](../docs/architecture-decisions.en.md).

## Running it

Run `npm run dev` **from the repository root.** That brings up the Worker, a
local D1 and the Access stand-in together. Running `npm run dev` in this
directory alone leaves the API with nowhere to forward to, and everything
returns 401.

```sh
cd ..        # repository root
npm run dev  # → http://localhost:5173
```

A development family and child account (`dev@example.com`) are created
automatically, and the page opens already signed in.

```sh
npm run build      # outputs to dist/, where wrangler.jsonc's assets.directory points
npm run typecheck
npm test           # vitest
```

## How the screen is conceived

The first thing a watching family member wants is not coordinates but
**how fresh the information is.** So each device card shows relative time
("14 minutes ago") in the largest type, with freshness as a coloured chip
(current / slightly old / old / nothing received).

**The time a position was measured and the time it reached the server are kept
distinct.** More than five minutes apart adds "may have been out of signal."
For the person watching, that gap is itself the information — "we were out of
contact then" — so it is not hidden.

The map overlays the positioning error as a circle. A bare dot overstates the
precision.

## Layout

| | |
|---|---|
| `src/api/` | Types matching the server's JSON, and a fetch wrapper |
| `src/hooks/usePolling.ts` | Refetch on an interval; pauses while the tab is hidden |
| `src/components/MapView.tsx` | Works with Leaflet directly; no react-leaflet |
| `src/lib/format.ts` | Relative time, freshness, battery and accuracy display |
| `src/lib/trust.ts` | Whether a fix can be taken at face value (Issue #16) |

`src/api/types.ts` pairs with the Serialize structs in
`server/src/routes/*.rs`. **Change the server and this changes too.**

### Why not react-leaflet

It avoids an intermediate library that has to track React versions, and keeps
control over when markers and layers update. Leaflet's default marker images
break under the bundler's URL handling, so they are drawn with `DivIcon`
instead.

### On polling

The MVP works without push notifications (CLAUDE.md §6, phase 1). Devices only
send when the position changes, so asking more often than every 30 seconds
produces nothing new. Phase 2 replaces this with FCM Web Push.

## Not implemented

- Receiving FCM Web Push (service worker) — phase 2
- Geofence configuration UI — phase 3
- A management screen for families and child accounts (currently registered
  with `wrangler d1 execute`)
