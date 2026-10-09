*[日本語版](README.md)*

# Mimamori — iPhone app (parent side)

Detects position changes on the parent's iPhone and sends them only when they
change. Runs on **the same server contract, the same screen flow and the same
thresholds** as [android-app](../android-app). Neither the server nor the
dashboard needed any change.

- Structural decisions → [../docs/architecture-decisions.en.md](../docs/architecture-decisions.en.md)
  (**ADR-6** for iOS)
- Implementation status → [../docs/implementation-status.en.md](../docs/implementation-status.en.md)
- The server contract → [../server/README.en.md](../server/README.en.md)

| | |
|---|---|
| Bundle ID | `com.damburisoft.chikaku.watch` |
| Display name | みまもり |
| Minimum iOS | 17.0 (iPhone XS and later) |
| Language | Swift 6 / SwiftUI / SwiftData |
| External dependencies | **None** — neither CocoaPods nor SPM |

---

## 1. What you need

```
Xcode 26 or later
```

Set the server address. `Config/Chikaku.xcconfig` is gitignored.

```sh
cp Config/Chikaku.xcconfig.example Config/Chikaku.xcconfig
$EDITOR Config/Chikaku.xcconfig
```

> **In xcconfig, everything after `//` is dropped as a line comment.**
> Writing `https://` directly leaves just the scheme and breaks silently, so
> the template inserts the slashes through a variable. Keep that shape.

The build succeeds without it. In that case the address can be entered at
runtime from "advanced settings (server URL)" in the app.

## 2. Building and running

```sh
# Simulator
xcodebuild -project Chikaku.xcodeproj -scheme Chikaku \
  -sdk iphonesimulator -configuration Debug build

# Device target (unsigned, for checking the build)
xcodebuild -project Chikaku.xcodeproj -scheme Chikaku \
  -sdk iphoneos -configuration Release \
  CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO build
```

To work in Xcode, open `Chikaku.xcodeproj` as-is. The project uses **file
system synchronized groups** (Xcode 16+), so adding a file under `Chikaku/`
puts it in the target automatically — no pbxproj editing.

### Running on a real device

**Background positioning can only be meaningfully checked on hardware.** The
simulator reproduces neither device movement nor battery level.

Free provisioning (Personal Team) can install on a device, but the profile
**expires after seven days.** Leaving the app on a parent's phone requires an
active Apple Developer Program membership.

## 3. Verifying the network contract

Changing the server's status codes changes what the app's retry logic means.
That correspondence is checked mechanically against a local Worker.

```sh
npm run test:ios     # from the repository root
```

It compiles the app's own sources — `Chikaku/Data/ApiClient.swift` among them
— and drives them directly, so **there is no test-only copy.** The UI is not
involved.

| Checked | Expected |
|---|---|
| Registering with an invite code | Succeeds, returning `device_id` and `device_token` |
| Reusing a spent code | `unauthorized` (re-pair) |
| A valid position | `success` (removed from the queue) |
| Resending the same fix | `success` (the server folds the duplicate) |
| Invalid latitude / out-of-range battery level | `clientError` 400 (discard without retrying) |
| Battery level `-1` (unavailable) | Accepted |
| Wrong token / another device's `device_id` | `unauthorized` |
| RFC 3339 formatting | UTC with milliseconds |

---

## 4. Layout

```
Chikaku/
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
│   ├── LocationTuning.swift    Where the positioning parameters and thresholds live
│   ├── LocationRepository.swift Decides "should this be sent" and queues it
│   └── LocationTracker.swift   CLLocationManager (SLC + standard updates)
├── Upload/
│   ├── Uploader.swift              Drains the queue
│   └── BackgroundTaskScheduler.swift Registration with BGTaskScheduler
└── UI/
    ├── RootView.swift        Screen routing
    ├── DisclosureView.swift  Consent screen
    ├── PairingView.swift     Invite code entry
    ├── PermissionView.swift  Permissions requested in stages
    ├── StatusView.swift      The steady-state screen
    ├── AppModel.swift
    ├── Common.swift
    └── Theme.swift
```

### Mapping to the Android version

| Android | iOS | Note |
|---|---|---|
| Foreground service | `startUpdatingLocation` + Background Modes | No ongoing notification |
| `BootReceiver` / `WatchdogWorker` | Significant Location Change | The OS wakes it after termination or reboot |
| `WorkManager` (exponential backoff) | Drain on each location update + `BGTaskScheduler` | **iOS guarantees no execution time** |
| Room | SwiftData | Excluded from backup |
| DataStore | UserDefaults + Keychain | Only the token goes in the Keychain |
| OkHttp | URLSession | No added dependency |
| `res/values/strings.xml` | `Strings.swift` | Japanese only |

## 5. Battery strategy (per CLAUDE.md §2.2)

iOS has no concept of an update interval, so there is no dial equivalent to
Android's. Power saving comes from **which positioning service is used.**
Details in ADR-6.

**Stage 1: suppress the updates at the OS level**

| Parameter | Value | Intent |
|---|---|---|
| `distanceFilter` | 50 m | **The core of it.** Without movement, no callback happens at all |
| `desiredAccuracy` | `kCLLocationAccuracyHundredMeters` | Avoid GPS alone; use Wi-Fi and cell positioning |
| Significant Location Change | Runs alongside | Near-negligible drain, and wakes the app after termination or reboot |
| `pausesLocationUpdatesAutomatically` | **false** | With true, resumption is not guaranteed and the watch develops a gap (ADR-6) |

**Stage 2: thin the sends in the app** (`LocationRepository`)

- Only send once the device is **50 m or more** from the last queued position
- Send once every **30 minutes** regardless, as a health check (best-effort on
  iOS)
- Discard fixes worse than **500 m** accuracy
- Discard fixes measured **more than five minutes ago.** CoreLocation can
  return a stale cached position right after launch, and sending that as
  "where they are now" shows the child a lie

## 6. Offline resilience

Every fix is written to the SwiftData queue and deleted only after a
successful send.

| Event | Behaviour |
|---|---|
| Offline / network failure | `retryLater`. Retried on the next location update or by `BGTaskScheduler` |
| Server 5xx / 408 / 429 | `retryLater` |
| 401 / 403 | `needsRepairing`. Re-pairing required |
| Other 4xx | Discard that record after 10 attempts |
| Long outage | Queue capped at 500, oldest trimmed first |
| The app was terminated | SLC wakes it again on a position change |
| Device reboot | Likewise — there is no equivalent of `BootReceiver` |

## 7. Privacy and security (CLAUDE.md §5)

- TLS required. Anything other than `https` is permitted in debug builds only
  (decided in `resolve()`)
- ATS uses `NSAllowsLocalNetworking` only. `NSAllowsArbitraryLoads` is not used
- `device_token` lives in the Keychain as
  `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`
  - Not `WhenUnlocked`: location-triggered launches happen while the screen is
    locked. Every send after a reboot would fail until the parent opens the
    phone, with no visible cause
  - `ThisDeviceOnly`: the token is not copied by a backup restore or a device
    transfer
- The outbox is excluded from iCloud backup, including `-wal` and `-shm`
- The location itself is never written to the log
- "Disconnect from family" erases the pairing information and the unsent queue
  from the device
- The consent screen is mandatory on first launch; the watch cannot start
  without it

## 8. Development aids

Launch arguments that exist only under `#if DEBUG`. They are not in release
builds.

```sh
# Consented, not paired (pairing screen)
xcrun simctl launch <dev> com.damburisoft.chikaku.watch -chikakuSeedConsent

# Paired (permission screen → status screen)
xcrun simctl launch <dev> com.damburisoft.chikaku.watch -chikakuSeedPairing

# Grant location permission from the simulator side
xcrun simctl privacy <dev> grant location-always com.damburisoft.chikaku.watch
```

## 9. Not there yet

- **Push notifications.** Dashboard polling covers it for now (phase 2)
- **Geofencing** (`CLMonitor`) for further power savings (phase 3)
- **A notification when the watch stops.** The permission is obtained but
  nothing is sent yet
- **An Apple Watch version.** watchOS supports neither SLC nor region
  monitoring, and background positioning can only be started while in the
  foreground — so "once the app is closed it cannot resume." It does not work
  as a monitoring device
