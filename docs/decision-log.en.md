*[日本語版](decision-log.md)*

# Decision log

**Not what changed, but why it was decided that way.** The diffs are in
`git log`; how the thing works is in
[implementation-status.en.md](implementation-status.en.md). What's here is the
step before that — what actually happened on the device, how it was reasoned
about, and what was checked.

The on-device logs are reproduced as they were. **The attempts that did not
work have been kept.** Two calls that turned out wrong —
`registerDefaultNetworkCallback`, and inferring a fix's origin from its
altitude — are, I think, the most useful part to read.

> These were originally written as pull request descriptions, one per change,
> and have been collected into a single file. Dates are as they were.


## Ask for satellites, and mark the fixes that still can't be trusted

Issue #13 / merged 2026-08-31

Two halves: **make the untrustworthy fixes visible, and produce fewer of
them.**

#### 1. Making them visible

#### Why not filter on accuracy

All ten bad points claimed **100 m or 300 m while being 7 km wrong.** Every
threshold was checked against all 233 records.

| Threshold | Bad points surviving | Real trips surviving | Points recorded while out |
|---|---|---|---|
| **500 m (current)** | 10/10 | 3/3 | 69 |
| 200 m | 5/10 | 3/3 | 46 |
| 100 m | 5/10 | 3/3 | 36 |
| **80 m** | **0/10** | **2/3** | **18** |

**Removing the errors requires dropping below 80 m, and that loses 74% of the
points recorded while out of the house.** Indoors, underground and in a car
only coarse fixes are available, so "arrived at the hospital" disappears
entirely. Ten wrong points would cost fifty-one right ones.

#### Recording where each fix came from

`getProvider()` often returns nothing more informative than `fused`, so the
signal used instead is **whether altitude, speed and bearing are present.**
Satellite fixes carry altitude; Wi-Fi and cell positioning cannot produce one.

**This is a heuristic, not a guarantee.** The raw values behind the
classification are stored too, so it can be re-derived once there is real
device data. Speed and bearing are not required, because requiring them would
discard satellite fixes taken while standing still (pinned by a unit test).

`NULL` means "not reported", not "it was a network fix". Filling it with zero
would mark every fix from the iOS app and from older builds as approximate
(the same mistake as #4). The Room migration is non-destructive, so positions
queued while offline survive it.

#### The dashboard distinguishes rather than deletes

| | |
|---|---|
| Accuracy circle on the current position | Grey, dashed |
| Map tooltip | Adds "approximate position" |
| Movement path | That segment alone is grey and dashed |
| Device card | States "this is an approximate position" and why |

**Deleting them would delete "was at the hospital" along with them.** The
judgement lives in one place, `isTrustedFix()`, so the map and the card cannot
disagree.

#### Verification

| | Result |
|---|---|
| Server e2e | 36 (+3) |
| Android unit | 18 (+5) |
| iOS contract | 12 (omitting it breaks nothing) |
| `assembleRelease` / `lintVitalRelease` | Pass; only the 5 known lint warnings |


#### 2. Producing fewer of them — switching to `PRIORITY_HIGH_ACCURACY`

**Recording the origin does not reduce the errors.** Google's documentation
says BALANCED "will rarely use GPS" and relies mainly on Wi-Fi and cell
information, so leaving it would keep producing the same mistake.

**Battery is held down by the interval, not the priority.** Positioning is not
continuous: every 5 minutes at most, and nothing is delivered at all unless the
device has moved 50 m. GNSS does not run back to back. The rule of thumb that
"HIGH_ACCURACY burns the battery" assumes a one-second interval and does not
transfer here.

**It will cost something, though, so a baseline was taken from real data
first.** Battery level is recorded alongside every position, so running the
same calculation after the switch gives a comparison.

| | BALANCED (before the switch) |
|---|---|
| Overall mean | **2.50 %/h** (91.6 hours / 229%) |
| Stationary overnight | 0.35 %/h |
| Worst interval | 5.13 %/h (includes screen use) |

**Doubling is acceptable; more than tripling means revisiting it** — in that
case by using Activity Recognition to raise accuracy only while moving.

CLAUDE.md §2.2 specified BALANCED, so it was rewritten along with the reason
for the retraction. **A specification that silently disagrees with the running
code is the worst outcome.**

---

## Watch for a physical link, not for the default network

Issue #15 / merged 2026-09-21

#### What was happening

On 2026-09-07 the phone lost its connection at 16:20. Wi-Fi came back at 19:51.
**Sending did not resume; the last position reached the server at 01:08 the
next morning** — 8 hours 17 minutes late.

| Measured at | Received at | Delay |
|---|---|---|
| 16:19:55 | 16:19:57 | 0 min (normal) |
| 16:25:57 | 20:51:54 | 266 min |
| 16:51:32 | 01:08:16 next day | **497 min** |

Behaviour while the line was down was correct. What was broken was **recovery
after it came back**, for two reasons at once:

1. `UploadWorker`'s exponential backoff grows to WorkManager's five-hour
   ceiling and **is never cleared when the connection returns.**
2. The hourly `WatchdogWorker` looked like the safety net and wasn't. It called
   `enqueueNow`, which uses `ExistingWorkPolicy.KEEP`, so it found the waiting
   work and returned without touching it. In the device log it
   **finished in 203 milliseconds with `SUCCESS` and sent nothing.**

#### The fix

##### `UploadScheduler.retryNow()`

Chooses between `REPLACE` and `KEEP` based on the existing work's state. The
decision itself is a pure function with no Android dependency:

```kotlin
fun shouldResetBackoff(state: WorkInfo.State?, runAttemptCount: Int): Boolean {
    // **Never touch work that is running.** The worker keeps reading until the
    // queue is empty, so replacing it here would cut every drain short.
    if (state != WorkInfo.State.ENQUEUED) return false
    // Without a failure there is no backoff attached, so there is nothing to discard.
    return runAttemptCount > 0
}
```

`REPLACE` rather than `cancelUniqueWork` followed by `enqueueNow`, because
`REPLACE` performs the cancel and the enqueue as one operation. Split apart,
the gap between them can be lost to `KEEP`.

##### Two paths back

| | Who | Delay |
|---|---|---|
| Immediate | `LocationTrackingService` watches the network and calls `retryNow` on `onAvailable` | seconds |
| Fallback | `WatchdogWorker` calls `retryNow` hourly | up to 1 hour |

The foreground service is the only thing that is always resident, so it is the
only place that can notice the moment the line returns. If the queue is empty
it does not touch WorkManager at all — connections switch repeatedly while
moving.

`onUserUnlocked` now calls `retryNow` too. A reboot can restore the previous
backoff along with everything else, and waiting it out would stop the watch for
hours immediately after a restart.

##### The backoff policy itself is unchanged

Option 3 from the issue — shortening the ceiling — was not taken. It would mean
hammering a server that is genuinely down, and the two paths above are judged
sufficient. **Open to changing this.**

##### `onAvailable` is not read as reachability

On the device in question, a VPN with nothing underneath it stayed `VALIDATED`
(`Active default network: none`, `UnderlyingNetworks: []`).
`NetworkType.CONNECTED` was satisfied and DNS kept failing in 11 milliseconds.

The callback is written to buy an attempt, nothing more. When the attempt
fails, it returns to backoff and the watchdog picks it up next time.

#### Tests

```
UploadSchedulerTest        tests=5 failures=0 errors=0   ← new
StorageMigrationTest       tests=5 failures=0 errors=0
LocationRepositoryTest     tests=5 failures=0 errors=0
LocationSourceTest         tests=5 failures=0 errors=0
LocationTuningTest         tests=3 failures=0 errors=0
```

`lintDebug` and `assembleDebug` pass. All 10 lint findings pre-date this change.

#### Not done yet

**Not verified on hardware** — no device connected. The unit tests pin only the
decision; whether `registerDefaultNetworkCallback` actually fires `onAvailable`
is unchecked.

The procedure is recorded as unverified in
[implementation-status.en.md](implementation-status.en.md) §7.3:

1. Airplane mode on, leave it 30 minutes or more (to grow `run_attempt_count`)
2. Confirm with `adb shell dumpsys jobscheduler` that the next run is tens of
   minutes away
3. Airplane mode off
4. **Within one minute**, the queue should drain and reach the server

This device keeps barely two minutes of logcat ring buffer, so widen it with
`adb logcat -G 16M` before starting.

#### Corrected along the way

Two items in §7.3 — "battery impact unmeasured" and "origin classification
unverified" — had been answered by the preceding analysis, so they were
replaced with the measured figures (2.56 %/h → **2.21 %/h**, no regression) and
a pointer to #16. Out of scope for #15, but leaving a false statement in a file
being edited is worse.

### Addendum (2026-09-19)

#### On-device testing found the immediate path was wrong → fixed in `039b769`

##### Test 1: airplane mode + Wi-Fi off → restore

Sent **2 seconds** after recovery. That meets the acceptance criterion, but
**it was not a reproduction of #15.** With no network at all,
`NetworkType.CONNECTED` is never satisfied, so `UploadWorker` never runs and no
backoff accumulates; when the line returns, WorkManager starts it on its own.
That path worked before the fix.

##### Test 2: Tailscale on + Wi-Fi off → Wi-Fi on (the same shape as 9/7)

| Time | Event |
|---|---|
| 20:29:09 | Health check queues one position → `UnknownHostException` (11 ms) |
| 20:30:09 | Retry +1 min → fails |
| 20:32:09 | Retry +2 min → fails. `run_attempt_count=3` |
| **20:32:31** | **Wi-Fi returns** |
| 20:36:09 | Retry +4 min → `202` |

Reproduced successfully. **But `onAvailable` was never called.** The upload
went through on the backoff retry that happened to be scheduled four minutes
later; with the attempt count where it stood on 9/7, that would have been over
two hours.

The cause is `registerDefaultNetworkCallback`. Under a VPN, the app's default
network *is* the VPN. When Wi-Fi returns, the VPN's underlying link is swapped
but the default does not change, so `onAvailable` never fires again.

##### The fix

`registerNetworkCallback` with a `NetworkRequest` for INTERNET capability.
`NetworkRequest` excludes VPNs by default, so it reacts the moment a physical
link — Wi-Fi or mobile data — comes up.

**Unverified after the change.** The device has been disconnected; test 2 will
be re-run next time it is attached. The procedure in §7.3 has been updated.

### Addendum (2026-09-21)

#### On-device testing: test 2 passes

Re-ran the Tailscale-on, Wi-Fi-off condition with the debug APK from `039b769`.

| Time | Event |
|---|---|
| 11:25:01 | Wi-Fi off → `Active default network: none`, VPN `CONNECTED` |
| 11:40:59 / 11:41:59 / 11:44:00 / 11:48:00 | Four failures with `UnknownHostException`. Backoff 1→2→4→8 min |
| **11:48:20.5** | `svc wifi enable` |
| 11:48:22.98 | `TrackingService: the line is back, retrying the upload` |
| 11:48:23.06 | `Processor cancelling 36e5bdb2…` (old work discarded via `REPLACE`) |
| 11:48:23.63 | `202` |

**3.2 seconds from Wi-Fi on to upload complete.** Waiting for the scheduled
retry would have meant 11:56.

Server side: the two positions measured at 11:40:58 and 11:41:06 were received
at 11:48:25 — a 7.4 minute delay, which is exactly the length of the outage.

##### What changed since 9/19

Under the same conditions, `registerDefaultNetworkCallback` did not react to
Wi-Fi returning at 20:32:31 and the upload waited for the 20:36:09 retry.
Switching to a `NetworkRequest` for INTERNET capability made it react to the
physical link even with the VPN up.

##### Still unverified

The hourly `WatchdogWorker` → `retryNow` fallback has still not been seen on
hardware, because the immediate path got there first both times. The decision
is pinned by `UploadSchedulerTest`. Exercising the path on a device needs a
state where the line is up but DNS alone is broken (changing the Private DNS
setting).

---

## Judge a fix by the shape of its error, not by what the phone claims

Issue #16 / merged 2026-09-21

Option A from #16. No app changes — only the dashboard's judgement is replaced.

#### What changed

`isTrustedFix` no longer rests on the phone's self-reported `source_kind` but
on **two signals that were in the data all along.** The judgement lives in one
new file, `client/src/lib/trust.ts`, used by the map (current-position circle
and path) and the device card alike.

| Reason to doubt | Evidence (checked against 1,770 production records) |
|---|---|
| `database_accuracy` — accuracy reported as exactly **100 / 200 / 300 / 400 / 500** | All ten known-bad points have it. Of 512 fixes carrying a speed, two do. All eleven pairs delivered within one second of each other run round-number first, real-number second |
| `spike` — neighbours within 1 km of each other, but this point 2 km or more from both | The 9/3 point that reported 56.1 m while being 33 km wrong is only catchable this way. Across 1,770 records the condition fires on that point alone |
| `reported_network` / `reported_unknown` — self-reported | Only four of them, but no reason to ignore what the phone says |

A `satellite` label is disbelieved when the accuracy has the database shape.
All 561 `satellite` classifications rested on broken evidence.

#### Replayed over production data

```
{ database_accuracy: 284, spike: 1, trusted: 1485 }
spikes: [ '09-03 15:41:58' ]
8/29 eastward 10 points: all database_accuracy
```

Matches the figures from the analysis in #16. **This shows the known errors can
be caught, not that unknown ones will be.** §7.3 records the procedure for
replaying it the next time an error turns up.

#### What it deliberately does not flag

- The 22.5 m fix most often recorded while stationary. It is **actually a
  network-provider output** (confirmed against `dumpsys location`) but the
  location is right. The goal is not to identify network fixes, so it is
  trusted.
- `source` of null — the iOS app and builds predating the field. Not suspicious
  on its own.
- Spikes need both neighbours, so the rule cannot apply to the latest position.
  Nothing can be said until the next point arrives.

#### Tests

`vitest` was added to the client. There was no test runner before, but this
judgement has now been wrong twice against real data, so it does not get to be
untested again.

```
✓ src/lib/trust.test.ts (17 tests)
```

The figures all come from production data — the accuracy values of the ten
points on 8/29, the spacing of the three points on 9/3, and so on. The
coordinates themselves are synthetic, re-anchored so the distances are
preserved; see the note at the top of the test file.

#### Remaining

- **Replacing what the app records (option B in #16) has not been started.**
  The display is corrected without it, so it is not urgent.
- The four points between 16:37 and 16:50 on 8/30, 3.4–3.6 km from the nearest
  GPS fix, have not been confirmed either way.
