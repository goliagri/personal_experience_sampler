# Tier 3 — exploratory charters (Android, headless emulator)

Tier 1 (`android/tests/device/`) and Tier 2 (`android/app/src/androidTest/`) check behaviours we
already thought of. Tier 3 is the opposite: a timeboxed agent *uses* the app on the emulator,
looking for what nobody wrote a test for. Its output is not a pass/fail run — it is a findings
file, and every confirmed finding is then converted into a Tier 1 or Tier 2 test so it can never
regress silently.

## Ground rules

- **One emulator, one consumer.** Never run two charters, or a charter and `emu.sh dtest`/`ctest`,
  at the same time. Charters run sequentially.
- **Never touch the owner's data.** Seed with `android/tools/emu.sh seed` (device id `emu-pes`).
- **`adb` is not on PATH** — everything goes through `android/tools/emu.sh`
  (`start shot ui uixml tap type key log alarms notifs shade doze settime db reboot root seed`).
- **Look at the screen.** The emulator is headless; `emu.sh shot <name>` writes `.emu/<name>.png`,
  which the Read tool renders. Use screenshots for layout/legibility judgements and `emu.sh ui`
  for exact text and tap targets. A finding about appearance needs a screenshot as evidence.
- **Read state, don't guess it.** `emu.sh db` pulls the SQLite file; check events/samples with the
  desktop package (`desktop/pes/store/db.py`, and `pes.engine` as a cross-implementation oracle).
- **Timebox.** Roughly 40 tool calls per charter. Depth beats coverage: one well-evidenced finding
  is worth more than five "looks fine" notes.
- **Report, don't fix.** A charter agent does not edit `android/app` or the engines. Findings are
  triaged afterwards; fixes and tests are a separate step.

## Oracles, in priority order

1. `PROJECT_PREFERENCES.md` — the owner's intent. §2 "Missed / skipped / late pings" and
   "answering experience fast and frictionless above almost everything else" are the load-bearing ones.
2. `SPECIFICATION.md` §10 (client design), §6.5 (active window/snooze/expiry), §5.2 (fold rules).
3. The desktop client — the two clients are meant to be recognizably the same app, so a divergence
   is at minimum a question.
4. General design sense, stated explicitly as such and argued from the owner's priorities.

## Finding format

One entry per finding in `findings/<charter-id>.md`:

```
### F<n> — <one-line claim>
Severity: bug | divergence | papercut | question
Evidence: <screenshot paths, ui dump excerpts, db rows, log lines — enough to reproduce>
Repro: <numbered steps from a known seed>
Expected / why: <cite the preference, spec section, or the design argument>
Test: <which tier, and the assertion that would catch it>
```

Severity means: **bug** = violates spec or an invariant; **divergence** = the two clients disagree
and one of them is wrong; **papercut** = works but fights the owner's stated priorities;
**question** = intended behaviour is genuinely ambiguous and the owner should decide.

---

## C1 — The answer flow, as the owner will actually live in it

*Explore* the path from notification to submitted answer, *with* keyboard, scrolling and repeated
use, *to discover* friction the screen tests can't see.

The owner's top priority: "fast and frictionless above almost everything else … one page, scroll
down through fields, no long animations." Answer several pings in a row like a real user. Watch:
where does focus land, does the soft keyboard cover the field being typed into or the Submit
button, how many taps and scrolls does a full survey cost versus a quick one, does the tags field
get focus per §10.3, do tag suggestions save typing or get in the way, does IME "next" move between
fields, is Submit reachable without hunting. Count taps for a realistic answer and say whether that
number is defensible.

## C2 — Old sample vs. new sample

*Explore* every route that reaches an Answer screen *to discover* any state where a late sample
could be mistaken for a fresh one, or a fresh one for a late one.

This is a hard requirement (preferences §2, spec §10.4): late samples reachable only from Backlog
and History, never from Home's active card or a notification, banner + prominent original time.
Try: a notification left in the shade past expiry; a snoozed sample re-firing; two streams firing
minutes apart; answering from Backlog while a new ping arrives; History → answer late; the app
resumed hours after a ping. For each, capture what the screen says and whether the scheduled time
is unmissable.

## C3 — Honest accounting under adverse conditions

*Explore* process death, reboot, Doze, clock changes and quiet mode *to discover* generated pings
that end up with no row, the wrong status, or a duplicate.

Invariants: every generated ping gets exactly one row; precedence retracted > answered > skipped >
expired > suppressed > unobserved > pending; a filled sample is never overwritten by an unfilled
one; latency always measured from the original scheduled time. Use `kill()`-style root kills (not
`am force-stop`, which cancels alarms), `emu.sh doze`, `emu.sh settime` jumps forwards and
backwards, quiet mode on/off across a ping, and airplane mode. After each, pull the DB and fold it
with the desktop engine; any disagreement is a finding.

## C4 — Configuration surfaces and the "no peeking" rule

*Explore* Streams and Settings *to discover* wrong, stale or unreachable state.

Upcoming ping times must stay hidden unless the user deliberately asks (preferences §2 "schedule
hidden by default"; spec §10.2 reveals next 48 h behind an explicit action). Check the streams list
against the config, "Fire test ping now" (a real sample, `test: true`, normal answer path), quiet
mode "until turned off" vs "for H:MM" including the auto-revert, the permissions checklist against
the actual granted permissions (revoke each and re-read), the ping calendar, and today's
fired/answered/expired counts. Android's Streams/Surveys screens are read-only by design for now —
note anything that *looks* editable but isn't, or state that goes stale without a manual refresh.

## C5 — Failure and degradation

*Explore* the app with the network, permissions and cloud unavailable or broken *to discover*
silent failures and dead ends.

Local-first is an invariant: the ping/answer path must work with no network at all. Sync is
best-effort and its failures must be *visible* (there is a sync status line on Home and errors in
Settings). Try: airplane mode, sync with no Drive account connected, exact alarms revoked mid-flight,
notifications revoked, the DB made read-only, a config version from the future, an unknown stream id
in a notification. Real Drive sign-in is blocked on the OAuth client registration for the debug
SHA-1 — treat "cannot connect" paths as in scope and actual Drive round-trips as out.

## C6 — Presentation: themes, font scale, rotation, junk input

*Explore* the app under display settings and inputs a real phone will produce *to discover*
truncation, unreadable contrast, layout collapse or crashes.

Spec §10.1 requires dark and light themes and a shared status palette. Exercise
`settings put secure ui_night_mode`, large font scale (`settings put system font_scale 1.5`),
display size, landscape rotation, and long/unicode/emoji tag and note input. Also: rotation or
process death mid-answer — is typed-but-unsubmitted input lost, and is that acceptable?

---

## Converting findings

After a charter: triage each finding, fix what is a real defect (engine fixes must be mirrored in
`desktop/pes` and `android/runtime`), and add the test named in the finding's `Test:` line. Add the
charter's durable checks to `TEST_PLAN.md` §2b. A finding marked **question** goes to the owner
rather than into a test.
