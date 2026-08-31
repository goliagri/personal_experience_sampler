# C5 — Failure and degradation

Charter: *explore the app with the network, permissions and cloud unavailable or broken, to
discover silent failures and dead ends.* Invariant under test: **the ping/answer path never
touches the network**; sync is best-effort and its failures must be *visible*.

Session: headless emulator, debug APK already installed, re-seeded with `android/tools/emu.sh seed`
(device id `emu-pes`, streams `day` Poisson mean-gap 45 min / `fixed` 12:00+20:00 / `off` disabled,
expiry 60 min, tz America/Los_Angeles). Guest clock driven forward from `2026-09-01 13:0x PDT`.
Real Drive round-trips out of scope (no OAuth client for the debug SHA-1).

**Everything mutated was restored before finishing** — airplane mode back off
(`settings get global airplane_mode_on` = 0), `POST_NOTIFICATIONS` re-granted
(`granted=true`), `SCHEDULE_EXACT_ALARM` appop back to `allow`,
`/data/data/pes.app/files/pes.sqlite{,-wal,-shm}` back to `0600`, the hand-edited config
replaced by a fresh `emu.sh seed`, and `files/last_crash.txt` deleted.

---

### F1 — "Sync now" with no Drive account connected does nothing at all: no toast, no status change, no error — the button is a dead end
Severity: bug

Evidence: airplane mode on, "Not connected" in Settings. Tapped `Sync now`
(`.emu/c5-syncnow-notconnected.png`); after 5 s the Drive block is byte-identical:

```
text="Google Drive sync"
text="Not connected"
text="Last successful sync: never"
text="Connect Google Drive…"
text="Sync now"
```

No `Syncing…` line, no `Last sync failed:` line, no toast. Cause is in
`android/app/src/main/java/pes/app/SyncWorker.kt`:

```kotlin
override fun doWork(): Result {
    val context = applicationContext
    if (!DriveConnection.connected(context)) return Result.success()
```

— the worker reports **success** and never writes `sync_meta/last_sync_result` or
`last_sync_error`, so the observing `DriveSection` has nothing to render. The same early return
is on the path behind **"Restore cloud from local cache…"** (`SyncWorker.restoreNow` enqueues the
same worker with `KEY_RESTORE`), so the restore procedure is equally silent when disconnected —
the one operation the owner will run under stress, in §8.6, giving no confirmation that it did
not run.

Repro:
1. `emu.sh seed`; do not connect Drive.
2. Settings → `Sync now`. Wait 10 s. Nothing changes anywhere.
3. Same for `Restore cloud from local cache…` → confirm.

Expected / why: charter C5 and the app's own design ("Surface the failure in Settings instead of
dying silently" — SyncWorker's own comment) require a sync attempt to end in a legible outcome.
"Not attempted, because no Drive account is connected" is a legitimate outcome, but it has to be
*said*: the user's mental model after tapping is "sync ran". Cheapest fix: in the not-connected
branch write `last_sync_error = "Not connected to Google Drive"` (and for restore, "Restore
skipped: not connected") before returning success.

Test: Tier 1 (`android/tests/device`) — with `drive_connected=false`, enqueue `pes-sync-manual`,
await it, assert `kv(sync_meta,last_sync_error)` is non-empty and mentions the missing connection;
Tier 2 Compose test asserting the error `Text` appears in `DriveSection`.

---

### F2 — Any exception on the engine thread kills the process and permanently stops firing, expiry and scheduling, while Home keeps rendering plausible state and says nothing
Severity: bug

Two different triggers, one root cause: `EngineHost`'s engine task has no `try/catch`, so a
throwing `Engine.tick()` / `Engine.start()` reaches the default uncaught handler, `CrashLog`
records it, and the process dies. Alarms are not re-armed, so the app never ticks again — but the
DB is still readable, so **Home, Backlog and History all render normally and look healthy**.

**Trigger A — unwritable database.** `chmod 400` on `pes.sqlite`, `-wal` and `-shm` (as root),
kill + relaunch. The app starts and renders Home fine (`.emu/c5-db-readonly.png`). Filling in the
14:56 ping and tapping **Submit** drops the user straight to the launcher
(`.emu/c5-readonly-submit.png`), with:

```
E/AndroidRuntime: FATAL EXCEPTION: main
  android.database.SQLException: Error code: 8, message: attempt to write a readonly database
    at pes.store.Db.appendOwnEvent(Db.kt:508)
    at pes.Engine.appendEvent(Engine.kt:155)
    at pes.Engine.answer(Engine.kt:506)
    at pes.app.AnswerScreenKt$AnswerScreen$2$5$1$1$1...(AnswerScreen.kt:481)
```

The typed answer is gone — after restoring `0600` and relaunching, the sample was still `pending`
with no partial event. `last_crash.txt` was then immediately overwritten by the *next* crash
(`Db.kvSet(Db.kt:771)` from `Engine.tick`), i.e. the app was in a crash-on-every-tick loop.

**Trigger B — a config the scheduler cannot resolve.** A config with
`protocol.type = "quantum_teleport"` injected into `config_cache`:

```
engine task, app 0.3.0
java.util.NoSuchElementException: Key quantum_teleport is missing in the map.
  at pes.core.protocols.BaseKt.getProtocol(Base.kt:30)      // registry.getValue(protocolType)
  at pes.core.SchedulerKt.resolveDay(Scheduler.kt:64)
  at pes.Engine.resolvedWindow(Engine.kt:248)
  at pes.Engine.materialize(Engine.kt:276)
  at pes.Engine.start(Engine.kt:305)
  at pes.app.BootReceiver.onReceive$lambda$0(Alarms.kt:54)
```

Consequences observed at guest time `2026-09-02T00:00:30Z`, ~2 h after those pings' 60-minute
expiry:

```
('day|2026-09-01T19:56:26Z', 'skipped')
('day|2026-09-01T20:25:53Z', 'answered')
('day|2026-09-01T21:10:21Z', 'pending')   <- should be expired at 22:10:21Z
('day|2026-09-01T21:56:37Z', 'pending')   <- should be expired at 22:56:37Z
```

and the shade still carried `Daytime (Poisson) / Ping at 14:56 - answer now`, while Home
(`.emu/c5-unknown-protocol.png`) cheerfully listed `Fixed times 0/0/0` and `Weird protocol 0/0/0`
with no warning of any kind. The *only* surface is Settings → **Last crash**, which prints a raw
Kotlin stack trace (`.emu/c5-settings-crash.png`) — accurate, but the user has to already suspect
something and go looking.

Repro (Trigger A, the realistic one — full disk, corrupt WAL, an OEM cleaner, storage encryption
hiccup all produce a failed write):
1. `emu.sh seed`; advance the clock past a Poisson ping so a card is live.
2. `emu.sh shell 'su root chmod 400 /data/data/pes.app/files/pes.sqlite*'`; kill + `emu.sh launch`.
3. Home renders. Answer the ping → app disappears. Relaunch → Home renders again → next tick
   crashes again.

Expected / why: honest accounting (CLAUDE.md) says every generated ping gets exactly one row and
expiry is a real transition; a wedged engine leaves pings `pending` forever and silently stops
generating. Local-first also means the local write path is the one path that must never fail
quietly. Two independent fixes are wanted:
(a) wrap the engine task body so an exception is caught, recorded, and the alarm is *still*
re-armed (or a retry scheduled) instead of taking the process down;
(b) show the fact of a recorded crash / failed engine tick on **Home**, not only in Settings —
one line ("The app hit an error at HH:MM; scheduling may have stopped — see Settings") is enough.
The Answer screen additionally needs the submit failure surfaced in place, keeping the typed form,
rather than vanishing.

Test: Tier 1 — a device test that chmods the DB read-only, submits an answer, and asserts (i) the
process survives, (ii) an error is visible on screen, (iii) after restoring the mode the answer
can still be submitted. Tier 2 / `:runtime:test` — a JVM test that a `Db` whose write throws does
not propagate out of `Engine.tick`, and a scenario test that an unresolvable protocol type in
`config_cache` degrades to "that stream generates nothing" rather than aborting the whole
materialization of every stream.

---

### F3 — Home's sync line only ever shows "Last sync: …"; a sync that has been failing for days is invisible from Home
Severity: bug (the charter's "failures must be visible" surface is half-missing)

Evidence: `MainActivity.kt:260` renders exactly one line and reads only one key:

```kotlin
lastSync = engine.db.kvGet("sync_meta", "last_sync"),      // :101
...
Text("Last sync: ${d.lastSync ?: "never"}", ...)           // :260
```

`sync_meta/last_sync_error` — the key `SyncWorker` goes out of its way to write on every failure —
is read only by `DriveSection` in Settings. On the device Home showed `text="Last sync: never"`
throughout the whole offline session, identical to a healthy never-synced device. There is also
no "(offline)" or staleness hint: after a week of failures Home still says
`Last sync: 2026-08-25T…`, and the user has to notice the date themself.

Repro:
1. Connect nothing, or force a sync failure; run `Sync now` from Settings.
2. Back to Home: the line is unchanged and carries no error.

Expected / why: `PROJECT_PREFERENCES.md` §2 wants the cloud to be a boring, trustworthy backing
store; the failure mode it must not have is "I thought it was syncing". Home is the only screen
the owner sees daily. Render the error (or at least a `⚠` and the age of `last_sync`) there.

Test: Tier 2 Compose test on `HomeScreen` — with `sync_meta/last_sync_error` non-empty, assert a
node containing "failed" (or the warning marker) exists.

---

### F4 — Nothing in the app warns that notifications are off; pings fire correctly and are simply never delivered
Severity: papercut (judgement call, argued below)

Evidence (this half is the **good news** — the invariant holds):
`pm revoke pes.app android.permission.POST_NOTIFICATIONS` + `appops … POST_NOTIFICATION ignore`,
airplane mode still on, clock advanced to the 14:10 Poisson ping.

```
notifications for pes.app: 0
samples: ('day|2026-09-01T21:10:21Z', 'pending')     # fired and recorded
Home:    "Daytime (Poisson) — ping at 14:10"  [Answer] [Snooze] [Skip]
         "Daytime (Poisson)   3 / 1 / 0"
```

The alarm survived the permission-revoke process kill (`origWhen=2026-09-01 13:56:26 …
exactAllowReason=permission` still armed afterwards), the ping fired, the row is right, and
`Notifications.kt:98` swallows the `SecurityException` exactly as intended
(`.emu/c5-home-notifs-revoked.png`). Data intact.

The papercut: the only place that says anything is Settings → Permissions checklist
(`✓/✗ Notifications`), which the user has to go and look at. From Home the app is
indistinguishable from an app that has simply stopped scheduling — which is the single most
expensive misdiagnosis this app can produce, because the owner's whole dataset depends on being
interrupted. A one-line banner on Home when `areNotificationsEnabled()` is false ("Notifications
are off — pings are being recorded but you will not be told") costs nothing and closes the
most likely real-world silent failure (a user tapping "Don't allow" once, or an OEM notification
cleaner).

Repro: revoke `POST_NOTIFICATIONS`, relaunch, open Home. Nothing indicates the problem.

Expected / why: design argument from `PROJECT_PREFERENCES.md` §2 (the answer flow is the product;
a ping you are never told about is a lost sample) plus C5's "silent failures and dead ends".

Test: Tier 2 Compose test — `HomeScreen` with a fake `areNotificationsEnabled() == false` shows a
warning node.

---

### F5 — Exact alarms revoked degrades correctly, but nothing says what the user has lost
Severity: papercut

Evidence: `cmd appops set pes.app SCHEDULE_EXACT_ALARM ignore`, then a clock jump. The next
re-arm switched from exact to inexact, exactly as `Alarms.kt` intends:

```
before: type=RTC_WAKEUP origWhen=2026-09-01 13:56:26 window=0 exactAllowReason=permission flags=0x5
after:  type=RTC_WAKEUP origWhen=2026-09-01 15:10:21 window=+10m8s609ms  flags=0x20
        whenElapsed=+13m30s949ms  maxWhenElapsed=+23m39s558ms
```

The ping at 14:56 still fired and still notified (`Ping at 14:56 - answer now`), no crash. Settings
showed `✗ Exact alarms` with a `Grant` button. So the degradation itself is fine.

What is missing is the consequence: the measured slack was **+10 minutes on a 60-minute expiry
window**, i.e. up to a sixth of every response window can be eaten before the user is even told,
and latency (measured from the scheduled time, per CLAUDE.md) is silently inflated. `✗ Exact
alarms` does not tell the owner that. One clause — "pings may arrive up to ~10 minutes late" —
turns a checkbox into a decision.

Repro: `cmd appops set pes.app SCHEDULE_EXACT_ALARM ignore`, force a reschedule (`emu.sh settime`),
`emu.sh alarms`.

Expected / why: preferences §2 keeps latency honest; the user cannot reason about their own data
if the sampling instrument's precision changes without saying so.

Test: Tier 1 — with the appop ignored, assert the pending alarm has a non-zero window and that the
Settings checklist row text mentions the delay.

---

### F6 — A config carrying a protocol type this client does not know is fatal by construction, and nothing re-validates a config once it is in `config_cache`
Severity: question

`pes.core.protocols.getProtocol` is `registry.getValue(protocolType)` (`Base.kt:30`) — a hard
`NoSuchElementException` for anything unregistered, thrown from deep inside `materialize` where it
takes the whole engine down (F2, trigger B). `Engine.applyConfig` *does* call `validateConfig`
(`Engine.kt:146`) and `_check_protocol` rejects `bad_protocol_type`, so a bad config cannot arrive
through the normal sync path today — my repro injected it directly into `config_cache`, which is
artificial. But:

- the guard runs only at *apply* time; a config already in `config_cache` (written by a different
  app version, restored from a backup, or applied before a protocol was renamed) is never
  re-checked, and the app has no way back — every launch crashes in `start()`;
- forward compatibility is a real scenario for this project: desktop and Android ship separately
  (`M6`/`M7` add protocols), and the owner will edit config on desktop. A desktop that gains a new
  protocol before the phone is updated writes a config the phone will refuse — and today the phone
  refuses it by *bricking its own scheduler for every stream*, not by ignoring one stream.

The owner should decide the intended semantics: (a) an unknown protocol makes the whole config
invalid and the client keeps the last good one (needs `applyConfig` to be the only door *and* a
visible "config rejected" message), or (b) an unknown protocol disables just that stream and the
other streams keep running (needs `getProtocol` to return null and `resolveDay` to skip). Either
way it must not be an uncaught throw. Same question applies to a config `version` far in the
future — `validate_config` only checks `version >= 1`, and version `999` was accepted with no
comment.

Test: once decided — a `spec/` conformance vector plus mirrored `:runtime:test` / pytest scenario
asserting the chosen behaviour in both languages.

---

## Checked and fine

- **Local-first, end to end, with no network at all.** Airplane mode on
  (`settings get global airplane_mode_on` = 1, `Active default network: none`) for the whole first
  half of the session. With zero connectivity: the Poisson ping at 13:25 **fired** (event
  `{"ev":"fired","sample":"day|2026-09-01T20:25:53Z",…}`), **notified**
  (`Daytime (Poisson) / Ping at 13:25 - answer now`, 3 actions), was **answered** through the normal
  form (`{"ev":"answered","answers":{"tags":["offline-test"]},…}`, Home went to `2 / 1 / 0`), and a
  second ping was **skipped** (`day|2026-09-01T19:56:26Z → skipped`). Not one network-related delay,
  error or spinner anywhere on that path.
- **The answer path never blocks on sync.** `SyncWorker` opens its own `Db` and the manual one-shot
  request carries no network constraint, so a `Sync now` while offline returns instantly instead of
  queuing behind connectivity; the engine thread was never involved.
- **Alarms survive a permission revoke.** `pm revoke POST_NOTIFICATIONS` kills the process, but the
  armed `RTC_WAKEUP … PingAlarmReceiver` was still present afterwards and still delivered.
- **Notifier failure is contained.** `NotificationManagerCompat.notify` throwing `SecurityException`
  with the permission revoked did not reach the engine: the sample row is correct and the app did
  not crash.
- **Exact-alarm degradation works** (F5 covers only the missing explanation): inexact re-arm, ping
  still fired and notified.
- **Permissions checklist is accurate when freshly opened**: `✓ Notifications / ✗ Exact alarms /
  ✗ Battery optimization exempt` matched `dumpsys package` and `cmd appops` exactly at the moment
  the screen was entered. (Its staleness on resume is C4 F5 — not re-reported.)
- **`CrashLog` really does capture and surface crashes.** Both the read-only-DB `SQLException` and
  the unknown-protocol `NoSuchElementException` were written to `files/last_crash.txt` and rendered
  under **Last crash** in Settings (`.emu/c5-settings-crash.png`).
- **Recovery from the read-only DB is clean.** After `chmod 600` + relaunch, the two pings were
  still there and still `pending` with no duplicate rows and no phantom `answered` — the aborted
  submit left no partial event.
- **A stream removed from the effective config stops generating, without disturbing history.**
  After the injected config dropped `day`, the `day` ping scheduled for `2026-09-02T00:00:16Z` did
  not fire and produced no row, while the already-fired `day` samples kept their correct
  `Daytime (Poisson)` name on Home (`streamConfig` resolves the config effective *at the scheduled
  time* — right behaviour, worth keeping a test on).
- **Home does not refresh while on screen** — known, C4 F4, deliberately not re-reported; all
  screen readings above were taken after leaving and re-entering.

### Observations, not findings

- The WAL is never checkpointed: `pes.sqlite` 80 KB against a `pes.sqlite-wal` of **4.1 MB** after
  ~1 h of use (`su root ls -l /data/data/pes.app/files/`). Harmless here, but it is why
  `emu.sh db` must pull both, and it will grow unbounded on a real phone; a periodic
  `wal_checkpoint(TRUNCATE)` is cheap.
- `dumpsys jobscheduler` showed the periodic sync job with `Minimum latency: +1d0h53m5s90ms`
  rather than ~1 h, after this session's repeated `settime` jumps
  (`Unsatisfied constraints: TIMING_DELAY CONNECTIVITY`). Almost certainly an artifact of driving
  the guest clock (WorkManager schedules on elapsed-realtime), so not reported as a finding — but
  if a later charter sees hourly sync not happening on a device whose clock has moved, this is the
  place to look.
- `Db.upsert_config` (desktop) accepted the `quantum_teleport` config without complaint; validation
  lives only in `validate_config`, called from `engine.apply_config`. Fine as designed, noted so the
  next charter does not mistake the store for a validating layer.
