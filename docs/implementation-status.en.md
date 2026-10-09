*[日本語版](implementation-status.md)*

# Implementation status — location monitoring for elderly parents

A system that lets adult children see where their parents are, when the
parents live elsewhere. This document records **what currently works and what
does not.**

- Structural decisions and their reasoning → [architecture-decisions.en.md](architecture-decisions.en.md)
- Authentication and authorization design → [authentication.en.md](authentication.en.md)
- What happened on the device and how each call was made → [decision-log.en.md](decision-log.en.md)
- How to use each component → `server/README.en.md`, `client/README.en.md`, `ios-app/README.en.md`
- Post-deployment work (adding and removing child accounts) → "Operations" in `server/README.en.md`

Last updated: 2026-08-25

---

## 1. The whole picture

```
  Parent's phone            Cloudflare                    Child's browser
┌──────────────┐        ┌────────────────────────┐        ┌──────────────┐
│ Android app  │  HTTPS │  Access (auth)         │        │  Dashboard   │
│ (Kotlin)     │───────▶│    ↓                   │◀──────▶│  (React SPA) │
│ Foreground   │  only  │  Worker (Rust/wasm)    │  same  │              │
│ Service      │  when  │    ↕                   │ origin │ Leaflet map  │
├──────────────┤  the   │  D1 (SQLite)           │        │              │
│ iPhone app   │position│                        │        │              │
│ (Swift)      │───────▶└────────────────────────┘        └──────────────┘
│ SLC + std.   │changes   Cron: delete history after 90 days
└──────────────┘
```

**Both parent platforms speak the same contract.** From the server's side
there is no distinction between Android and iPhone; only `device_model`
differs.

| Component | Status | Detail |
|---|---|---|
| **Android app (parent)** | Complete | 23 Kotlin files. Both debug and release build |
| **iPhone app (parent)** | Complete, unverified on hardware | 25 Swift files. Both debug and release build. **Distribution needs the Apple Developer Program renewed** (§4.5) |
| **Server** | Complete | Cloudflare Workers (Rust) + D1. 9 unit tests / 30 e2e |
| **Dashboard (child)** | Complete | Vite + React + TypeScript. Map, history, invites, unpairing |
| **Deployed to production** | Done | `https://chikaku.<subdomain>.workers.dev`. D1, Access and Cron all live (§8.2) |
| **End-to-end on hardware** | Partial | Pairing and position upload work from a real device (§7.2). Movement, offline and battery were unverified at the time of writing (§7.3) |

In terms of the phases in CLAUDE.md §6, **phase 1 (MVP connectivity) is
complete on Android** — positions from a real device reach the live
environment. The iPhone version is implemented and its contract with the
server is verified, but on-device confirmation is waiting on the membership
renewal (§4.5).

**One Worker serves the API and the dashboard's static files from the same
origin.** Server and client are not separate deployment targets (ADR-2).

---

## 2. How the data flows

End to end, along the implemented path.

### 2.1 Pairing (once)

1. The child presses "issue an invite code" on the dashboard → an 8-character
   code, valid 24 hours, single use
2. The child passes the code to the parent verbally or on paper
3. The parent opens the app → consent screen → enters the code → grants
   permissions, one step at a time
4. The server returns a `device_id` and `device_token`, and the device is
   bound to the family

Invite codes are drawn from 27 characters, with the easily confused
`0 1 2 B I L O S Z` removed. On input, case, hyphens and spaces are ignored.

### 2.2 Sending positions (ongoing)

1. A positioning callback only fires once the device has moved 50 m from the
   last position
2. The app thins further — nothing below 50 m, but at least one send every
   30 minutes even without movement
3. The position is written to an on-device queue first, then sent over HTTPS
   and removed from the queue on success
4. The server folds duplicates together via a uniqueness constraint on
   `(device_id, recorded_at)`

Android attaches **the device's configuration health** — battery
optimisation, notifications, "always allow" — to every send (§3.5). The values
are read **at the moment of sending**, not when the position was queued:
stamping a queue that sat for hours offline with the settings from back then
would mean nothing.

**Offline, the send fails but the queue survives.** If only the response is
lost, the duplicate is folded on the server.

**From here the two platforms diverge.** The queue and what drives the retry
are different things.

| | Android | iOS |
|---|---|---|
| Queue | Room | SwiftData |
| Retry driver | WorkManager with exponential backoff | Drained on the next location update; `BGTaskScheduler` assists |
| 30-minute stationary health check | Guaranteed by WorkManager | Left to the OS — **the interval is not honoured** |

iOS drains "on each location update" because that is when the app is awake.
Details in §4.2 and ADR-6.

### 2.3 Viewing (when the child wants to)

1. The child opens the dashboard → Cloudflare Access demands a sign-in
2. The Worker verifies the Access JWT and identifies the family by email
   address
3. The latest position is fetched every 30 seconds (paused while the tab is
   hidden)
4. The map shows a pin and an accuracy circle, with the movement path on top
   if asked for

There are no push notifications yet; polling covers it (phase 2 replaces this
with FCM).

---

## 3. Android app (parent side)

Package / applicationId: `com.damburisoft.chikaku.watch`
Display name: **みまもり** (mimamori, "watching over") / version `0.1.0`
(versionCode 1)

### 3.1 Layout

```
app/src/main/java/com/damburisoft/chikaku/watch/
├── ChikakuApp.kt              Application. Creates the notification channel, schedules periodic work
├── Graph.kt                   Minimal service locator (no DI library)
├── MainActivity.kt            Screen routing, receives permission results
├── data/
│   ├── DeviceStorage.kt       Direct Boot detection and the device-protected Context
│   ├── StorageMigration.kt    Carrying data over from the previous storage location
│   ├── SettingsStore.kt       DataStore. Consent state, pairing info, last-sent time
│   ├── DeviceHealth.kt        Device configuration health (3.5)
│   ├── AppDatabase.kt         Room
│   ├── PendingLocation.kt     Outbox entity and DAO
│   ├── ApiClient.kt           OkHttp. ApiResult distinguishes failure reasons by type
│   └── ApiModels.kt           Request/response DTOs (kotlinx.serialization)
├── location/
│   ├── LocationTuning.kt      Where the positioning parameters and thresholds live
│   ├── LocationSource.kt      Classifying where a fix came from (3.7)
│   └── LocationRepository.kt  Decides "should this be sent" and queues it
├── service/
│   ├── LocationTrackingService.kt  Foreground service
│   ├── BootReceiver.kt             Automatic recovery after reboot or app update
│   ├── HeartbeatScheduler.kt       Schedules the stationary health check
│   ├── HeartbeatReceiver.kt        Receives it
│   └── TrackingNotification.kt     The ongoing notification
├── work/
│   ├── UploadWorker.kt        Drains the queue
│   ├── WatchdogWorker.kt      Hourly liveness check
│   └── UploadScheduler.kt     Registration with WorkManager
└── ui/
    ├── DisclosureScreen.kt    Prominent disclosure (consent screen)
    ├── PairingScreen.kt       Invite code entry
    ├── PermissionScreen.kt    Permissions requested across four steps
    ├── StatusScreen.kt        The steady-state screen
    ├── MainViewModel.kt
    ├── Common.kt              Shared components
    └── theme/Theme.kt
```

### 3.2 Battery strategy (per CLAUDE.md §2.2)

"Do not poll continuously" is implemented in two stages.

**Stage 1: suppress the updates at the OS level**

| Parameter | Value | Intent |
|---|---|---|
| `setMinUpdateDistanceMeters` | 50 m | **The core of it.** Without movement, no callback happens at all |
| `setPriority` | **`HIGH_ACCURACY`** | Originally `BALANCED`, since retracted (§3.7 / Issue #13). BALANCED barely uses GPS and recorded places that were never visited |
| `setIntervalMillis` | 5 min | Enough at walking speed |
| `setMinUpdateIntervalMillis` | 2 min | Floor on update frequency |
| `setMaxUpdateDelayMillis` | 15 min | Batched delivery, fewer CPU wakeups |

**Stage 2: thin the sends in the app** (`LocationRepository`)

- Only send once the device is **50 m or more** from the last queued position
- Send once every **30 minutes** regardless, as a health check — so the child
  can tell "stationary" apart from "something is wrong"
- Discard fixes whose accuracy is worse than **500 m**

**The health check is driven by a clock** (`HeartbeatScheduler` /
`HeartbeatReceiver`). Because of stage 1's `setMinUpdateDistanceMeters(50m)`,
**no positioning callback fires at all while stationary.** The send decision
lives inside that callback, so it is never reached. This was missed initially,
and produced a five-hour gap on a real device while the user was at home.

`AlarmManager.setAndAllowWhileIdle` is used, because WorkManager's periodic
execution is deferred to a maintenance window under Doze and cannot guarantee
30 minutes. Exact alarms are not used — "roughly every 30 minutes" suffices,
and it is not worth asking a parent for `SCHEDULE_EXACT_ALARM` on Android 12+.

The alarm targets **`HeartbeatReceiver`**, not the foreground service. Firing
while the service is dead would hit the background foreground-service start
restriction and throw where it cannot be caught, so a receiver takes it and
swallows the start failure.

### 3.3 Offline resilience

Every fix is written to the Room queue and deleted only after a successful
send.

| Event | Behaviour |
|---|---|
| Offline / network failure | `Result.retry()` → WorkManager exponential backoff (1 min initially) |
| Under Doze | `setExpedited` runs it without waiting for a maintenance window (mitigation, not a fix — §3.5) |
| Server 5xx / 408 / 429 | `Result.retry()` |
| 401 / 403 | `Result.failure()`. Re-pairing required |
| Other 4xx | Discard that record after 10 attempts |
| Long outage | Queue capped at 500, oldest trimmed first |
| Service killed | The hourly `WatchdogWorker` notices and restarts it |
| Health-check alarm lost | `WatchdogWorker` re-arms it with the remaining time (fires immediately if already due) |
| Reboot / app update | `BootReceiver` resumes automatically (if the FGS start is refused, the watchdog picks it up) |
| **The line came back** | **A network callback receives `onAvailable`, discards the backoff and retries immediately (Issue #15)** |

#### Discarding the backoff when the line returns (Issue #15)

**Exponential backoff does not know the connection came back.** During a long
outage `UploadWorker`'s wait doubles up to WorkManager's five-hour ceiling, and
that timer is not cleared when the line returns — so the watch stays stopped
for the remainder.

Observed on a real device on 2026-09-07:

| Measured at | Received at | Delay |
|---|---|---|
| 16:19:55 | 16:19:57 | 0 min (normal) |
| 16:25:57 | 20:51:54 | 266 min |
| 16:51:32 | 01:08:16 next day | **497 min** |

The connection had been restored at 19:51. **The hourly `WatchdogWorker` was
running and was not the safety net it looked like**, because
`UploadScheduler.enqueueNow` used `ExistingWorkPolicy.KEEP` and returned
without touching the waiting work — 203 milliseconds and a success, in the
device log.

The fix has two parts.

1. `LocationTrackingService` registers a `NetworkRequest` for INTERNET
   capability via `registerNetworkCallback` and calls
   `UploadScheduler.retryNow` on `onAvailable`. **This is the immediate path.**
   **Not `registerDefaultNetworkCallback`.** Under a VPN the app's default
   network *is* the VPN; when Wi-Fi returns the default does not change and
   `onAvailable` never fires again (confirmed on hardware 2026-09-19, §7.3).
2. `WatchdogWorker` switches from `enqueueNow` to `retryNow` as well. It
   catches whatever the first path misses and bounds the silence to one hour.

`retryNow` picks between `REPLACE` and `KEEP` based on the existing work's
state. The decision is factored into `UploadScheduler.shouldResetBackoff` and
pinned by unit tests — discard only when `ENQUEUED` with an attempt count above
zero. **Never touch `RUNNING`:** the worker keeps reading until the queue is
empty, so replacing it would cut every drain short.

Note that `onAvailable` does not promise the device can reach anything. On that
phone a VPN with nothing underneath it stayed `VALIDATED` while DNS failed in
11 milliseconds (`Active default network: none`, `UnderlyingNetworks: []`).
**A satisfied `NetworkType.CONNECTED` must not be read as reachability.** When
the attempt fails it returns to backoff, and part 2 picks it up next time.

### 3.4 UI (adapted for older users)

- Body text 20sp, titles 24–30sp, primary buttons at least 64dp tall
- Flow: **consent → pairing → permissions (four steps) → status screen**
- Permissions are not requested all at once; each is explained on its own
  before being asked for
- Android 11+ does not offer "always allow" in the dialog, so there is a branch
  that directs the user to the settings screen
- The status screen shows only last-sent time, unsent count and device name.
  Stopping and unpairing both go through a confirmation dialog
- When the configuration is broken, a warning and an "open settings" button
  appear at the top of the status screen (§3.5)

### 3.5 Device configuration health (Issue #4)

Even once the permission screens have been passed, these can be lost at any
time through a settings change or an OEM power-saving feature. All three
caused real damage on the device:

| What is lost | What happens |
|---|---|
| Exemption from battery optimisation | Doze defers the job and **sends arrived up to 74 minutes late** (filed as 27 minutes, revised after the 2026-08-28 drive) |
| Notifications | The ongoing notification never appears, so the parent cannot tell whether it is running |
| "Always allow" location | Positioning stops while the screen is off |

**The real problem was that nobody could tell it had broken.** It only shows on
the parent's screen, and the parent is not looking. Addressed in three places:

1. `DeviceHealth.read()` reads all three together, and the permission screen
   and status screen share that judgement — so there is no state where one says
   "granted" while notifications do not appear
2. The status screen shows a warning and links straight to the relevant
   settings screen
3. **It is sent to the server alongside the position and shown on the child's
   dashboard too** (§6.1)

For notifications, `POST_NOTIFICATIONS` alone is not enough: the permission can
be granted while the app, or the individual channel, is switched off in
settings. In practice that is what gets switched off, so
`areNotificationsEnabled()` and the channel importance are both checked.

The delay itself is also softened with
`setExpedited(RUN_AS_NON_EXPEDITED_WORK_REQUEST)`, but **that is a mitigation,
not a substitute for the exemption.** Once the quota runs out it is queued as
ordinary work.

### 3.6 Recovery immediately after reboot (Issue #3)

**Symptom.** Reboot the phone, go out without unlocking the screen, and not one
position is recorded for the whole trip. In production the process started one
minute after boot and the first point arrived 35 minutes later, after the run
had finished. The process simply did not exist in between.

**Cause.** `ACTION_BOOT_COMPLETED` is not delivered until the user unlocks for
the first time, because it waits for credential-encrypted storage to become
available. **Nothing was misconfigured and nothing was wrong in the code** —
this is Android behaving as specified. From the parent's point of view it is
still "I restarted my phone and the watch silently stopped."

**Fix.** Receive `LOCKED_BOOT_COMPLETED`, which requires putting the state
needed at startup somewhere readable before unlock.

| | Where it lives | Readable |
|---|---|---|
| Consent, watch enabled/disabled, device_id, last queued position | Device-protected storage | Always |
| Queued positions (Room) | Device-protected storage | Always |
| `device_token` | Credential-encrypted storage | Only after unlock |

**`device_token` is deliberately not moved.** Before unlock the app positions
and queues but does not send. The token needed for sending stays under
credential protection, so Direct Boot support does not increase secret
exposure. The 35 minutes of positions are kept with their `recordedAt` and all
arrive once the phone is unlocked. **History is not lost; the "current
position" stays stale** until then — an accepted trade.

The environment itself differs before unlock, so the paths are switched:

| | Before unlock | After unlock |
|---|---|---|
| Positioning | `LocationManager` (GPS first) | `FusedLocationProviderClient` |
| Sending | No | `UploadWorker` |
| WorkManager | Unavailable (its DB is credential-side) | Used |
| Notification channel | May be impossible on some devices | Available |

**`FusedLocationProviderClient` cannot be used**, because Google Play services
is not running during Direct Boot. The `network` provider is implemented in
Play services too, so GPS is the only thing that can be relied on. The bug
surfaced while running outdoors, which happens to suit that.

**Retreat if the ongoing notification cannot be shown.** Whether a notification
channel can be created before unlock may vary by device. A foreground service
that sits there without one gets killed by the OS, so the exception is caught,
`stopSelf()` is called, and `WatchdogWorker` takes over. Behaviour in that case
matches the pre-fix state — stopped until unlock — so nothing is made worse.

**DataStore's location does not follow the Context.**
`Context.preferencesDataStoreFile` is implemented as
`File(this.applicationContext.filesDir, ...)`, which **discards a
device-protected Context and reverts to credential-encrypted storage.** This
was hit on a real device: the settings stayed entirely in the old location
while appearing to have been migrated. There is a separate
`Context.deviceProtectedDataStoreFile` for this, and that is what is used. Room
uses the Context it is given, so the trap exists only on the DataStore side.

**Carrying data over on update.** Older versions kept everything in
credential-encrypted storage. `StorageMigration` moves the settings and the
outbox on the first launch after unlock. **Without it, updating the app would
destroy the pairing on the parent's phone** — requiring a new invite code to be
issued and typed in. The old files are not deleted until the move completes, so
a failure part-way can be retried on the next launch.

### 3.7 Where each fix came from (Issue #13)

**Symptom.** Two hours of positions were recorded at a place 7.4 km from home
that was never visited. The actual movement that day was in the opposite
direction.

**Probable cause.** `PRIORITY_BALANCED_POWER_ACCURACY`
[per Google's documentation](https://developer.android.com/develop/sensors-and-location/location/battery)
"will rarely use GPS" and relies mainly on Wi-Fi and cell information. What a
Wi-Fi fix returns is **the position at which an access point is registered in a
database**, not a measurement. When the access point itself moves — Wi-Fi on a
train — the answer is wildly wrong.

**Accuracy cannot separate them.** All ten bad points claimed 100 m or 300 m
while being 7 km wrong. Testing every threshold against all 233 records showed
that **removing the errors requires dropping below 80 m, which loses 74% of the
points recorded while out.** Indoors, underground and in a car only coarse
fixes are available, so "arrived at the hospital" disappears entirely.

So **the origin itself is recorded.**

| Stored | Meaning |
|---|---|
| `source_kind` | `satellite` / `network` / `unknown` |
| `source_provider` | The `getProvider()` value, which may be no more than `fused` |
| `source_has_altitude` / `_has_speed` / `_has_bearing` | The raw values the classification rested on |

**This classification turned out to be wrong on hardware (Issue #16).** It was
built on the premise that satellite fixes carry altitude and Wi-Fi/cell fixes
cannot produce one, but the device's network provider declares
`supports=[altitude]` and returns altitude, vertical accuracy and MSL altitude
alike. Speed and bearing were present on an 82 m fix taken while stationary. Of
1,770 records only four were classified `network`, and the point that was 33 km
wrong came out as `satellite`. **Because the raw values behind the
classification were stored, the breakage could be confirmed without changing
it.** The basis for display has moved to the dashboard as described in §6.1;
these columns stay but are no longer the deciding factor.

`NULL` means "not reported", not "it was a network fix" — it applies to the iOS
app and to builds predating the column (same principle as §5.2).

**Recording the origin does not reduce the errors**, so the priority was
switched to `PRIORITY_HIGH_ACCURACY`. Left on BALANCED, only Wi-Fi and cell
towers are consulted and the same mistake keeps happening.

**Battery is held down by the interval.** Positioning is not continuous:
every 5 minutes, and nothing is delivered unless the device moves 50 m, so GNSS
does not run back to back. The rule of thumb that "HIGH_ACCURACY burns the
battery" assumes a one-second interval and does not apply here. **It does cost
something, so the pre-switch measurement is kept as a baseline** (§7.3).

**Errors still remain.** Where GNSS does not reach, all the device can know is
Wi-Fi and cell information; if that is wrong, the device cannot tell either.
Communicating that such a fix is unreliable is the dashboard's job, and that
judgement is in §6.1.

**The effect of the switch is visible in the numbers.** The proportion of
points whose accuracy is reported as exactly 100 / 200 / 300 / 400 / 500 —
those §6.1 treats as database-derived — was **40–47%** from 8/25 to 8/30 under
BALANCED, and **4–17%** from HIGH_ACCURACY onward.

### 3.8 Privacy and security (CLAUDE.md §5)

- TLS required. Anything other than `https` is permitted in debug builds only
  (restricted by `network_security_config` to localhost / 10.0.2.2 / 192.168.x)
- HTTP logging is debug-only, since the bodies contain location data
- All domains are excluded from cloud backup and device-to-device transfer.
  **The device-protected side (`device_*`) is excluded explicitly too** (3.6),
  because device transfer is not stopped by `allowBackup="false"`
- `device_token` alone is kept out of device-protected storage and left under
  credential protection (3.6)
- "Disconnect from family" erases the pairing information and the unsent queue
  from the device
- The consent screen is mandatory on first launch; the watch cannot start
  without it

### 3.9 Toolchain

The development machine only has JDK 25 / 26, which the originally configured
Gradle 8.9 cannot run on, so everything was moved to current stable. No code
had been written yet, so the migration cost was zero.

| | Before | After |
|---|---|---|
| Gradle | 8.9 (wrapper not generated) | **9.7.1** |
| AGP | 8.7.3 | **9.3.1** |
| Kotlin | 2.0.21 | **2.3.21** |
| KSP | 2.0.21-1.0.28 | **2.3.11** |
| compileSdk / targetSdk | 35 | **37** |
| minSdk | 26 | 26 (unchanged) |

Main libraries: Compose BOM 2026.08.00 / core-ktx 1.19.0 / lifecycle 2.11.0 /
activity-compose 1.13.0 / play-services-location 21.4.0 / Room 2.8.4 /
WorkManager 2.11.2 / DataStore 1.2.1 / OkHttp 5.5.0 / kotlinx-serialization
1.11.0

**Things the migration turned up**

1. **AGP 9 has Kotlin support built in.** Applying
   `org.jetbrains.kotlin.android` breaks the build, so it was removed.
2. **Kotlin is pinned to 2.3.21.** 2.4.10 is current, but the combination with
   KSP 2.3.11 was preferred. Lint warns about the available update; that is
   deliberate.
3. **fragment was raised to 1.9.0 explicitly**, because `play-services-base`
   pulls in 1.1.0 and `lintVitalRelease` reports it as fatal. The app itself
   uses no fragments.
4. **Extra Android SDK components are required**: `platforms;android-37.0`,
   `build-tools;37.0.0` and others

---

## 4. iPhone app (parent side)

Bundle ID: `com.damburisoft.chikaku.watch` — the same as Android; different
platforms do not collide.
Display name: **みまもり** / version `0.1.0` / minimum iOS 17.0 (iPhone XS and
later)

**No changes were needed on the server or the dashboard.** The parent-device
contract (`POST /api/v1/devices/register` and `POST /api/v1/location`) was
already platform-independent, so only the iOS side had to meet it. Even the
convention that `battery_level` is -1 when unavailable lines up with
`UIDevice.batteryLevel`, which reports -1.0 when unknown.

Swift 6 / SwiftUI / SwiftData. **No external dependencies** — neither
CocoaPods nor SPM. Usage and build steps are in
[`ios-app/README.en.md`](../ios-app/README.en.md).

### 4.1 Layout

```
ios-app/Chikaku/
├── ChikakuApp.swift        @main. The AppDelegate receives location-triggered launches
├── Graph.swift             Minimal service locator (no DI library)
├── Config.swift            Settings via xcconfig → Info.plist
├── Strings.swift           Copy. One-to-one with Android's strings.xml
├── Log.swift               os.Logger. Never emits the location itself
├── Data/
│   ├── SettingsStore.swift   UserDefaults + Keychain
│   ├── Keychain.swift        For device_token only
│   ├── PendingLocation.swift SwiftData entity
│   ├── LocationQueue.swift   Operations on the outbox
│   ├── ApiClient.swift       URLSession. ApiResult distinguishes failure reasons by type
│   ├── ApiModels.swift       Request/response DTOs
│   └── Timestamp.swift       RFC 3339 formatting
├── Location/
│   ├── LocationTuning.swift     Where the positioning parameters and thresholds live
│   ├── LocationRepository.swift Decides "should this be sent" and queues it
│   └── LocationTracker.swift    CLLocationManager (SLC + standard updates)
├── Upload/
│   ├── Uploader.swift               Drains the queue
│   └── BackgroundTaskScheduler.swift Registration with BGTaskScheduler
└── UI/
    ├── RootView.swift        Screen routing
    ├── DisclosureView.swift  Prominent disclosure (consent screen)
    ├── PairingView.swift     Invite code entry
    ├── PermissionView.swift  Permissions requested in stages
    ├── StatusView.swift      The steady-state screen
    ├── AppModel.swift
    ├── Common.swift
    └── Theme.swift
```

The Xcode project uses **file system synchronized groups** (Xcode 16+), so
adding a file under `Chikaku/` puts it in the target without touching the
pbxproj.

### 4.2 How it differs from Android

**Parts of this are a redesign rather than a port.** iOS has no one-to-one
equivalent of Android's "foreground service plus WorkManager".

| Android | iOS | The difference |
|---|---|---|
| `setMinUpdateDistanceMeters(50m)` | `distanceFilter = 50` | Roughly equivalent |
| `setIntervalMillis` / `setMaxUpdateDelayMillis` | **Nothing** | iOS has no concept of an update interval |
| Foreground service (ongoing notification) | Background Modes + `allowsBackgroundLocationUpdates` | **No ongoing notification.** Nothing can be pinned on screen to say it is running |
| `BootReceiver` / `WatchdogWorker` | Significant Location Change | The OS wakes the app after termination or reboot |
| WorkManager (exponential backoff) | Drain on each location update + `BGTaskScheduler` | **iOS guarantees no execution time** |
| Room | SwiftData | Excluded from backup (including `-wal` / `-shm`) |
| DataStore | UserDefaults + Keychain | Only the token goes in the Keychain |
| OkHttp | URLSession | No added dependency |
| Four permission steps | While-using → always → precise → notifications | Renumbered to match only the steps actually shown |

### 4.3 Battery strategy (per CLAUDE.md §2.2)

Power saving comes from **which positioning service is used**, not from
intervals. The reasoning is in ADR-6.

**Stage 1: suppress the updates at the OS level**

| Parameter | Value | Intent |
|---|---|---|
| `distanceFilter` | 50 m | **The core of it.** Without movement, no callback happens at all |
| `desiredAccuracy` | `kCLLocationAccuracyHundredMeters` | Avoid GPS alone; use Wi-Fi and cell positioning |
| Significant Location Change | Runs alongside | Near-negligible drain, and wakes the app after termination or reboot |
| `pausesLocationUpdatesAutomatically` | **false** | With true, resumption is not guaranteed and the watch quietly develops a gap |

**Stage 2: thin the sends in the app** (`LocationRepository`)

Same thresholds as Android: 50 m, a 30-minute health check, discard beyond
500 m accuracy. **iOS has one more**: discard fixes measured more than five
minutes ago. CoreLocation can return a stale cached position immediately after
launch, and sending that as "where they are now" shows the child a lie.

**The health check is weaker than on Android.** The only thing that can support
a 30-minute send while stationary is `BGAppRefreshTask`, and the OS decides
when it runs, so the interval is not honoured. From the child's side this reads
as "liveness confirmation from a parent on an iPhone is coarser while
stationary", surfacing as how often the freshness chip says "slightly old"
(§6.1).

### 4.4 Privacy and security (CLAUDE.md §5)

- TLS required. Anything other than `https` is permitted in debug builds only
  (decided in `resolve()`)
- ATS uses `NSAllowsLocalNetworking` only. `NSAllowsArbitraryLoads` is not used
- `device_token` lives in the Keychain as
  `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`
  - **It must not be `WhenUnlocked`.** Location-triggered background launches
    happen while the screen is locked. With `WhenUnlocked`, every send after a
    reboot fails until the parent opens the phone once — and the cause is
    invisible
  - `ThisDeviceOnly`: the token is not copied by a backup restore or a device
    transfer
- The outbox is excluded from iCloud backup
- The location itself is never written to the log
- "Disconnect from family" erases the pairing information and the unsent queue
  from the device

### 4.5 Distribution constraint (unresolved)

**The Apple Developer Program membership has not been renewed this year.** As
things stand the app cannot be installed on a parent's iPhone and left running.

| | Free (Personal Team) | Requires payment |
|---|---|---|
| Building, testing on one's own device | ○ | |
| Installing on a parent's iPhone and leaving it running | | ● |

Free provisioning profiles expire after seven days, so they cannot be used for
a parent's device far away. After paying, there are three distribution routes
with different reinstallation burdens.

| Method | Validity | Effort for the parent |
|---|---|---|
| TestFlight | Build expires after 90 days | Reinstall every three months |
| Ad Hoc | One year (tied to membership renewal) | Once a year; UDID must be registered in advance |
| Unlisted App Distribution | Indefinite | Install from a link, then automatic updates |

The third is easiest for long-term operation, but this is a deployment-stage
decision and has not been made.

### 4.6 Why there is no Apple Watch app

**It does not work on watchOS.** Three technical walls, none with a workaround.

- **There is no background positioning API.** watchOS supports neither
  `startMonitoringSignificantLocationChanges` nor region monitoring. Continuing
  to position requires something like `CLBackgroundActivitySession`, and
  `startUpdatingLocation()` can only be started in the foreground — meaning
  **once the app is closed it cannot resume.**
- **The battery cannot take it.** Continuous positioning is not viable on a
  device rated for about 18 hours of normal use. It collides head-on with this
  system's battery-first design.
- **Many models cannot communicate independently.** Without the cellular
  version, nothing can be sent while the iPhone is out of range or away.

The realistic role for watchOS is not as the tracker but as **a viewer on the
child's side**, showing watch status in a complication. And if the parent has
an Apple Watch, Apple's own Find My location sharing with Family Sharing covers
most of the purpose anyway.

---

## 5. Server (Cloudflare Workers)

Rust built to wasm and run on Workers. Routing is workers-rs's dispatch, not
axum. **Ported from an original Axum + SQLite implementation** (ADR-4/5).

### 5.1 Endpoints

**Parent devices (bypass Cloudflare Access)**

| Method | Path | Protection |
|---|---|---|
| POST | `/api/v1/devices/register` | Invite code (single use) |
| POST | `/api/v1/location` | `Bearer device_token` |
| GET | `/api/v1/healthz` | None |

**Child accounts (behind Access)**

| Method | Path |
|---|---|
| GET | `/api/v1/me` |
| GET | `/api/v1/families/{id}/latest` |
| GET | `/api/v1/families/{id}/history` |
| POST | `/api/v1/families/{id}/invites` |
| POST | `/api/v1/families/{id}/devices/{id}/revoke` |

`invites` and `revoke` are not in CLAUDE.md §3.2 but were added. Without the
first there is no route by which a device can be registered at all; without the
second, a lost device cannot be cut off.

### 5.2 Data

Tables: `families`, `children_accounts`, `parent_devices`, `invite_codes`,
`location_events`.

**Times are stored as UTC epoch milliseconds (INTEGER).** SQLite TEXT datetimes
compare incorrectly when the formatting varies, which would silently break
range queries over history. They are converted to RFC 3339 in JSON.

Location history is deleted after 90 days by a Cron Trigger running every six
hours, which also clears invite codes that expired unused.

Device configuration health lives on `parent_devices` and is overwritten on
every send; no history is kept. **NULL means "no report received yet", not "no
problem."** The iOS app and builds predating the field do not send it, so it is
kept as a third state rather than filled with 0/1 — filling it would put a
warning on devices that merely cannot report.

**`location_events` carries the origin of each fix (Issue #13, migration
0003):** `source_kind`, `source_provider`, `source_has_altitude`, `_has_speed`,
`_has_bearing`. The raw values behind the classification are stored as well as
the classification itself, because the classification is a heuristic and should
be re-derivable once there is real device data. `NULL` means "not reported",
not "it was a network fix" (§3.7).

### 5.3 Authentication

The paths are entirely separate by subject. Details in
[authentication.en.md](authentication.en.md).

| | Parent device | Child account |
|---|---|---|
| Credential | `device_token` (32 random bytes) | Cloudflare Access JWT |
| Storage | **SHA-256 only**; the original is never kept | **Not stored** |
| Expiry | None (revoked explicitly) | Access session settings |

**No password is stored anywhere.** Handing this to Access removed rate
limiting, password resets and session management from the problem.

A Worker that also serves Static Assets is not given the Access context, so the
Worker verifies the JWT's signature, `aud`, `iss`, `exp` and `kid` itself.
`alg` is fixed to RS256 rather than trusting the token's own claim, which
closes the `alg=none` substitution.

### 5.4 Constraints hit during the port

D1 and wasm specifics. Hard to diagnose from the symptom, so recorded here.

| Constraint | Handling |
|---|---|
| **D1 rejects JavaScript BigInt** | Bind `i64` as `f64` (`db::num()`). Epoch milliseconds are nowhere near 2^53 |
| D1 rows cannot be tuples | Receive them in a `#[derive(Deserialize)]` struct |
| **`batch` only rolls back on a statement failure**, not on zero rows updated | Invite redemption uses a conditional INSERT plus an UPDATE guarded by EXISTS, and counts as successful only when both affect one row |
| `getrandom` does not work on wasm | `wasm_js` feature plus the cfg in `.cargo/config.toml` |
| `serde_wasm_bindgen` produces a JS Map | The JWK handed to SubtleCrypto is built with `JSON.parse` to get a plain object |
| wrangler 4 needs Node 22+ | Pinned in `.tool-versions` |

### 5.5 Rate limiting

Against brute-forcing invite codes. **WAF Rate Limiting Rules are unavailable**
— they are a zone-level feature, and this system has no custom domain and runs
on a workers.dev hostname (§8.2). The Worker's built-in `ratelimit` binding is
used instead.

| Route | Key | Limit | Why that key |
|---|---|---|---|
| `POST /api/v1/devices/register` | Source IP | 5 / 60 s | Pre-authentication, so the IP is the only handle available |
| `POST /api/v1/families/{id}/invites` | Child account ID | 10 / 60 s | Authenticated. Several people in one family do not throttle each other |

`POST /api/v1/location` is **deliberately not limited.** A device returning
from a long outage drains its whole queue at once, and throttling that would
restrict correct behaviour. That route is protected by a 32-byte
`device_token`, against which brute force does not apply.

**The limit is not absolute.** The counter is held per Cloudflare location, so
spreading connections exceeds the configured value. Measured: eight attempts
with a fresh `curl` process each time all passed (each connection landed on a
different machine), while forty attempts over a reused connection stopped at
five. **This slows single-connection brute force; it is not a cap on the number
of attempts.** The real defence for invite codes remains the short lifetime
(24 hours) and the 27^8 space; this is a layer on top.

**A missing binding fails with a 500.** Letting it pass would leave only the
assumption that rate limiting exists while actually being defenceless (the same
principle as `config::required`). That decision is why the e2e configuration in
`wrangler.test.jsonc` also has to declare the binding, with only the limits
loosened.

Exceeding it returns **429**. Android treats 429 as a temporary failure and
retries (the 408/429 branch in `ApiClient.execute`), so no app change is
needed.

---

## 6. Dashboard (child side)

A Vite + React + TypeScript SPA. No Next.js (ADR-1). The map is Leaflet +
OpenStreetMap. **There is no login screen** — Access has already handled it.

### 6.1 How the screen is conceived

The first thing a watching family member wants is not coordinates but
**how fresh the information is.** So the device card shows relative time
("14 minutes ago") in the largest type, with freshness as a coloured chip
(current / slightly old / old / nothing received).

**The time a position was measured and the time it reached the server are kept
distinct.** More than five minutes apart adds "may have been out of signal."
For the person watching, that gap is itself the information — "we were out of
contact then" — so it is not hidden.

**If the parent's device settings are broken, the reasons are listed on the
device card.** Shown only on the parent's screen, the parent does not notice
and the watch degrades quietly (§3.5). They are ordered by impact: positions
stop arriving, then merely losing the ability to notice. Nothing is shown for a
device whose `health` is `null` — presenting "unknown" as "a problem" leaves a
warning nobody can clear.

The map overlays the positioning error as a circle. A bare dot overstates the
precision.

**A position already on screen is not removed when the connection drops.**
Removing it would look like the watch has failed, when in fact slightly older
information is still in hand; a banner says so instead.

**Unreliable fixes are distinguished rather than deleted (Issue #13).**
Estimates from Wi-Fi or cell towers can claim 100 m while being kilometres out.

| | |
|---|---|
| Accuracy circle on the current position | Grey and dashed |
| Map tooltip | Adds "approximate position" |
| Movement path | That segment alone is grey and dashed |
| Device card | States "this is an approximate position" and why |

**They are not deleted because deleting them deletes "was at the hospital"
too.** Places where only coarse fixes are available are common, and discarding
them adds silence. The judgement lives in one place,
`client/src/lib/trust.ts`, so the map and the card cannot disagree.

**What counts as unreliable (Issue #16).** The device's own report (§3.7's
`source_kind`) turned out not to be dependable, so the judgement rests on two
signals that were in the data itself. All the figures were checked against
1,770 production records.

| Signal | Evidence |
|---|---|
| Accuracy reported as exactly **100 / 200 / 300 / 400 / 500** | When returning a Wi-Fi or cell database position, the device reports accuracy as a discrete value. All ten known-bad points have it. Of 512 fixes carrying a speed, two do. All eleven pairs delivered within one second of each other run discrete-then-real (network → GPS) |
| **Neighbours within 1 km of each other, but this point 2 km or more from both** | The point that claimed 56.1 m while being 33 km wrong is not catchable by the rule above. Across 1,770 records the condition fires on that one point. "Speed since the previous point" does not catch it — entering, it works out at 127 km/h, which a train would produce |
| Self-reported `network` / `unknown` | Only four of them, and those four were in the right place, but there is no reason to ignore them |

284 records (16%) are flagged. Of those, only 14 are more than 2 km from a GPS
fix; the rest are things like the 100–300 m points recorded indoors when GPS
cannot get through, and the place is right. **So drawing them dashed as
"approximate" is enough — they need not be discarded.**

**This is not an attempt to identify network fixes.** The 22 m point most often
recorded while stationary is in fact a network-provider output, and the
location is correct. The aim is to avoid drawing untrustworthy points as solid
lines, and nothing more.

The spike rule needs both neighbours, so it applies only to history. The latest
position cannot be doubted on those grounds until the next point arrives.

### 6.2 Implementation decisions

- **No react-leaflet.** That removes an intermediate library that has to track
  React versions, and keeps the upgrade timing under our control. The default
  marker images break under the bundler's URL handling, so they are drawn with
  `DivIcon` instead.
- **Polling pauses while the tab is hidden.** The device only sends when the
  position changes, so asking more often than every 30 seconds produces nothing
  new.
- **Leaflet does not notice its container being resized.** `invalidateSize()`
  is called from a `ResizeObserver` (this was found while checking the page in
  a browser).

### 6.3 Layout

| | |
|---|---|
| `src/api/` | Types matching the server's JSON, and a fetch wrapper |
| `src/hooks/usePolling.ts` | Refetch on an interval; pauses while the tab is hidden |
| `src/components/MapView.tsx` | Works with Leaflet directly |
| `src/lib/format.ts` | Relative time, freshness, battery and accuracy display |
| `src/lib/trust.ts` | Whether a fix can be taken at face value (§6.1) |

`src/api/types.ts` pairs with the Serialize structs in
`server/src/routes/*.rs`. **Change the server and this changes too.**

---

## 7. Verification status

### 7.1 What has been confirmed

| Target | Detail |
|---|---|
| Android | `assembleDebug` / `assembleRelease` both succeed (15 MB / 1.8 MB, through R8 and lintVitalRelease); `lintDebug` reports 0 errors and 5 warnings, all known and deliberate |
| Android unit tests | **5** (pinning the `LocationRepository.shouldSend` decision) |
| iOS | Builds for `iphonesimulator` Debug and `iphoneos` Release (736 KB), zero warnings with Swift 6 strict concurrency on |
| iOS ↔ server | **12 contract checks** that drive the app's own `ApiClient` against a local Worker (`npm run test:ios`) |
| Server | **9** unit tests, **30** e2e, clippy clean under `-D warnings` |
| Client | `tsc -b` (strict) and `vite build` succeed |
| Dashboard UI | Rendering and interaction checked in a headless browser (selecting a device → showing the path, issuing an invite code, switching to a narrow viewport) |
| iOS UI | All four screens rendered in the simulator (consent → pairing → permissions → status) |

**The e2e tests do not bypass Cloudflare Access verification.** A local server
publishes a JWKS using a test RSA key and the Worker is pointed at it.
Signature verification and the `aud`, `iss`, `exp` and `kid` checks go through
the same code as production, and tampered signatures, **claim-only
substitution**, expiry and service-token rejection are all exercised.

The status codes Android depends on are pinned by e2e as well.
**Changing the server's codes changes the app's retry logic.**

| Code | Situation | App behaviour |
|---|---|---|
| 202 | Position accepted | Remove from the queue |
| 400 | Invalid time, coordinates or battery level | Stop retrying and discard |
| 401 | Token revoked / bad invite code / impersonation / bad JWT | Re-pair |
| 403 | Passed Access but the email is not registered | — |
| 404 | Referred to another family's ID | — |
| 429 | Rate limited (§5.5) | Retry later |
| 5xx | Server-side problem | Retry later |

**The iOS side checks the same table mechanically.** `npm run test:ios` starts
a local Worker, issues an invite code, and compiles the app's own sources —
`Chikaku/Data/ApiClient.swift` among them — to drive it. **There is no test-only
copy**, so changing the app's networking layer necessarily goes through this.

| Checked | Expected |
|---|---|
| Registering with an invite code / reusing a spent code | Success / `unauthorized` |
| A valid position / resending the same fix | Both `success` (the server folds the duplicate) |
| Invalid latitude / out-of-range battery level | `clientError` 400 (discard without retrying) |
| Battery level `-1` (unavailable) | Accepted |
| Wrong token / another device's `device_id` | `unauthorized` |
| RFC 3339 formatting | UTC with milliseconds, matching Android's `ISO_INSTANT` |

Rows are confirmed to actually land in D1 (one registration, two positions, the
duplicate folded).

### 7.2 Confirmed in production

Checked against `https://chikaku.<subdomain>.workers.dev` after the 2026-08-24
deployment.

**How Access behaves per route** — the only place where a bypass that is too
wide or too narrow becomes visible:

| Request | Result | Meaning |
|---|---|---|
| `GET /` | 302 → Access login | The dashboard is protected |
| `GET /api/v1/me` | 302 → Access login | Likewise |
| `GET /api/v1/healthz` | 200 | The bypass works |
| `POST /api/v1/location` (no token) | 401 from the Worker | Past the bypass, the Worker refuses |
| `POST /api/v1/devices/register` (bad code) | 401 `invalid_invite_code` | Likewise |
| A forged `Cf-Access-Jwt-Assertion` | 302 (never reaches the Worker) | Access does not let the header be trusted |

**End to end** (invite code → device registration → position upload → display):

| Check | Result |
|---|---|
| Invite code typed as `k7qm-4xdf`, lowercase with a hyphen | Normalised and accepted |
| Position upload | 202 `{"stored":true}` |
| Resending the same fix | 202 `{"stored":false}` — deduplication works |
| Reusing a spent invite code | 401 — single use is enforced |
| Invalid latitude `999.0` | 400 |
| Browser sign-in and dashboard display | Works via one-time PIN |
| 40 consecutive device registrations on one connection | All 429 after the first five, as configured |

**End to end from a real device** (2026-08-25, AQUOS Sense 8 / SHARP SH-54D,
debug build)

The APK embeds `chikaku.serverBaseUrl` from `local.properties` at build time.
At the time there was no signing configuration and an unsigned APK will not
install, so a debug build was used (signing was set up later, §8.3).

| | First | Second |
|---|---|---|
| Measured at (JST) | 10:45:23 | 10:48:21 |
| Received (JST) | 10:48:23 | 10:48:24 |
| Delay | 179 s | 2 s |
| Accuracy | 100 m | 19.7 m |

**The 179-second delay on the first is by design.** The fix taken right after
launch was written to the Room queue and drained by WorkManager at its next
opportunity. This is where storing the measurement time and the receive time
separately pays off (§6.1).

**Accuracy tightened from 100 m to 19.7 m.** `BALANCED_POWER_ACCURACY`
answered first from Wi-Fi/cell positioning, then GPS came in. On the map this
shows as the size of the accuracy circle.

**On-device check (2026-08-28, SH-54D / Android 16, debug build)**

Installed over a device running the old version, from when everything lived in
credential-encrypted storage.

| Check | Result |
|---|---|
| Pairing survives the update | Yes. `device_id` unchanged |
| Settings move to device-protected storage | Yes. `/data/user_de/0/…/files/datastore/chikaku_settings.preferences_pb` |
| `device_token` stays on the credential side | Yes. Only `chikaku_credentials.preferences_pb` on the CE side |
| Old files are removed | Yes |
| The outbox moves to device-protected storage | Yes. The CE `databases/` is empty |
| The health-check alarm is scheduled | Yes. `ELAPSED_WAKEUP` with the `HEARTBEAT` tag |
| The foreground service runs with the `location` type | Yes. `types=0x00000008` |

**The first install over the top failed to migrate.** Because
`Context.preferencesDataStoreFile` discards a device-protected Context and
reverts to the credential side, source and destination pointed at the same
file and DataStore threw "two instances for the same file". **It was being
swallowed, so from outside everything looked fine.** The table above is after
the fix. To stop it recurring, a source and destination that resolve to the
same file now skip the migration and log it (the decision is pinned by a unit
test).

**This device is not exempt from battery optimisation and notifications are
not permitted.** The conditions that trigger the Issue #4 warnings are present
in the real data.

**Observed after a reboot with the screen left locked** (same day, 13:25) —
exactly the conditions that reproduce Issue #3:

| Check | Result |
|---|---|
| Starts on `LOCKED_BOOT_COMPLETED` | Yes. Process start **3 seconds** after `boot_completed` |
| The foreground service comes up before unlock | Yes. FGS start allowed with `code:LOCKED_BOOT_COMPLETED` |
| **The ongoing notification can be shown before unlock** | Yes. `foregroundId=1001` / `types=0x00000008` |
| Settings are readable from device-protected storage | Yes. The health check was scheduled at `+29m54s`, which is unreachable without reading them |
| Positioning can be requested before unlock | Yes. Registered on `gps` and `fused` (`HIGH_ACCURACY, minUpdateDistance=50.0, @+2m`) |
| Nothing is sent before unlock | Yes. `UploadWorker` does not run and the server's `last_seen_at` does not move |
| The device is actually locked | Confirmed. `isKeyguardShowing=true`, and `run-as` cannot read CE |

Before the fix the process did not start until **2,217 seconds after the
reboot** — the moment the parent unlocked the screen. **It is now 3 seconds.**

```
13:25:46.495 Background started FGS: Allowed [... code:LOCKED_BOOT_COMPLETED ...]
13:25:46.567 gps provider +registration com.damburisoft.chikaku.watch
             -> Request[@+2m0s0ms HIGH_ACCURACY, minUpdateDistance=50.0]
```

**That does not mean a fix was actually obtained.** Observed indoors, so
`locations = 0` throughout. Whether GPS locks on has to be checked outdoors
(7.3).

**The `network` provider was not registered on from here.** It is implemented
in `com.android.location.fused` on the Play services side and was
`enabled=false` / `connected=false` at that point — unreliable, as expected.

**Observed on a drive there and back** (same day, 13:40–15:00, roughly 3.4 km
each way). The device settings — battery optimisation exemption and
notifications — were **still not fixed.**

| Measured | Received | Delay | From home | Gap from previous |
|---|---|---|---|---|
| 13:42:34 | 14:54:42 | 72 min | 0.32 km | 2 min |
| 13:44:36 | 14:54:42 | 70 min | 0.61 km | 2 min |
| 14:16:29 | 14:54:42 | 38 min | **3.42 km** | **31 min** |
| 14:49:20 | 14:54:42 | 5 min | 2.05 km | **32 min** |
| 14:51:22 | 14:59:30 | 8 min | 0.30 km | 2 min |

**Right after departure the interval is 2 minutes, so the movement trigger
itself works.** It drops to 30-minute intervals immediately afterwards because
the OS froze the app and the positioning callbacks stopped; the only thing left
running was the alarm that punches through Doze. **Not one position survives
from the moment of arrival at the destination.**

The send delay reached **74 minutes** — worse than the 27 minutes recorded when
§3.5 was filed. Same cause: no exemption from battery optimisation.

| Check | Result |
|---|---|
| **The health check fires at 30 minutes** | Yes. 31 min 53 s and 32 min 51 s (`setAndAllowWhileIdle` is imprecise, so that is reasonable) |
| **Device configuration health arrives end to end** | Yes. `bat_ok=0` / `notif_ok=0` / `bgloc_ok=1` recorded on `parent_devices` |
| 50 m movement triggers a send | Yes, while the app is not frozen |

**Without those 30-minute points, the 65 minutes from 13:44 to 14:49 would
have been complete silence.** Issue #2's purpose — letting the child tell
"stationary" apart from "something is wrong" — shows up here in real data.

**500 m fixes make the map jump.** 12:32, 12:53, 13:44 and 15:38 all share
**the same coordinates** with 500 m accuracy: a single cell-tower fix, landing
612 m from home. They scrape through because
`MAX_ACCEPTABLE_ACCURACY_METERS = 500` admits anything at or below 500. From
the child's side the parent jumps 600 m and comes back.

### 7.3 Not yet confirmed

- **The dashboard's trust judgement (§6.1) has only been checked by replaying
  historical data (Issue #16).** Running it over 1,770 production records
  caught all eleven known errors — the ten on 8/29 and the one on 9/3 — and
  produced no other spikes. **That shows the known errors can be caught, not
  that unknown ones will be.** The next time an error turns up, start by
  repeating the same replay. The replay simply applies the same functions used
  in `client/src/lib/trust.test.ts` to production data, which can be pulled
  with `wrangler d1 execute chikaku --remote --json`.

  The four points between 16:37 and 16:50 on 8/30 — 3.4–3.6 km from the
  nearest GPS fix — have not been confirmed either way.

- **Replacing what the app records has not been started (option B in #16).**
  The plan is to swap to `hasSpeedAccuracy`, `hasBearingAccuracy`, the list of
  keys in extras, and `elapsedRealtimeAgeMillis`. If `satellites` survives in
  GPS-derived fused output it would be decisive, but whether it does cannot be
  known without a device. The display is already corrected by §6.1, so this is
  not urgent.

- **The battery impact of `PRIORITY_HIGH_ACCURACY` has been measured. There is
  no regression (Issue #13).**

  | | BALANCED (before) | HIGH_ACCURACY (after) |
  |---|---|---|
  | Overall mean | 2.56 %/h (99.4 hours / 254%) | **2.21 %/h** (135.9 hours / 300%) |

  ```sql
  -- Consumption per discharge interval; charging breaks the interval
  SELECT datetime(recorded_at/1000,'unixepoch','+9 hours'), battery_level
  FROM location_events WHERE battery_level >= 0 ORDER BY recorded_at;
  ```

  **This includes screen use, so it is not the app's consumption alone.** The
  pre-switch baseline had been recorded as 2.50 %/h; rerunning the same
  calculation gave 2.56 %/h, because the window had grown. Either way it sits
  inside the "doubling is acceptable" band, so the Activity Recognition rework
  is not needed for now.

- **The fix for discarding the backoff was wrong once on hardware (Issue
  #15).** Two conditions were tried on 2026-09-19.

  | Condition | Result |
  |---|---|
  | Airplane mode + Wi-Fi off → restore | Sent **2 seconds** after recovery. But **this does not reproduce #15** |
  | Tailscale on + Wi-Fi off → Wi-Fi on | Backoff grew 1→2→4 min (reproduced). **But `onAvailable` was never called**, and the send waited for the scheduled retry four minutes later |

  In airplane mode `NetworkType.CONNECTED` is never satisfied, so
  `UploadWorker` never runs and no backoff accumulates; when the line returns,
  WorkManager starts it on its own. **That path worked before the fix.** #15
  occurs when WorkManager believes it is connected but nothing can get out,
  which a VPN with nothing underneath it produces.

  `registerDefaultNetworkCallback` stayed silent under the second condition
  because, with a VPN up, the app's default network *is* the VPN. Wi-Fi
  returning only swaps what is underneath; the default does not change. The fix
  was to switch to a `NetworkRequest` for INTERNET capability, but **that is
  unverified.** Procedure:

  1. Turn a VPN (Tailscale or similar) on and Wi-Fi off — confirm
     `Active default network: none` with the VPN `CONNECTED`
  2. Wait for the health check to queue one position (up to 30 minutes), then
     for `UploadWorker` to fail with `UnknownHostException` until
     `run_attempt_count` reaches 3 or more
  3. Confirm with `adb shell dumpsys jobscheduler` that the next run is minutes
     to tens of minutes away
  4. Turn Wi-Fi on
  5. **Within one minute**, "the line is back, retrying the upload" should
     appear and the position should reach the server

  ```
  adb logcat -G 16M    # this device keeps barely two minutes of ring buffer
  adb logcat -s TrackingService:V UploadWorker:V
  ```

  A send cannot be triggered with `am start-foreground-service`; the service is
  `exported=false`, which is correct and is not going to change.
- **Behaviour after the battery-optimisation exemption is unconfirmed.** The
  2026-08-28 drive (7.2) was measured **without** it, and only the 30-minute
  health checks got through while moving. **Whether the exemption produces a
  2-minute trace has not actually been measured.** If fixing it does not
  improve matters, the cause lies somewhere other than battery optimisation
- **Offline retry has not been exercised in the field.** No test has
  reproduced loss of signal
- **No battery measurement.** The design is power-efficient, but no figure
  from a real device
- **The release build has not been run on a device.** Signing is configured
  and signed APKs are produced (§8.3), but nothing has been installed yet.
  Behaviour under R8 obfuscation and shrinking is still to be checked
- **The Cron Trigger has not been seen running.** It is registered, but
  deletion of 90-day-old data is still some way off
- **Whether `setExpedited` actually shortens the delay is unverified.** On a
  device without the battery-optimisation exemption, the time to send has not
  been observed getting shorter. Only mitigation can be expected; it is not a
  substitute for the exemption. Whether it is being treated as expedited can be
  checked with `adb shell dumpsys jobscheduler | grep -A20 chikaku`
- **The configuration warnings have not been seen on a device.** The settings
  have not actually been switched off to confirm the warning appears on both
  the status screen and the dashboard. It is just switching them off and back,
  so it can be checked on the spot with a device to hand
- **Whether a fix can actually be obtained before unlock is unconfirmed (Issue
  #3).** The positioning request reaching `gps` has been confirmed on hardware
  (7.2), but **not one fix was obtained indoors** (`locations = 0`). The
  `network` provider is implemented in Play services and cannot be relied on,
  so GPS is all there is. Procedure:

  ```sh
  # With the watch running, reboot and walk a few hundred metres outdoors
  # without unlocking.
  adb reboot
  adb shell dumpsys location | grep -A2 'chikaku'   # does `locations` increase?
  # After unlocking, check the dashboard: if the points from the walk are
  # listed with their recordedAt, recovery worked (arriving late is fine)
  ```

- **How much battery positioning before unlock consumes is unmeasured.** In
  that state `LocationManager` is registered at a 2-minute, 50 m interval, so
  **it keeps trying even indoors where GPS cannot lock.** Normally this ends
  within minutes when the phone is unlocked, but it matters in a case like
  "rebooted and left on a table overnight". If necessary, the registration
  could be dropped after a period without a fix, leaving only the 30-minute
  health check
- **iOS has never been run on a device.** Background positioning, recovery via
  SLC and the actual `BGTaskScheduler` interval are none of them reproduced in
  the simulator. This depends on renewing the Apple Developer Program (§4.5)
- **Whether background positioning works under free provisioning is
  unconfirmed.** `UIBackgroundModes` is an Info.plist key rather than an
  entitlement, so it should, but one device build settles it and that is worth
  doing first

---

## 8. Development and deployment

### 8.1 Running it locally

**The procedure is collected in [getting-started.en.md](getting-started.en.md)**
(Japanese: [getting-started.md](getting-started.md)). Written twice, one copy
inevitably goes stale.

```sh
npm install
npm run dev        # → http://localhost:5173
```

That brings up the Worker, a local D1, and **a stand-in for Cloudflare
Access** together. Access is not present locally, but **building an escape
hatch into the Worker that disables verification risks it reaching
production**, so instead a genuine JWT that passes verification is supplied.

### 8.2 What was deployed (completed 2026-08-24)

**The actual values are not in the repository.** They are not harmful to
publish, but keeping the project reusable meant untracking `wrangler.jsonc`
entirely ([getting-started.en.md](getting-started.en.md)). Only the shape is
recorded here.

| | |
|---|---|
| Public URL | `https://chikaku.<subdomain>.workers.dev` |
| Cloudflare account | Account ID |
| D1 | `chikaku`, with the ID assigned by `wrangler d1 create` (APAC) |
| Zero Trust team domain | `https://<team>.cloudflareaccess.com` |
| Identity provider | One-time PIN only; no external IdP configured |
| Cron | `0 */6 * * *` |

**No custom domain.** Cloudflare Access accepts a `workers.dev` hostname
directly as a self-hosted application's domain, and there is no reason to
arrange a custom domain at MVP stage. Moving to a Custom Domain later means
updating the Access application's `destinations` and the Android side's
`chikaku.serverBaseUrl`.

**There are two Access applications.** A path-scoped application takes
precedence over one covering the whole host (Cloudflare applies the most
specific rule first), so between them exactly the three parent-device routes
are open and everything else is protected.

| Application | Covers | Policy |
|---|---|---|
| `chikaku-device-api` | `/api/v1/devices/register`, `/api/v1/location`, `/api/v1/healthz` | Bypass (Everyone) |
| `chikaku` | The whole host | Allow (child accounts' email addresses) |

The AUD tag written into `wrangler.jsonc` is the latter's. **Not the
former's** — the dashboard's JWT is issued by the latter application.

Creating an Access application needs Zero Trust API permissions, which the
`wrangler login` OAuth token does not include (only `workers`, `d1` and so
on). A separate API token with `Access: Apps and Policies (Edit)` was issued
and used with `POST /accounts/{id}/access/apps`. **That token is revoked once
the work is done.**

Redeployment is just `npm run deploy`, which includes building the client. No
placeholders remain in `wrangler.jsonc`.

---

### 8.3 Release signing (set up 2026-08-26)

The parent's device gets a release build. Debug builds log HTTP bodies —
which contain location data — and let `run-as` read the on-device data, which
is unsuitable for a phone in daily use.

The signing configuration is read from `local.properties`. **It signs only
when all four values are present, and produces an unsigned APK otherwise**, so
that R8 and lint verification can run in an environment without the signing
key rather than failing the build there.

```properties
chikaku.keystoreFile=/Users/YOUR_NAME/.keystores/chikaku/chikaku-release.jks
chikaku.keystorePassword=...
chikaku.keyAlias=chikaku
chikaku.keyPassword=...
```

**The key lives outside the repository.** `.gitignore` excludes `*.jks`, but
keeping it out of the working tree in the first place means there is no path
by which it could be committed accidentally.

| | |
|---|---|
| Location | `~/.keystores/chikaku/chikaku-release.jks` (`chmod 600`) |
| Type | PKCS12 / RSA 2048 / 10,000 days |
| Alias | `chikaku` |
| Certificate SHA-256 | `452c2d07d813dc84171d2dd9080140821a4fcd021fc921dff14ed056a0f3dd3c` |

Signing uses **v2 and v3 only**; v1 is disabled. At minSdk 26 the v1 JAR
signature is unnecessary and only adds `META-INF` entries to the APK.

```sh
./gradlew :app:assembleRelease
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
```

**Losing this key means the app can never be updated.** An APK with a
different signature cannot overwrite an existing install of the same
`applicationId`, so the parent would have to uninstall first — and re-pair.
Keep the key file and the passwords separately, and keep them safe.

---
## 9. Open work

### Deployment (next steps)

- **End-to-end check on an Android device.** `chikaku.serverBaseUrl` in
  `local.properties` already points at the deployment; start by issuing an
  invite code on the dashboard and pairing a real phone
- Measure battery consumption
- **Renew the Apple Developer Program.** The iPhone version is implemented,
  but without this it cannot be left running on a parent's device (§4.5). If
  too long has passed since expiry this becomes re-enrolment rather than
  renewal, with a review wait, so it affects the schedule early
- **End-to-end check on an iOS device, and choosing a distribution method**
  (TestFlight / Ad Hoc / Unlisted)

### Features

- **Rate limiting is a speed limit, not a cap** (§5.5). Spreading connections
  exceeds the configured value. The primary defence against brute force
  remains the short invite-code lifetime and the 27^8 space
- FCM Web Push (phase 2)
- Geofencing (phase 3)
- **Making it usable through LINE**
  ([#12](https://github.com/abekatsu/chikaku/issues/12), not being worked on).
  This matters once packaging is considered: it removes the need for the child
  to create an account, and notifications arrive reliably. **In exchange, the
  reach of the location data widens** — it stays in the chat and can be
  forwarded — so where to draw the line on what is shared has to be decided
  before implementation. Experimenting within the family comes first
- A management screen for families and child accounts. Today, the Access
  policy and `wrangler d1 execute` are kept in step by hand (the procedure is
  in "Operations" in `server/README.en.md`)
- An audit log — there is currently no way to trace who viewed a position and
  when
- Automated testing is mostly at the contract level. On Android only the send
  decision is pinned by unit tests (5 of them); positioning and the service
  lifecycle are not covered. On iOS, `npm run test:ios` sees only the
  networking layer
- **iOS does not report device configuration health.** The server permits the
  field to be absent so nothing breaks, but no warnings ever appear for an
  iPhone on the dashboard. Some items map onto iOS ("always allow",
  notifications) and some have no equivalent at all (battery-optimisation
  exemption), so the set of items needs rethinking
- **There is no iOS equivalent of the Issue #3 escape.** iOS cannot launch an
  app between a reboot and the first unlock either (Before First Unlock), and
  Significant Location Change does not arrive. There is no mechanism
  equivalent to Android's Direct Boot, so the same gap stays open. And since
  iOS does not report configuration health (above), there is no way for the
  child to see that recovery has not happened
- **The "watch has stopped" notification is not implemented on iOS.** The
  permission is obtained but nothing is sent. Permissions are easier to lose
  on iOS — the OS periodically prompts to change to "while using the app" — so
  it would matter more there than on Android

### Status of the open items in CLAUDE.md §8

| Item | Status |
|---|---|
| Front-end technology | **Decided**: Vite + React + TypeScript (ADR-1) |
| Hosting environment | **Decided**: Cloudflare Workers + D1 (ADR-2/4) |
| Binding a parent device to a family | **Decided**: invite code, 8 characters, single use, 24 hours |
| Location history retention | **Decided**: 90 days, deleted automatically by Cron |
