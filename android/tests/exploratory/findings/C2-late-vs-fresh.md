# C2 — Old sample vs. new sample

Charter run: 2026-08-30 (session), headless emulator (Android 15, 1080x2400), debug APK already
installed. Two isolated dev DBs were seeded during the run with `emu_seed.build_db/push`
(device `emu-pes`, survey `dev` v1, no Poisson stream so ping times are exactly known):

| seed | streams | expiry / snooze | used for |
|---|---|---|---|
| A | `fixed` 11:58, `second` 12:01 | 15 min→5 / 10 | History-on-pending, banner staleness |
| B | `fixed` 13:00, `second` 13:20 | 15 / 2 | snooze re-fire, backlog answer with a new ping mid-form |

Samples driven end to end: `fixed|2026-09-01T18:58:00Z` (History → "Answer late", 57 s after it
fired), `second|2026-09-01T19:01:00Z` (notification → form left open across expiry),
`fixed|2026-09-01T20:00:00Z` (snoozed from the shade, re-fired, expired, answered from Backlog).

---

### F1 — History offers "Answer late" on a still-active ping, and that screen shows the red LATE banner for a sample that fired seconds ago and is recorded `late: false`
Severity: bug (also a divergence — desktop does not do this)

Evidence: `.emu/c2-history-answerlate.png`. Seed A, ping fired at 11:58:05, opened from
History at 11:58:1x. `emu.sh ui` on the History screen, while the sample was the *active* ping
shown on Home's card:

```
text="2026-09-01 11:58  Fixed times"   bounds="[42,593][647,656]"
text="pending"                          bounds="[42,656][159,698]"
text="Answer late"                      bounds="[812,630][1006,683]"
```

and on the screen it pushes:

```
text="Scheduled 2026-09-01 11:58"
text="LATE — originally 2026-09-01 11:58, 0 min ago. This answer will be marked late."
```

Snooze and Skip are absent from that screen (the `!d.late && !fromBacklog` guard), so the two
actions the spec gives you at a live ping are unreachable by this route. Submitting produced

```json
{"sample":"fixed|2026-09-01T18:58:00Z","status":"answered","late":false,"latency_s":57,
 "answered_at":"2026-09-01T18:58:57Z","snoozes":0}
```

— i.e. the banner's promise ("will be marked late") is false, and the fold is right while the UI
is wrong.

Cause: `HistoryScreen` (Screens.kt) pushes `Screen.Answer(sample, fromBacklog = true)` for every
row whose status is in `expired/unobserved/skipped/pending`, and `AnswerScreen` renders the banner
on `d.late || fromBacklog`. `pending` includes the sample that is currently active. The desktop
client decides purely on the clock (`ui/answer.py:274` `self.late = now > scheduled + expiry`) and
has no `from_backlog` override, so the same action on desktop correctly shows no banner and keeps
Snooze/Skip.

Repro:
1. `emu_seed` with `poisson=False, fixed=["11:58"], expiry=15`; `emu.sh settime '2026-09-01 11:50:00'`.
2. `emu.sh settime '2026-09-01 11:58:05'` → the ping fires; Home shows the active card.
3. Home → History → the top row is `2026-09-01 11:58 … pending` with an "Answer late" button. Tap it.
4. Red LATE banner on a ping that is 10 seconds old; Snooze/Skip gone. Submit → `late: false`.

Expected / why: preferences §2 requires the UI to make it impossible to mistake an old sample for
a new one; the symmetric error is just as bad here, because the banner is the owner's only signal
and this teaches them it can cry wolf. Spec §10.4 scopes the banner to *late* samples, and §6.5
defines late as "past expiry" — the same predicate the fold uses for the `late` column. The
banner and the recorded flag must be the same predicate. Either History must not offer
"Answer late" for a sample that is still active (route it to the normal answer screen), or
`fromBacklog` must stop forcing the banner and the screen must decide on the clock alone.

Test: Tier 2 (`androidTest`, Compose). Render `AnswerScreen` for a sample whose
`scheduled + expiry > now` with `fromBacklog = true` and assert no node containing "LATE" exists,
and that Snooze/Skip are displayed. Tier 1 companion: after firing a ping, assert the History row
for a `pending` non-expired sample exposes no "Answer late" button.

---

### F2 — The LATE banner is computed once when the screen opens and never re-evaluated: a form opened while the ping was live and submitted after expiry is recorded `late: true` with no banner ever shown
Severity: bug

Evidence: `.emu/c2-open-past-expiry.png`. Seed A, `second|2026-09-01T19:01:00Z`, expiry 5 min
(expires 12:06). Opened from the notification body at 12:01; clock moved to 12:09 with the form
still on screen. `emu.sh ui` at 12:09 — unchanged, no banner, both secondary actions still there:

```
text="Second stream"
text="Scheduled 2026-09-01 12:01"
text="Snooze"   bounds="[42,529][289,655]"
text="Skip"     bounds="[310,529][508,655]"
```

`emu.sh notifs` at that moment: 0 pes.app notifications — the engine *had* expired the sample and
cancelled its notification, and the screen said nothing. Events and the folded row after submit:

```
2026-09-01T19:01:05Z fired    second|2026-09-01T19:01:00Z
2026-09-01T19:09:00Z expired  second|2026-09-01T19:01:00Z
2026-09-01T19:09:34Z answered second|2026-09-01T19:01:00Z
{"sample":"second|...","status":"answered","late":true,"latency_s":514}
```

So the sample silently moved to the backlog under the user, and the answer they submitted from a
screen with no banner is stored as late. Two knock-ons observed on the same screen:
- Tapping "Snooze" on the now-expired sample is correctly refused, but the message is
  "Snooze refused: too close to expiry" (`snoozeRefusalText("near_expiry")`) for a ping that is
  four minutes *past* expiry — see F4.
- "Skip" is still offered. Skipping there would append a `skipped` event that the fold discards
  (precedence `expired > skipped`), so the button would appear to work and change nothing.

This is not Android-specific: `desktop/pes/ui/answer.py:274` computes `self.late` once in
`__init__` as well, so the desktop answer window has the same blind spot. Fixing it should be
mirrored.

Repro:
1. Seed with `expiry=5`, fire a ping, open it from the notification (no banner — correct).
2. `emu.sh settime` past `scheduled + expiry` while the form stays open; `emu.sh shot`.
3. Type a tag, Submit. `emu.sh db` (+ `-wal`) → the row has `late: true`, and no banner was ever
   displayed.

Expected / why: preferences §2 — "the UI makes it impossible to mistake an old sample for a new
one"; this is precisely a sample that stopped being new while the user was looking at it. Spec
§6.5 moves the sample to the backlog at the expiry instant, and the answer screen is the one place
the user learns what they are answering. The banner (and the Snooze/Skip row) should be driven by
a clock that ticks, or at least re-evaluated on resume and immediately before the submit is
written. Realistic trigger: the owner opens a ping, gets interrupted, comes back 20 minutes later
and submits — no signal at all that the sample is now filed as late.

Test: Tier 2. Render `AnswerScreen` with a `FakeClock`, advance it past `scheduled + expiry` while
composed, and assert the LATE banner appears and Snooze/Skip disappear without leaving the screen.
Mirror in the desktop suite (`tests/scenarios`) if the fix lands there too.

---

### F3 — A ping notification that outlives its sample still reads "Ping at HH:MM — answer now" and is indistinguishable from a live ping in the shade
Severity: bug (precondition: the app lost its alarms — force stop / OEM battery manager)

Evidence: `.emu/c2-shade-stale.png`, taken at 12:10 on 2026-09-01 with a live ping and a two-day-old
one side by side:

```
Fixed times · now        Ping at 12:10 - answer now     [Reply tags] [Snooze] [Skip]
Daytime (Poisson) · 2d   Ping at 20:03 - answer now
```

The stale one is for a sample that no longer exists in the DB at all (`day|…` was left over from
the previous charter and the DB has since been re-seeded twice). Nothing in the app's own text
distinguishes it: same title style, same "answer now" body, the only difference is Android's own
"2d" timestamp chip. `emu.sh notifs` confirms it was a `pes.app` notification on channel `pings`.

Normally this cannot happen: `Engine.expirePending` calls `notifier.cancel` and `nextWake`
includes every pending sample's expiry instant, so a running app with its exact alarm intact
cancels the notification on time (verified — see "Checked and fine"). It happens when the alarm is
lost: `am force-stop` (which `emu_seed.push` does), the user's own Force stop, or a Samsung-style
battery manager putting the app in the stopped state — the owner's phone is a Galaxy
(preferences §2 "Other decisions"). The notification survives; the app is not running to cancel it.

Uncertainty, stated plainly: I tapped the stale notification's body once while the app was already
foregrounded on Streams. The shade closed, the app stayed on Streams, no Answer screen and no
"Can't answer this ping" screen appeared, and the notification was gone afterwards, so I could not
re-run it. I am reporting only what I saw; whether the intent was dropped (a plausible
`FLAG_ACTIVITY_NEW_TASK`-into-an-existing-task drop in `AndroidNotifier.notify`) or something else
happened needs a second look. Live notifications tapped from the same state *did* route correctly.

Repro (state, not the tap):
1. Fire any ping so its notification is posted.
2. `emu.sh shell 'am force-stop pes.app'` before its expiry instant (this is what `emu_seed.push`
   does; it is also what a battery manager does).
3. `emu.sh settime` past `scheduled + expiry_minutes`, then `emu.sh shade` — the notification is
   still there, still saying "answer now", with Snooze/Skip/Reply actions.

Expected / why: spec §10.4 is categorical — "late samples are reachable only from Backlog and
History, never from Home's active card or a notification". A notification that survives its
sample's expiry is exactly a notification leading to a late sample, and its inline "Reply tags"
action writes an `answered` event straight into a sample that is already `expired` (the fold
accepts it, `late: true`) without the owner ever seeing a banner. A defensive re-check in
`ActionReceiver` (refuse/redirect the action when `scheduled + expiry <= now` and say so), plus
cancelling stale ping notifications on app start, would close it.

Test: Tier 1 (device). Fire a ping, force-stop the app, jump the clock past expiry, relaunch, and
assert `emu.sh notifs` contains no `pes.app` ping notification. Second assertion: deliver a
`REPLY`/`SKIP` broadcast for an already-expired sample and assert no `answered` event is written by
that path (or that it is written with an explicit late acknowledgement, whichever the owner picks).

---

### F4 — "Snooze refused: too close to expiry" is shown for a sample that is already past expiry
Severity: papercut

Evidence: F2's screen at 12:09, four minutes after the 12:06 expiry:
`text="Snooze refused: too close to expiry"`. The engine's refusal code is correct
(`near_expiry`, §6.5), it is the wording that is wrong for the case the user is actually in — and
the message is the only hint on that screen that the sample is no longer live, so the one chance to
tell them "this ping expired" is spent on a misleading sentence.

Repro: open a ping, let it expire with the form open (F2 repro), tap Snooze.

Expected / why: `snoozeRefusalText` maps two distinct situations onto one string. If F2 is fixed
the case mostly disappears; if not, `Engine.snooze` should return a distinct `expired` refusal.
This is a wording judgement, not a spec violation.

Test: Tier 2/unit. `Engine.snooze` on a sample past `scheduled + expiry` returns a refusal distinct
from `near_expiry`, and `snoozeRefusalText` renders it as "This ping has expired…".

---

### F5 — Submitting a late answer from Backlog returns to the Backlog screen, so a ping that fired during the answer is never presented
Severity: papercut

Evidence: seed B. With the Backlog answer form for `fixed|2026-09-01T20:00:00Z` open and typed
into, the clock was moved to 13:20:05 and `second|2026-09-01T20:20:00Z` fired (`emu.sh notifs` →
`android.title=String (Second stream)`). The open form was untouched — banner, typed tag
`backlogtag` and all fields intact (`.emu/c2-backlog-newping.png`), which is right. On Submit the
app popped back to "Backlog / Backlog is empty."; the new live ping was only reachable by pressing
Back to Home, where the card was waiting ("Second stream — ping at 13:20").

Expected / why: spec §6.5 says "If a survey is open when another fires, the new one queues and is
presented after submit". Coming from Home's card, `onDone` pops to Home and the new card is right
there, so the spec sentence is satisfied on that route; coming from Backlog it is not. Low harm —
the notification is still in the shade — and no confusion between old and new, which is why this is
a papercut and not a bug. The safe fix is popping to Home (or to the newly active sample) rather
than to the list you came from.

Test: Tier 2. Submit from `Screen.Answer(fromBacklog = true)` while an active sample exists and
assert the resulting screen shows the active ping (Home), not the Backlog list.

---

## Checked and fine

Every route below was exercised in this run and behaved correctly.

- **Notification → Answer, fresh ping**: no banner, "Scheduled 2026-09-01 12:01" header, Snooze and
  Skip present. A hurried user cannot mistake it for a backlog item.
- **Home's active card never shows a late sample.** `Engine.activeSamples` filters on
  `observed && scheduled + expiry > now`, and Home showed "No active ping." plus
  "Backlog: 1 expired ping(s)" the moment the 13:00 ping expired. The card label carries the
  original time ("Fixed times — ping at 13:00"), including after a snooze.
- **Snooze re-fire (§6.5, preferences §2)**: snoozing `fixed|2026-09-01T20:00:00Z` from the shade
  cancelled the notification immediately; at the snooze instant it re-fired as
  `android.title=String (Fixed times (snoozed x1))` / `android.text=String (Ping at 13:00 - answer now)`
  — the snooze count is stated and the time shown is the **original**, never the snoozed one.
- **Latency is measured from the original scheduled time through a snooze and an expiry.** That
  sample was scheduled 20:00:00Z, snoozed at 20:00:19Z, re-fired 20:03:10Z, expired 20:16:00Z and
  answered 20:20:23Z → `latency_s: 1223` (= 20:20:23 − 20:00:00), `snoozes: 1`, `late: true`. Exactly
  one row, one `fired` per notification, no duplicates.
- **Backlog screen (§10.2)**: header "These pings have expired. Answers will be marked late.", the
  original date-time in `titleLarge` (`text="2026-09-01 13:00"`, `bounds="[42,721][486,795]"`), status
  under it, grouped by stream. `.emu/c2-backlog.png`.
- **Backlog → Answer**: banner "LATE — originally 2026-09-01 13:00, 16 min ago. This answer will be
  marked late.", no Snooze/Skip, and the scheduled time repeated next to Submit (§10.4). Recorded
  `late: true`.
- **A new ping firing mid-answer does not disturb the open form** and does not switch the screen
  under the user (§6.5): typed text, banner and field state all survived; only a notification
  appeared. Answering the old one had no effect on the new one, which stayed `pending`.
- **Two streams firing minutes apart are distinguishable**: separate notifications (one id per
  sample, `sampleId.hashCode()`), each titled with its own stream name and its own original time
  ("Fixed times / Ping at 11:58", "Second stream / Ping at 12:01"), and Home lists per-stream
  today-counts. Answering one left the other untouched.
- **Notification cancellation at expiry works when the app is alive**: `emu.sh notifs` returned no
  pes.app notification immediately after the expiry instant passed, on every run where alarms were
  intact (this is why F3 needs a force-stop to reproduce).
- **Snooze is refused past expiry** rather than resurrecting an expired sample (wording aside, F4),
  and a snooze whose `until` would land past expiry is refused too (`expiry 5 / snooze 10`: the
  notification stayed up and the sample expired on schedule).
- **History rows carry the full original date-time** (`2026-09-01 11:58  Fixed times`) plus a
  colour-coded status, and the status filter chips work; an `answered` row offers Retract, not
  "Answer late", so an answered sample cannot be silently re-answered from there.
- **Home's today counts move honestly** when a backlog item is answered late: "Fixed times 1 / 0 / 1"
  became "1 / 1 / 0".
- Confirms C1 F2/F3 (window panning under the status bar with the IME up; Submit needing an extra
  scroll after typing) — hit again on every answer in this run, not re-reported.

## Not covered by this charter

The stale-notification *tap* target (F3's uncertainty) deserves a dedicated run — including
tapping a ping notification while the app is already foregrounded on a non-Home screen, which is
the state where I saw nothing happen. Also untouched: retract → answer-late round trips, editing an
already-answered sample (Android History has no edit, only Retract, while desktop `history.py`
supports supersede+prefill — a divergence worth its own look), quiet-mode/suppressed samples in the
backlog, and multi-device late answers.
