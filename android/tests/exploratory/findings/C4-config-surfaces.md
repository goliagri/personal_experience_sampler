# C4 — Configuration surfaces and the "no peeking" rule

Charter: `CHARTERS.md` C4. Emulator, debug APK, device id `emu-pes`.

Seed used for everything below (unless a step says otherwise):

```
python3 android/tools/emu_seed.py --push --now '2026-09-01T18:50:00Z' \
        --no-poisson --fixed 12:00,12:20,12:40,13:00 --expiry 15
android/tools/emu.sh settime '2026-09-01 11:55:00'
```

Headline: **no upcoming-ping-time leak was found** — Home, Streams, the notification, the
Backlog/History rows and the test-ping status line all show only times that have already
happened; the next 48 h appears only after tapping "Show schedule (next 48 h)…" in Settings.
The findings are instead about *missing* and *stale* configuration state: two Home features
required by §10.2 are absent on Android, and both Home and the permissions checklist keep
showing state that has since changed.

---

### F1 — Home has no ping calendar; the last-8-weeks view required by §10.2 does not exist on Android
Severity: divergence

Evidence: `.emu/c4-home1.png` — Home ends at the sync line and the History/Streams/Settings
links; there is no calendar. Full `emu.sh ui` text of Home is exactly: "Experience Sampler",
"No active ping.", "Streams (today: fired / answered / expired)", "Fixed times 0 / 0 / 0",
"Quiet: off", "Last sync: never", "History", "Streams", "Settings".
Source: `android/app/src/main/java/pes/app/MainActivity.kt` `HomeScreen` / `HomeData` have no
calendar member at all. Desktop has it: `desktop/pes/ui/home.py:181` `_calendar()`,
"Last 8 weeks, one cell per day, colored by answer rate".

Repro: seed as above, `emu.sh launch`, `emu.sh shot`.

Expected / why: spec §10.2 lists the ping calendar as part of Home ("ping calendar (last 8
weeks, one cell per day colored by answer rate)"), and preferences §2 "Analysis" explicitly
wants cadence/response feedback in the app ("a stylized calendar of past pings"). The desktop
client implements it, so the two clients are not "recognizably the same app" here.

Test: Tier 2 (`app/src/androidTest`) — a Compose test asserting Home contains a calendar node
with 8 week rows once the feature exists; until then this is a backlog item, not a test.

---

### F2 — Android quiet mode offers only "until turned off"; the "for H:MM" variant is unreachable
Severity: divergence (spec + preferences require both variants)

Evidence: Home's only quiet control is one button that toggles between "Quiet: off" and
"Quiet: on (tap to turn off)" (`emu.sh ui`: `bounds="[42,990][322,1116]" text="Quiet: off"`,
then `bounds="[42,990][575,1116]" text="Quiet: on (tap to turn off)"`).
Source: `MainActivity.kt` HomeScreen — `act { it.setQuiet(if (it.quietActive(now)) null else "indefinite") }`;
`"indefinite"` is the only value the Android UI can ever write. The engine supports a timed
value (`Engine.kt:192 setQuiet(quietUntil)` takes an ISO instant), and desktop exposes it:
`desktop/pes/ui/home.py:85` `ttk.Button(quiet, text="For H:MM...", command=self._quiet_for)`.

Repro: seed as above, tap the quiet button on Home; there is no second option and no dialog.

Expected / why: preferences §2 "Other decisions": *"Quiet mode: a global toggle suppressing all
pings, with options 'until turned back on' and 'for X hours:minutes' that auto-reverts."*
Spec §10.2: "quiet mode toggle (tap: 'until turned off' / 'for H:MM')". This is the phone's
most likely use ("quiet for 2 h, I'm in a meeting"), and on the phone it is the variant that is
missing — the user must remember to turn quiet back on, which is exactly the failure mode the
timed variant exists to prevent.

Test: Tier 2 — Compose test: tapping quiet offers both options; choosing "for 0:30" writes
`kv state.quiet_until` = now+1800 s and a `quiet_changed` event with that value.

---

### F3 — A timed quiet arriving from another device shows on Android as an indefinite quiet: no end time anywhere
Severity: divergence

Evidence: with `kv('state','state')` = `{"quiet_until":"2026-09-01T19:10:00Z",...,"set_by":"desktop"}`
pushed into the app DB and the device clock at 12:01 PDT, Home reads
`bounds="[42,990][575,1116]" text="Quiet: on (tap to turn off)"` — identical to the label for an
indefinite quiet, with no "until 12:10". Desktop for the same state renders
`"Quiet: on until <quiet_until>"` (`desktop/pes/ui/home.py:75`).

Repro: 1. seed as above; 2. `emu.sh db`; 3. rewrite `kv` row `('state','state')` with a future
`quiet_until` and `PRAGMA wal_checkpoint(TRUNCATE)`; 4. push it back with
`emu_seed.push(<file>, android/tools/emu.sh)`; 5. read Home.

Expected / why: quiet state is synced between devices (`state.json`, §8), so a phone can hold a
timed quiet it did not set. The screen then tells the user "on until you turn it off", which is
false — pings resume on their own at 12:10. Same preference as F2. (Note the desktop label is
not great either: it prints the raw UTC ISO string rather than a local time — worth fixing in
the same pass, but that is C4-adjacent, not an Android finding.)

Test: Tier 2 — Compose test over a DB with `quiet_until` in the future: Home text contains the
local end time.

---

### F4 — Home never refreshes while it is on screen: a ping that fires with Home open is invisible until the activity is resumed
Severity: bug (papercut on a good day; it makes the "no active ping" line untrue)

Evidence: `.emu/c4-home-stale.png`. Device left on Home at 12:55, then
`emu.sh settime '2026-09-01 13:01:00'`. The 13:00 ping fires — the notification exists:
`emu.sh notifs` → `android.text=String (Ping at 13:00 - answer now)` — but Home, untouched,
still reads:

```
text="No active ping."
text="Backlog: 1 expired ping(s)"
text="Fixed times   1 / 0 / 1"
```

Only after backgrounding + relaunch (ON_RESUME → `bump()`) does the active card appear
(`text="Fixed times — ping at 13:00"`, counts `2 / 0 / 1`).
Source: `MainActivity.kt` — `HomeData` is read in `produceState(null, refresh)` and `refresh` is
bumped only by a user action or `Lifecycle.Event.ON_RESUME`. Nothing bumps it on an engine
event or on a timer.

Repro: as above (any ping arriving while Home is foreground reproduces it; the clock jump is
only a way to schedule it).

Expected / why: spec §10.2 makes Home the place that shows the active sample, today's counts and
the sync line; a screen that silently stops matching the DB is the "wrong, stale state" this
charter is chartered to find. It also has a real cost: a user watching Home while waiting for a
ping (or who left the app open when the phone was in a pocket) sees "No active ping" while the
notification says otherwise, and the same staleness will hide the F2/F3 quiet auto-revert and a
sync completing in the background. The engine already runs on its own thread inside the same
process, so a state flow (or even a 30 s tick while resumed) is cheap and does not violate the
"no polling" rule, which is about wakeups, not about a foreground screen.

Test: Tier 2 — Compose test: with the Home screen composed, drive the engine to fire a ping and
assert the active card appears without an ON_RESUME. (Tier 1 can also assert it via
`emu.sh ui` after a `settime` jump with the app foreground.)

---

### F5 — The permissions checklist is computed once and never re-checked on resume, so granting a permission from the system screen leaves it showing ✗
Severity: bug

Evidence: `.emu/c4-perm-revoked.png`, `.emu/c4-perm-stale.png`.
1. `emu.sh shell 'pm revoke pes.app android.permission.POST_NOTIFICATIONS'` and
   `emu.sh shell 'cmd appops set pes.app SCHEDULE_EXACT_ALARM ignore'` → Settings correctly shows
   `✗ Notifications` / `Grant` and `✗ Exact alarms` / `Allow` (checklist reads the real state —
   that part is fine).
2. With Settings still open, re-grant both from outside:
   `cmd appops set pes.app SCHEDULE_EXACT_ALARM allow`, `pm grant pes.app android.permission.POST_NOTIFICATIONS`.
   Settings still shows `✗ Notifications`, `✗ Exact alarms`.
3. Press HOME, relaunch (activity resumed, composition intact): **still** `✗ / ✗`.
4. Only navigating Home → Settings again (which disposes and re-creates the composition) shows
   `✓ Notifications`, `✓ Exact alarms`.

Source: `Screens.kt` `SettingsScreen` — `val checklist = remember(checkTick) { permissionsChecklist(...) }`.
`checkTick` is incremented when the user *taps* the action button (i.e. before they have granted
anything), and `refresh` is not a key of that `remember`, so the ON_RESUME bump does not
recompute it either.

Repro: steps 1–4 above; step 2 also happens naturally, because the "Allow" button for exact
alarms and the battery-optimisation "Exempt" button both leave the app for a system screen and
come back via onResume — the exact flow spec §11 prescribes.

Expected / why: spec §11 makes the permissions checklist the mechanism that gets the app into a
working state on a new phone; a checklist that reports ✗ after the user has just granted the
permission trains them to distrust it, and (worse) invites them to re-open the system screen
and conclude the app is broken. The `checkTick`-on-tap increment is a hint the staleness was
half-anticipated: it re-checks at the wrong moment.

Test: Tier 2 — Compose test on `SettingsScreen`: change the permission state behind the screen,
send an ON_RESUME, assert the row flips to ✓. (The `permissionsChecklist` computation itself is
already correct; the assertion is about when it is recomputed.)

---

### F6 — A never-synced device with no Drive connection reports "Snapshot role: none (another device holds primary)"
Severity: papercut

Evidence: `.emu/c4-settings.png` / `emu.sh ui` on Settings, on a fresh seed with no Drive
connection:

```
text="Google Drive sync"
text="Not connected"
text="Last successful sync: never"
text="Snapshot role: none (another device holds primary)"
```

DB: `kv` has no `('device','role')` row at all (`select * from kv where ns='device'` →
only `device_id`). Source: `Screens.kt` SettingsScreen prints the "another device holds
primary" branch for *any* role value that is not `"primary"`, including "unknown / never
claimed".

Repro: seed, open Settings, read the line.

Expected / why: §9's primary role is *claimed* through the cloud folder; a device that has never
reached a cloud folder knows nothing about who holds it, and asserting that another device does
is a statement the app cannot support (here it is simply false — there is no other device and no
folder). Three states exist — primary, another device is primary, unknown/not connected — and
the UI collapses them into two. Minor, but this is precisely a "wrong state" surface.

Test: Tier 2 — Compose test: with no `('device','role')` row and no Drive connection, Settings
must not claim another device holds primary.

---

### F7 — The Streams list summarises a protocol by its internal type id only; desktop shows the parameters
Severity: divergence (papercut in effect)

Evidence: `emu.sh ui` on Streams: `text="Fixed times (fixed_times)"` and
`text="Disabled stream (fixed_interval) — disabled"`. Source: `Screens.kt` StreamsScreen builds
`StreamRow(..., p.str("type"))`. Desktop builds `summary = protocol["type"] + " " + ", ".join(...)`
of the protocol params (`desktop/pes/ui/streams.py:39`).

Repro: seed, Home → Streams.

Expected / why: spec §10.2 says the streams list shows "protocol summary". `(fixed_times)` is
the wire-format enum, not a summary — it tells the user nothing they did not already get from the
stream name, and on a read-only screen (Android cannot edit streams) the summary is the *only*
thing the screen is for besides the test-ping button. The desktop rendering ("fixed_times 12:00,
20:00" style) is the one that matches the spec wording. Opinion: showing the mean gap / times /
interval is the whole value of this row.

Test: Tier 2 — Compose test: a `fixed_times` stream's row contains its times; a `poisson`
stream's row contains its mean gap.

---

### F8 — "Fire test ping now" reports the raw sample id and gives no way to reach the ping it just created
Severity: papercut (partly opinion)

Evidence: `emu.sh ui` after tapping the button:
`text="Test ping fired: fixed|2026-09-01T18:55:25Z"`. The string stays on the screen for the rest
of the session (the `status` state is never cleared), and there is no "Answer it" affordance; the
user has to press Back and find the card on Home. The mechanics underneath are correct (see
"Checked and fine").

Repro: seed, Home → Streams → "Fire test ping now".

Expected / why: the sample id is an internal identity key (`<stream>|<scheduled UTC>`), a UTC
timestamp shown to a user whose whole UI is otherwise local time. Preferences §2 wants the test
ping as a *try it out* button; landing on the answer form (or at least "Answer now") is the
obvious completion, and a confirmation like "Test ping fired at 11:55" carries the same
information without the internal id. Opinion, argued from "the answer flow is the priority".

Test: Tier 2 — Compose test: the confirmation contains a local time, not the sample id.

---

### F9 — "Show schedule (next 48 h)" lists stream **ids**, not stream names
Severity: papercut (both clients — not a divergence)

Evidence: `.emu/c4-schedule.png` / `emu.sh ui` after tapping "Show schedule (next 48 h)…":

```
text="Schedule (next 48 h)"
text="2026-09-02 12:00  fixed"
...
text="2026-09-03 13:00  fixed"
```

`fixed` is the stream id; Home and Streams call the same stream "Fixed times". Desktop's
schedule window does the same (`desktop/pes/ui/settings.py:225-228`), so both clients are
consistently unhelpful. Source: `Screens.kt` `ScheduleSection` maps `it.stream` straight through
(it already has the engine in hand — `engine.streamConfig(...)?.str("name")` is what History and
Home use).

Expected / why: §10.2 makes this the one sanctioned way to look at upcoming pings; with several
streams the ids are the only thing distinguishing rows, and ids are a config-file detail.

Test: Tier 2 — Compose test: the revealed schedule rows contain the stream name.

---

## Checked and fine

- **The "no peeking" rule holds everywhere I could reach.** No future ping time appears on Home
  (active card shows the *fired* time, "ping at 13:00"), on Streams (name + protocol type only),
  in the notification ("Ping at 13:00 - answer now"), in Backlog/History (scheduled times of
  samples that already exist), or in the test-ping confirmation. `Screens.kt` `ScheduleSection`
  is gated behind an explicit "Show schedule (next 48 h)…" tap, starts collapsed, and collapses
  again when Settings is left and re-entered. The `schedule` table's future rows are never folded
  into `samples`, so History cannot leak them.
- **Show schedule content**: at 13:01 on Sep 1 it listed 2026-09-02 12:00 → 2026-09-03 13:00 —
  the whole materialized horizon, all of it inside 48 h, nothing from the past.
- **"Fire test ping now" is a real sample on the normal path.** Event
  `fired | fixed|2026-09-01T18:55:25Z | ... {"config_v":2,"dev":"emu-pes","ev":"fired","sample":"fixed|2026-09-01T18:55:25Z","scheduled":"2026-09-01T18:55:25Z","stream":"fixed","t":"2026-09-01T18:55:25Z","test":true}`
  — `test: true`, scheduled at the current second; sample row `status pending, observed true,
  test true`; a normal `pings`-channel notification with 3 actions was posted; Home showed the
  normal active card with Answer/Snooze/Skip and the today count went 0 → 1.
- **Disabled streams**: the "Disabled stream" row is marked "— disabled" and has no test-ping
  button; it is correctly absent from Home's per-stream counts.
- **Read-only by design is stated, not implied**: Streams carries "Streams are edited on the
  desktop; changes arrive at the next sync." and Settings labels the timezone "(edited on
  desktop)". Nothing on either screen looks tappable-but-inert — the only interactive widgets are
  the test-ping button, the device-name field, and the checklist actions, all of which work.
- **Quiet mode logs, it does not vanish pings.** Quiet on at 18:56:18Z (`quiet_changed`,
  `quiet_until: "indefinite"`), clock to 12:01 → event
  `suppressed | fixed|2026-09-01T19:00:00Z | 2026-09-01T19:01:00Z | {"reason":"quiet_mode",...}`
  and sample row `status suppressed, observed false`. The quiet flag survived a force-stop +
  relaunch and a DB push/relaunch cycle (it lives in `kv('state','state')`).
- **Permissions checklist accuracy** (as opposed to its refresh, F5): with notifications revoked
  and `SCHEDULE_EXACT_ALARM` set to `ignore`, a freshly-entered Settings shows `✗ Notifications` /
  `✗ Exact alarms`; with both restored it shows `✓ / ✓`. Battery-optimisation exemption correctly
  reads `✗` on this emulator. Each ✗ row carries its action button and the ✓ rows carry none.
- **Today's counts match the DB.** After a test ping (expired), a suppressed ping, an unobserved
  ping and a skipped ping, Home read `Fixed times 2 / 0 / 1` against sample rows
  `18:55:25 expired (test)`, `19:00 suppressed`, `19:20 suppressed`, `19:40 unobserved`,
  `20:00 skipped` — fired = the 2 observed rows, expired = 1, answered = 0. Suppressed and
  unobserved rows correctly do *not* count as fired.
- **Backlog excludes skipped**: after skipping the 13:00 ping the backlog link stayed at 2 (the
  expired test ping + the unobserved 12:40 one) — the skipped sample is not re-surfaced.
  (The "expired ping(s)" wording of that link is already reported as C3 F2; not re-reported.)
- Settings shows device id (`emu-pes`), the configured timezone, an editable device name, the
  Drive section ("Not connected" / "Last successful sync: never" / "Connect Google Drive…"),
  "Sync now", and the restore entry point; the restore confirmation dialog text matches §8.6.

## Method notes for the next charter

- **The "for H:MM" auto-revert could not be verified end-to-end on Android.** The UI cannot set a
  timed quiet (F2), and the synthetic route — editing `kv('state','state')` directly and pushing
  the DB — is *not faithful*: the engine's backfill classifier reads the `quiet_changed` **event
  history** (`Engine.kt:343`), not just the kv row, so a kv-only edit leaves the old
  `quiet_until: "indefinite"` event in place and later pings get classified `suppressed` even
  after the kv end time has passed. I initially took that for an auto-revert bug; it is an
  artifact of the unfaithful seed. If you need a timed quiet on the device, write a matching
  `quiet_changed` event into `events` as well as the kv row.
- Pushing a hand-edited DB works: pull with `emu.sh db`, edit `.emu/pes.db` with python sqlite3
  (the `-wal` is replayed on open), `PRAGMA wal_checkpoint(TRUNCATE)`, then
  `emu_seed.push('<file>', 'android/tools/emu.sh')` — it force-stops, replaces the file, fixes
  the owner/selinux context, re-grants POST_NOTIFICATIONS and the exact-alarm appop, and
  relaunches.
- Home's layout shifts vertically depending on whether an active card and a backlog link are
  present, so cached tap coordinates for the footer links go stale — re-dump `emu.sh ui` before
  every tap. A footer tap that lands on nothing followed by BACK exits the app to the launcher.
- The device was left at 2026-09-01 13:0x PDT with quiet off, both permissions granted, battery
  optimisation not exempt, and the seed DB described at the top of this file.
