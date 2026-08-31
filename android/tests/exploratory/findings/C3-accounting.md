# C3 — Honest accounting under adverse conditions

Charter run: 2026-08-30 (session), headless emulator (Android 15, 1080x2400), debug APK already
installed, no rebuild. One isolated dev DB (`emu_seed.Seed`, device `emu-pes`, survey `dev` v1):

```
Seed(now_iso="2026-09-02T18:55:00Z", poisson=False, quiet_zone=False,
     fixed=["12:00","12:10","12:20","12:30","12:40","12:50","13:00"],  # America/Los_Angeles
     expiry=5, snooze=2)          # plus the always-present disabled stream `off`
```

Seven known ping instants per local day and a 5-minute active window, so every adverse condition
below could be aimed at one specific ping. Timeline actually driven (device clock, local PDT):

| # | condition | ping | outcome |
|---|---|---|---|
| S1 | `kill -9` the app, then clock to 12:00:05 | 12:00 | fired, notified |
| S2 | `emu.sh reboot` (guest clock resets to 2026-08-30), re-pin to 12:09:30 | 12:10 | alarm re-armed, fired |
| S3 | deep Doze (`deviceidle force-idle`, state IDLE) across the ping | 12:20 | fired through Doze |
| S4 | clock jump 12:20:05 → 12:45:00 (two whole windows) | 12:30, 12:40 | both `unobserved` |
| S5 | clock jump **backwards** 12:45 → 12:35, then forwards to 12:40:05 | 12:40 | **F1** |
| S6 | quiet mode toggled on across the ping | 12:50 | `suppressed`, reason `quiet_mode` |
| S7 | answer the ping, then jump the clock past its expiry | 13:00 | stays `answered`, latency 38 s |
| S8 | `kill -9`, then clock jump a whole day (13:20 → next day 14:00) | 7 pings on 09-03 | all `unobserved` |

Final state: 14 sample rows, 14 generated pings, zero rows for the disabled stream. Every pull was
`su root cat …/pes.sqlite` + `-wal`; the folded rows were re-derived with the desktop core
(`pes.core.fold.fold_sample`) on every check — `refold mismatches: none` at each checkpoint.

---

### F1 — A backward clock jump re-opens the active window of a ping that was already recorded `unobserved`, so one generated ping gets two mutually exclusive terminal classifications
Severity: bug (engine-level; reproduced identically on the desktop engine, so both clients are wrong the same way)

Evidence: `fixed|2026-09-02T19:40:00Z`, pulled from the phone's DB, has three lifecycle events:

```
2026-09-02T19:45:00Z unobserved fixed|2026-09-02T19:40:00Z {'config_v': 2, 'stream': 'fixed'}
2026-09-02T19:40:05Z fired      fixed|2026-09-02T19:40:00Z {'config_v': 2, 'scheduled': '2026-09-02T19:40:00Z', 'test': False}
2026-09-02T19:48:00Z expired    fixed|2026-09-02T19:40:00Z {'config_v': 2, 'stream': 'fixed'}
```

(note the `fired` timestamp is *earlier* than the `unobserved` one but appended after it — the log
is append-only per device, so the file order and the `t` order disagree.)

Intermediate folded row, immediately after the re-fire:

```json
{"sample":"fixed|2026-09-02T19:40:00Z","status":"unobserved","observed":true,
 "late":false,"latency_s":null,"snoozes":0,"test":false}
```

— `status: unobserved` ("no device was running") together with `observed: true` ("this device
showed it to the user"). Home counted it: `.emu/c3-home-counts.png` reads
`Fixed times   4 / 0 / 3` (fired/answered/expired) at 12:42 when only three pings had ever been
`unobserved`-free, and `MainActivity.kt:91` computes `fired = rows.count { it.bool("observed") }`.
Final row after the window closed again: `status: expired, observed: true`. So the same generated
ping is recorded both as *nothing was running for it* and as *it was shown and the user ignored it*.

Reproduced on the desktop engine with the same seed and the same instants (no device involved):

```
$ cd desktop && python3 - <<'EOF'   # seed as above; c.set(t); e.start(); e.tick() at each t
  t in ["2026-09-02T19:45:00Z", "2026-09-02T19:35:00Z", "2026-09-02T19:40:05Z"]
EOF
fixed|2026-09-02T19:40:00Z unobserved observed= True
events 19:40: ['unobserved@2026-09-02T19:45:00Z', 'fired@2026-09-02T19:40:05Z']
notifications: shown: [('fixed|2026-09-02T19:40:00Z', 'Fixed times', 'Ping at 12:40 - answer now')]
```

Root cause is visible in `desktop/pes/engine.py` and mirrored in
`android/runtime/src/main/kotlin/pes/Engine.kt:279`. `materialize()` marks a schedule row `done`
only for

```python
types & {"fired", "answered", "skipped", "expired", "retracted", "suppressed"}
```

— **`unobserved` is not in that set**, so an already-unobserved sample is re-materialized as
`planned` forever. `_handle_due` then guards only against a *duplicate unobserved*
(`if "unobserved" not in types`) and falls through to the `else:` branch, which fires and notifies,
whenever `scheduled + expiry > now` — which a backward clock jump makes true again.

Two knock-ons observed from the same cause:
- A permanently-`planned` unobserved sample is a permanent `next_planned` candidate, so `_next_wake`
  keeps arming an exact alarm for a ping that is already accounted for. Measured after the backward
  jump: the only armed alarm was `origWhen=2026-09-02 12:40:00.000` (a sample recorded `unobserved`
  five minutes earlier) instead of the next real ping at 12:50. Preferences §2 / CLAUDE.md:
  "Android must not burn battery: exact alarms only".
- The re-fired sample is `unobserved`, not `pending`, so `_expire_pending` (which iterates
  `sample_rows(statuses=["pending"])`) can never close it out and never calls `notifier.cancel`.
  On desktop the `RecordingNotifier` log confirms the notification is shown and never cancelled.
  On the phone I could not confirm a lingering notification — `emu.sh notifs` reported zero pes.app
  notifications 8 s after the re-fire — so the *user-visible* half of this differs between clients
  and needs a second look; the *data* half is identical in both.

Repro (from the seed at the top):
1. `emu.sh settime '2026-09-02 11:55:00'`; push the seed DB.
2. Let 12:20 fire, then `emu.sh settime '2026-09-02 12:45:00'` — 12:30 and 12:40 are recorded
   `unobserved` (correct).
3. `emu.sh settime '2026-09-02 12:35:00'` (backwards). Pull the DB: no new events, refold clean —
   still correct. But the armed alarm is 12:40, not 12:50.
4. `emu.sh settime '2026-09-02 12:40:05'`. Pull the DB (`pes.sqlite` **and** `-wal`):
   `fixed|2026-09-02T19:40:00Z` now carries a `fired` event and `observed: true`.

Expected / why: CLAUDE.md's honest-accounting invariant — "every generated ping gets a row …
suppressed, unobserved, skipped and expired are all recorded" — reads naturally as *one*
classification per ping; a row that is simultaneously `unobserved` and `observed: true` is not an
honest record of anything, and it corrupts the only cadence statistic the clients are allowed to
compute (preferences §2: "no survey-content analysis in the clients — only cadence/response
stats"). Spec §6.4 makes `unobserved` a backfill *classification* of a window that has closed;
nothing in §6.5 re-opens a closed window. `unobserved` belongs in `materialize()`'s done-set, and
`_handle_due` should treat any prior classification (not just `unobserved`) as terminal for firing.
Backward clock jumps are not exotic: a manual timezone/clock correction, a phone whose RTC drifted
and is pulled back by NTP, or DST handling on a device with `auto_time` off all produce one.

Test: shared scenario test in both suites (`desktop/tests/scenarios/` and the mirrored
`android/runtime/src/test/kotlin/pes/scenarios/`), since the defect is in the shared engine layer:
drive one device forward past a ping's window, assert `unobserved`, jump the clock back inside the
window, tick, and assert (a) no `fired` event is appended, (b) the row is still
`status == "unobserved", observed == false`, (c) `nextWake` is not the already-classified sample's
instant. Tier 1 device companion: the `emu.sh settime` sequence in the repro, asserting
`events_for_sample` has exactly one event.

---

### F2 — Home calls the whole backlog "expired ping(s)", collapsing the distinction between expired and unobserved that the accounting model exists to preserve
Severity: papercut

Evidence: `.emu/c3-home-counts.png` — `Backlog: 5 expired ping(s)` at a moment when the DB held
three `expired` rows (`fixed|2026-09-02T19:00:00Z`, `…19:10:00Z`, `…19:20:00Z`) and two
`unobserved` rows (`…19:30:00Z`, `…19:40:00Z`). `MainActivity.kt:222`:
`Text("Backlog: ${d.backlogCount} expired ping(s)")`, while `Engine.backlog()` deliberately returns
`expired`, `unobserved` **and** stale `pending`.

Repro: any of S4 above, then look at Home.

Expected / why: this is only a label, hence a papercut, but it is a label on the exact distinction
CLAUDE.md calls out ("suppressed" ≠ "off", and unobserved = "no device was running" ≠ expired =
"you were shown it and let it lapse"). Preferences §2 wants the owner able to tell a ping they
missed from a ping their phone never presented. The Backlog screen itself gets this right — it
shows the per-row status — so only the entry point lies. "Backlog: N ping(s)" would be enough.

Test: Tier 2. Fold a mix of `expired` and `unobserved` samples and assert Home's backlog label does
not claim they are all expired (or asserts the exact counted breakdown, if the owner wants one).

---

### F3 — After a reboot that restores a stale clock, the app materializes a 48 h horizon around the wrong instant and is left with no armed alarm until a TIME_SET arrives
Severity: question

Evidence: `emu.sh reboot` at device time 2026-09-02 12:06 brought the guest back at
`Sun Aug 30 20:28:40 PDT 2026` (three days in the past — the emulator resyncs the guest RTC to the
host on boot). The app started on `BOOT_COMPLETED` (pid 2776) and wrote:

```
sync_meta last_materialized_at = 2026-09-02T19:01:00Z     <- correctly NOT rolled back
sync_meta last_tick            = 1788146926               (= 2026-08-31T03:28:46Z)
sync_meta materialized_until   = 2026-09-02T03:28:46Z     <- horizon around the wrong now
```

The config's `effective_from` is `2026-09-02T18:55:00Z`, i.e. *after* that entire horizon, so the
rebuilt schedule contained no rows at all and no future ping could be armed. Recovery was complete
and correct the moment the clock was fixed: `emu.sh settime '2026-09-02 12:09:30'` →
`origWhen=2026-09-02 12:10:00.000` armed → the 12:10 ping fired normally. Crucially, **no bogus
events were emitted**: `backfill_now()` returns early because the watermark (19:01:00Z) is already
ahead of the bad `now`, so nothing in the past was misclassified.

Expected / why: recording this as a question, not a defect, because the app behaved defensibly and
recovered on `TIME_SET`, and a real phone gets its clock back from NITZ/NTP within seconds of boot,
which fires exactly that broadcast. The open question for the owner is whether the app should be
able to *notice* that its watermark is in the future — a clear "my clock is wrong" signal — and
refuse to overwrite `materialized_until`/`materialized_day` with a horizon it knows is stale,
rather than depending on a later broadcast to repair it. A phone that boots with a dead-battery RTC
and no SIM/network would sit with an empty schedule indefinitely.

Test: none until the owner decides. If they want the guard: a shared scenario test starting the
engine with `clock.now() < parse_utc(last_materialized_at)` and asserting the previous
`materialized_until` is retained.

---

## Checked and fine

Each item names the invariant, what was actually done to it, and the row/event evidence.

- **Every generated ping gets exactly one row.** 14 pings were generated across the run (7 fixed
  times on 2026-09-02 + 7 on 2026-09-03); the phone's `samples` table holds exactly 14 rows, and
  the re-fold check (`fold_sample` over `events_for_sample`, expiry 5) reported
  `refold mismatches: none`, `samples with no events: []`, `events with no sample row: []` at four
  separate checkpoints (12:35, 12:40:05, 13:20, next-day 14:00). Final status census:
  1 answered / 4 expired / 1 suppressed / 8 unobserved. The only ping that got two *events* of
  different classes is F1's.
- **Process death across a ping (`kill -9`, not `force-stop`).** App killed at 11:57 (`pidof`
  empty), clock jumped to 12:00:05; the exact alarm survived, restarted the process (pid 11702) and
  posted `android.title=String (Fixed times)` /
  `android.text=String (Ping at 12:00 - answer now)`. Row `fixed|2026-09-02T19:00:00Z`: one `fired`
  at `19:00:05Z`, then one `expired` at `19:06:00Z` when the window closed. No duplicate `fired`.
- **Reboot across a ping.** Full `emu.sh reboot`; after the clock was re-pinned the alarm was
  re-armed (`origWhen=2026-09-02 12:10:00.000 window=0 exactAllowReason=permission`) and
  `fixed|2026-09-02T19:10:00Z` fired at `19:10:03Z` and expired at `19:16:00Z` — exactly one of
  each. See F3 for the stale-clock window in between.
- **Doze.** `dumpsys deviceidle force-idle` → `get deep` = `IDLE` before and after; the 12:20 ping
  still fired while the device was in deep idle (`android.text=String (Ping at 12:20 - answer now)`,
  `fired@2026-09-02T19:20:05Z`). `setExactAndAllowWhileIdle` is doing its job; no polling and no
  foreground service were needed.
- **A forward clock jump over several whole windows classifies, it does not fire stale.** Jump
  12:20:05 → 12:45:00 covered the 12:20 expiry plus two entire windows. Result: `…19:20:00Z`
  `expired` (event at `19:45:00Z`), `…19:30:00Z` and `…19:40:00Z` `unobserved` (both at
  `19:45:00Z`), zero notifications posted (`emu.sh notifs` count 0). The 12:40 case is the exact
  boundary `scheduled + expiry <= now` (12:45 ≤ 12:45) and was classified, not fired.
- **Two pings inside one alarm window** (12:30 and 12:40 in the single 12:20→12:45 jump) each got
  their own row and their own event; neither was dropped or merged.
- **A backward clock jump on its own creates nothing.** 12:45 → 12:35: no new events, no
  notification, `refold mismatches: none`, and no already-classified sample changed status. (The
  damage in F1 needs the subsequent *forward* re-crossing.)
- **Re-crossing an instant does not duplicate events.** After the 12:35 → 12:40:05 replay,
  `fixed|2026-09-02T19:40:00Z` still had exactly one `unobserved` event (F1 is about the extra
  `fired`, not a duplicate classification), and the duplicate scan over every sample for repeated
  `fired`/`expired`/`unobserved`/`suppressed`/`skipped` events found none anywhere in the DB.
- **Quiet mode suppresses and still records.** Toggled on from Home ("Quiet: on (tap to turn off)")
  at 12:48, clock to 12:50:05: `emu.sh notifs` count 0, and
  `fixed|2026-09-02T19:50:00Z` → `suppressed@2026-09-02T19:50:05Z {'reason': 'quiet_mode'}`,
  folded `status: suppressed, observed: false`. The ping was logged, not lost.
- **"Suppressed" ≠ "off".** The seed's disabled stream `off` (fixed_interval 60 min, `enabled:
  false`) produced **zero** sample rows and zero events over 26 simulated hours
  (`grep -c '"stream": "off"'` → 0), while the quiet-suppressed ping above did get a row. That is
  the distinction CLAUDE.md requires, on the same DB at the same time.
- **A filled-out sample is never overwritten by an unfilled one.** `fixed|2026-09-02T20:00:00Z` was
  answered in the app at 13:00:38 (tag `c3answer`), then the clock was jumped to 13:20 — 15 minutes
  past its 5-minute expiry, across a materialization and a backfill. The row is unchanged:
  `{"status":"answered","observed":true,"late":false,"latency_s":38,"answered_at":"2026-09-02T20:00:38Z"}`
  and its event list is still exactly `fired@20:00:05Z, answered@20:00:38Z` — no `expired` event was
  appended. Precedence `answered > expired` held both in the log and in the fold.
- **Latency is measured from the original scheduled time.** That answer: scheduled `20:00:00Z`,
  submitted `20:00:38Z`, `latency_s: 38`. (Latency across a snooze and an expiry was measured in C2
  — `latency_s: 1223` from the original instant — and is not re-tested here.)
- **A device "off" for a whole day backfills honestly.** App `kill -9`ed at 13:20 on 09-02, clock
  jumped to 14:00 on 09-03 (24h40m, skipping all seven of the next day's pings). The alarm restarted
  the app (pid 4199) and backfill wrote exactly seven `unobserved` events, all stamped
  `2026-09-03T21:00:00Z`, one per scheduled instant `19:00/19:10/19:20/19:30/19:40/19:50/20:00Z`.
  No ping was silently dropped, none fired stale, `last_materialized_at` advanced to
  `2026-09-03T20:55:00Z` (= now − max expiry, per §6.4).
- **Schedules are deterministic across all of the above.** The 14 sample ids on the phone are
  exactly the `fixed_times` instants of the configured timezone for the two local days, after a
  reboot, a Doze, a 25-minute forward jump, a 10-minute backward jump, a 24-hour forward jump and
  two process kills. The desktop engine, given the same seed DB and instants, produced the same
  sample ids and the same statuses (the only divergence being F1, which it reproduces identically).
- **The fold on the phone equals the desktop core's fold of the phone's own events**, field by
  field (status, observed, late, latency_s, snoozes, test, answered_at, fired_on, answered_on), at
  every checkpoint.

## Notes for whoever runs the next charter

- The emulator's guest clock is reset to **host** time by `emu.sh reboot` (host is ~3 days behind
  the scenario dates used here). Always `settime` immediately after a reboot, and expect the app to
  have materialized a garbage horizon in between (F3).
- Repeated `settime` jumps eventually ANR the Pixel Launcher; a `Pixel Launcher isn't responding`
  dialog then sits on top of the app and `emu.sh ui` shows only that dialog. Tap "Close app"
  (`android:id/aerr_close`) and re-`am start` the activity.
- `emu.sh db` still returns only the main file for some verbs; pull `pes.sqlite` **and**
  `pes.sqlite-wal` (the app never checkpoints) or you will read a stale/empty database. The helper
  used for this run did `su root cat` on both.

## Not covered by this charter

Airplane mode / network loss (C5's charter — the ping path never touches the network by
construction, but it was not exercised here). Quiet *zones* declared in the stream config, as
opposed to the global quiet *mode* — the seed used here had `quiet_zone=False` and no Poisson
stream, so `suppressed_reason: quiet_zone` was never produced on the device. Retraction and
supersede chains (precedence `retracted > answered`). Multi-device / sync-time reconciliation of
the same sample. Snooze interacting with a clock jump (snooze `until` in the past after a backward
jump) — a promising follow-up given F1's shape.
