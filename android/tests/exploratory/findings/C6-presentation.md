# C6 — Presentation: themes, font scale, rotation, junk input

Charter: explore the app under display settings and inputs a real phone will produce, to discover
truncation, unreadable contrast, layout collapse or crashes.

Build: the debug APK installed at the time of the run (post-C1-fix build — chip FlowRow, radio/checkbox
choice rendering, numeric keyboard and single-line tags are all present). Seed: `emu.sh seed`, device
`emu-pes`, one pending ping `2026-09-01 17:00` on "Daytime (Poisson)" (survey: tags / number Mood 1-7 /
single-choice Where / multi-choice With / text Note).

Screenshots referenced below are in `.emu/` at the repo root.

**Display settings restored at the end of the run** — `font_scale 1.0`, `user_rotation 0` +
`accelerometer_rotation 1`, `cmd uimode night no`, `wm density reset`, `wm size reset` (density and size
were never changed; verified afterwards: `font_scale=1.0`, `user_rotation=0`, `Physical size 1080x2400`,
`Physical density 420`).

---

### F1 — With the soft keyboard open, the Answer form collapses into a ~360 px strip and the fields below it stop being composed at all
Severity: bug (and a **failed fix** — this is the C1 F2/F3 area, and it is now worse, not fixed)

Evidence:
- `.emu/c6-answer-dark-kb.png` — tags field focused, keyboard up. Only Snooze / Skip / the tags box are
  drawn; Mood, Where, With, Note and Submit are gone and ~1100 px of blank black sits between the tags
  box and the keyboard.
- `.emu/c6-answer-ime-collapse.png` — after one upward swipe, the same strip now shows "Mood (1-7)",
  "Where" and the single radio "home", with the identical 1100 px of dead black below.
- `emu.sh ui`, keyboard **down**: content root `bounds="[0,275][1080,2337]"`, full form present down to
  Submit.
- `emu.sh ui`, keyboard **up**: content root `bounds="[0,275][1080,634]"` — 359 px tall — and the node
  list ends at the tags field (`[42,466][1038,634]`) plus a 31 px stub `[42,666][1038,697]`. Mood /
  Where / With / Note / Submit are **absent from the view tree**, not merely off-screen.
- Compare `.emu/c6-answer-dark-nokb.png` (keyboard down): the whole form including Submit fits on one
  screen with room to spare.

Repro:
1. Seeded state above, app in the foreground, the ping still pending.
2. Home → Answer (or tap the tags field on the Answer screen). The tags field takes focus per §10.3, so
   the keyboard comes up on its own — no user action is needed to reach this state.
3. Look at the screen: one field, then nothing.

Expected / why: the whole form must remain scrollable in the space above the IME. The strip is
scrollable, so nothing is unreachable in principle, but the user is answering a five-field survey
through a 15 %-of-screen letterbox while 45 % of the screen is blank. PROJECT_PREFERENCES §2 puts
"fast and frictionless above almost everything else" and spec §10.3 asks for "a single vertically
scrolling page"; this is neither. Note also that the header (stream name + "Scheduled …") scrolls out
of the strip immediately — for a *late* sample that is where the LATE banner lives (§10.4).

The C1 fix that stopped the window panning under the status bar (C1 F2) appears to have replaced pan
with resize without giving the scrolling column the remaining height; C1 F3 ("Submit hidden behind the
IME") is not fixed — Submit is now further away than it was.

Test: Tier 2 (Compose androidTest). Open AnswerScreen, request the IME on the tags field, assert every
field node (`Mood (1-7)`, `Where`, `With`, `Note`, `Submit`) still exists in the semantics tree, and
assert the scrolling container's height is within ~50 px of (window height − app bar − IME inset).
Tier 1 could assert the same from a `uiautomator` dump.

---

### F2 — In landscape with the keyboard open the Answer screen renders **nothing at all**: zero content nodes above the IME
Severity: bug

Evidence:
- `.emu/c6-answer-landscape-kb.png` — app bar, a black band, and the keyboard. No tags field, no
  Snooze/Skip, no Submit.
- `emu.sh ui` in that state: the entire dump is `decor_content_parent` / `content` / the action bar.
  There is not a single form node.
- `emu.sh ui` in landscape with the keyboard dismissed (`key ESCAPE`): the form is back —
  `What are you doing? (space-separated)`, `Mood (1-7)`, `Where`, `home`, `work`, `out`,
  `Somewhere else`, `With`. So this is IME-height-driven, not a landscape layout problem per se.
- `.emu/c6-answer-landscape.png` — landscape Home for comparison; Home itself is fine and scrolls.

Repro:
1. `emu.sh shell 'settings put system accelerometer_rotation 0; settings put system user_rotation 1'`.
2. Home → Answer. The tags field auto-focuses, the keyboard opens, the screen goes blank.

Expected / why: this is the same defect as F1 taken to its limit — in landscape the height left above
the IME is ~180 px, the column measures to ≤ 0, and nothing composes. In practice the answer flow is
**unusable in landscape on a phone**: the user cannot see or reach any field, and the only escape is
Back (which throws the answer away, see F6). A user who answers a ping while the phone is lying on a
desk in landscape gets a blank app. Local-first answering is the one path that must never fail
(PROJECT_PREFERENCES §2).

If landscape is deliberately out of scope, the honest fix is to lock the Answer activity to portrait —
but silently rendering an empty screen is not an acceptable way to express that.

Test: Tier 2, same assertion as F1's, parameterised over portrait and landscape; the landscape case
asserts at minimum that the tags field and Submit are present in the tree with the IME shown.

---

### F3 — Pressing the IME action key twice from the tags field silently checks the first option of the next choice field
Severity: bug (silent data corruption — an answer the user never gave)

Evidence:
- `.emu/c6-enter-selects-home.png` — the tags field contains only `x` (typed), nothing else was tapped,
  and "Where → home" is checked and highlighted.
- `emu.sh uixml` after the key presses:
  `class="android.view.View" … checkable="true" checked="true" … focused="true" bounds="[42,1129][1038,1203]"`
  — those are the bounds of the "home" row.
- The same thing happened accidentally earlier in the session (`.emu/c6-note-newlines.png`): a run of
  Enter presses aimed at a Note field left "home" selected.

Repro (from the seed):
1. Home → Answer.
2. Type any tag (`x`).
3. `input keyevent 66` three times (i.e. tap the keyboard's action key three times — it renders as
   "→|", the "next" arrow).
4. Dismiss the keyboard: "Where" now has "home" selected.

Expected / why: the tags field is correctly single-line now (C1 F4 is fixed — Enter no longer inserts a
newline), and IME "next" moving focus onward is the fix C1 F4 asked for. But focus traversal lands on a
radio row, and the *next* Enter activates the focused row. A user who taps the action key out of habit —
and the desktop client trains exactly that habit, "Enter submits when on the last field" (§12) —
records `where=home` without ever seeing the choice. Spec §5.2/§14 treat field values as the user's
answer; nothing in the app may write one on the user's behalf. It is also invisible: the selection is
below the fold while the keyboard is up (F1).

The right behaviour: IME "next" from the tags field should move focus to the next *text-entry* field
(Mood), not into a choice group, or choice rows should not take focus in the IME-next chain.

Test: Tier 2. Type into the tags field, `performImeAction()` twice, assert every choice field is still
unselected. Worth a matching Tier 1 assertion that the submitted `answered` event carries no `where` key.

---

### F4 — The shared status palette of §10.1 is not implemented: `answered` is purple, `pending` is not an accent, and skipped / unobserved / pending are the same grey
Severity: divergence (spec §10.1 is explicit, and the desktop client is the reference)

Evidence:
- `android/app/src/main/java/pes/app/Screens.kt:174`:
  ```kotlin
  private fun statusColor(status: String) = when (status) {
      "answered" -> MaterialTheme.colorScheme.primary
      "expired" -> MaterialTheme.colorScheme.tertiary
      "retracted", "suppressed" -> MaterialTheme.colorScheme.outline
      else -> MaterialTheme.colorScheme.onSurfaceVariant
  }
  ```
- `.emu/c6-history-dark.png` — the one row reads `pending` in plain grey, identical to what `skipped`
  and `unobserved` would render as.

Expected / why: §10.1 fixes the palette across both clients: "answered green, skipped gray, expired
amber, unobserved blue-gray, suppressed muted, pending accent, retracted struck-through". What is
implemented is: answered = theme primary (purple in both themes), expired = tertiary (pink-ish, not
amber), suppressed and retracted = outline, and **skipped, unobserved and pending collapse into one
colour**. Retracted is not struck-through anywhere. The status colour is the fastest way to read a
History or Backlog list, and C3 F2 already flagged that the expired/unobserved distinction is being
eroded in the UI — this is the same erosion in the colour channel. The two clients are supposed to be
recognizably the same app (charter oracle 3).

Test: Tier 2. A screen test over a list containing one sample of each status, asserting the colour of
each status label against a named palette (which should be a shared `StatusPalette` object rather than
seven `MaterialTheme.colorScheme` picks), plus that `retracted` carries `TextDecoration.LineThrough`.

---

### F5 — The ping calendar's four greens are hard-coded light-theme values; in dark mode the scale inverts, so the *worst* days are the brightest
Severity: papercut (part measurement, part design argument — argued below)

Evidence:
- `.emu/c6-home-dark.png` vs `.emu/c6-home-light.png` — the single day with data is the palest green
  (`0xFFC8E6C9`) in both. In light it is a faint tint on white; in dark it is the single brightest
  object on the screen.
- `android/app/src/main/java/pes/app/MainActivity.kt:451`:
  ```kotlin
  private val ANSWER_RATE_COLORS = listOf(
      Color(0xFFC8E6C9), Color(0xFF81C784), Color(0xFF4CAF50), Color(0xFF2E7D32),
  )
  ```
  These are the Material *light* green ramp and do not participate in the theme. The empty/no-ping cell
  next to them **is** theme-aware (`surfaceContainerHighest`).

Repro: `emu.sh shell 'cmd uimode night yes'`, open Home, look at the "Last 8 weeks" grid.

Expected / why (design argument, stated as such): a heatmap encodes magnitude as *ink* — in light mode
the darkest green is the strongest day because it contrasts most with white. In dark mode that ordering
flips: `0xFFC8E6C9` (a near-white green) reads loudest against `#111`, and the top bucket `0xFF2E7D32`
sits at roughly the same luminance as the empty-cell grey it is meant to be distinguished from, so a
100 %-answered day looks emptier than a 20 % day. The calendar exists to show cadence at a glance
(§10.2), so a scale that means the opposite thing in the two themes is a real defect, not taste. The
fix is a second ramp for dark (pale → saturated, e.g. `#1B3A22 → #A5D6A7`), mirrored on the desktop so
the clients still "read alike" — which is the comment's stated goal.

Caveat: the seeded DB has exactly one non-empty day, so I could not photograph all four buckets side by
side; the argument above is from the constants plus the one observed cell.

Test: Tier 2 unit-level — assert the calendar bucket colours are read from a theme-aware source and that
luminance is monotonic in the answer rate *in the direction of contrast against the surface*, in both
`lightColorScheme()` and `darkColorScheme()`.

---

### F6 — Every configuration change destroys the Answer screen and the answer typed into it, with no warning and no way back
Severity: papercut, with an embedded **question** for the owner

Evidence:
- Typed `typedbeforerotate` into the tags field, then
  `settings put system user_rotation 1` → `.emu/c6-answer-landscape.png`: the app is on **Home**, the
  ping still pending, the text gone.
- Same on a font-scale change: `settings put system font_scale 1.5` while on the Answer screen →
  `.emu/c6-answer-font15.png` is Home, not the form.
- Back also discards: typed a 74-char tag, `key BACK`, re-opened Answer → `emu.sh ui` shows the tags
  field empty, no "discard?" prompt.
- Source: `grep -n "rememberSaveable\|SavedStateHandle" AnswerScreen.kt MainActivity.kt` → no hits, and
  the manifest declares no `configChanges`/`screenOrientation`. `MainActivity`'s navigation `stack` and
  the form's `values`/`multi` maps are plain `remember`, so the activity recreate takes all of it.

Repro: Home → Answer → type a tag → rotate the device.

Expected / why: two separate judgements, and I want to be explicit about which is which.

1. **Rotation / font-scale / theme change must not lose the input.** This is not ambiguous to me. The
   owner's stated top priority is that answering be frictionless; retyping an answer because the phone
   turned over is the opposite, and unlike process death the user did nothing that reads as
   "leaving". `rememberSaveable` on the form state, and a saveable nav stack, is a small change.
   Losing the *screen* as well (you are dumped on Home mid-answer) makes it worse: the user has to find
   the ping again, and if it has meanwhile expired they are routed through Backlog with a LATE banner
   (§10.4) for an answer they started on time.
2. **Process death mid-answer** — I could not test this cleanly within the timebox (killing the process
   with input typed is trivially destructive; the evidence above already shows the state is not
   persisted anywhere, so the outcome is not in doubt: the draft is gone). My view: losing the draft
   here is *acceptable* — nothing partial is written, the sample stays `pending`, honest accounting is
   preserved, and persisting drafts to the DB would put a write on the answer path that the fold model
   has no event type for. But the app should at least *return to the Answer screen for that sample* on
   relaunch while the sample is still active, instead of Home.
   **Question for the owner**: is a lost draft after a kill acceptable, and should relaunch resume the
   Answer screen? The spec is silent (§10.3 describes the screen, not its lifetime), so this is a
   decision, not a defect.

Test: Tier 2 for (1) — `StateRestorationTester` (or an activity recreate) with text in the tags field
and a choice selected, asserting both survive and that the Answer screen is still displayed. (2) goes
to the owner before any test is written.

---

### F7 — Invalid tags are rejected clearly, but the message echoes the whole bad tag and never states the rule
Severity: papercut

Evidence: `.emu/c6-longtag-err.png` — after submitting a 74-character tag, the field turns red and the
error reads `Invalid tag: aaaaaaaaaabbbbbbbbbbccccccccccddddddddddeeeeeeeeeeffffffffffgggggggggghhhh`,
wrapping over two lines and pushing the rest of the form down. Nothing says *why*: not "max 64
characters", not "letters, digits, `_ . -` only". The tags field is single-line, so the start of the
offending text is scrolled out of view at the same time.

Repro: Home → Answer → type 74 `a`-`h` characters as one token → Submit.

Expected / why: the charter's bar is "invalid tags must be rejected with a clear message, never
silently mangled". Rejection and non-mangling are both satisfied — that part is a pass, and worth
recording as such. What is missing is the rule. Spec §7 defines the charset `[A-Za-z0-9_.\-]{1,64}`;
the user cannot infer from this message whether the problem is the length, a character, or the
duplicate. Suggested: `Tag too long (max 64)` / `Tag may only use letters, digits, _ . -` and elide the
echoed tag to ~20 chars.

Also verified in the same pass, and correct: leading/trailing/interior whitespace is collapsed
(`"   work   coffee   "` became the two tags `work`, `coffee` — `.emu/c6-note-newlines.png`), and
Enter no longer inserts a newline into the tags field.

Test: Tier 2 — assert the error string for an over-long tag mentions the limit, and for a
charset-violating tag mentions the allowed characters.

---

### F8 — On arrival, the Answer screen auto-scrolls the stream name and scheduled time (and therefore the LATE banner) off the top
Severity: papercut (a §10.4 risk at large font scales)

Evidence: `.emu/c6-answer-font15-b.png` — font scale 1.5, keyboard dismissed, screen just opened: the
topmost visible text is "Scheduled 2026-09-01 17:00" clipped by the app bar; "Daytime (Poisson)" is
gone. At scale 1.0 with the keyboard up the same thing happens (`.emu/c6-answer-dark-kb.png`: the header
is above the strip).

Repro: `settings put system font_scale 1.5`, Home → Answer.

Expected / why: the tags field taking focus (§10.3) drags the scroll position to it, and the header is
whatever gets pushed out. That header is exactly where §10.4 puts the LATE banner and the prominent
original time — the one thing the design says must be unmissable. I could not photograph a late sample
at 1.5 within the timebox, so I am reporting the mechanism rather than the late case; but the mechanism
does not distinguish between the two. Cheapest fix: request focus without scrolling, or pin the header
above the scrolling region.

At 1.5 the rest of the form is otherwise **fine** — no truncation, no overlap, all labels intact; see
"Checked and fine".

Test: Tier 2 — open the Answer screen for a late sample at `fontScale = 1.5` and assert the LATE banner
node is displayed (not merely present) without scrolling.

---

## Checked and fine

- **Dark theme, four screens photographed.** Home (`.emu/c6-home-dark.png`), Answer
  (`.emu/c6-answer-dark-nokb.png`), History (`.emu/c6-history-dark.png`), Settings
  (`.emu/c6-settings-dark.png`). **Streams and Backlog were not visited in dark within the timebox** —
  see "Not covered". On the four that were, every screen flips:
  no white-on-white, no black-on-black, no hard-coded text colour left behind. The only non-flipping
  colours in the app are the four calendar greens (F5). Contrast on body text, labels, outlined buttons
  and the error red is comfortable in both themes.
- **Dark theme, error state.** The invalid-tag red (`.emu/c6-longtag-err.png`) is legible on the dark
  surface and the field outline turns red with it.
- **Light theme baseline** (`.emu/c6-home-light.png`) matches the dark layout element for element.
- **C1 fixes confirmed present** in this build: tag chips no longer blow up the row (the suggestion row
  is a FlowRow and stayed absent/compact throughout), the window no longer pans under the status bar,
  the tags field is single-line and Enter does not insert a newline, the number field is numeric, and
  single-cardinality choices render as radio rows while multi renders as checkboxes
  (`.emu/c6-answer-dark-nokb.png`). C1 F3 (Submit behind the IME) is **not** fixed — see F1.
- **Font scale 1.5.** Home (`.emu/c6-answer-font15.png`) wraps cleanly: the card title breaks to two
  lines, "Streams (today: fired / answered / expired)" wraps, all three of Answer / Snooze / Skip keep
  their labels, the quiet-mode buttons keep theirs ("For H:MM…" was already elided at 1.0 and stays
  elided, not clipped). Answer form at 1.5 (`.emu/c6-answer-font15-b.png`): no truncation, no overlap,
  choice labels intact, Submit still reachable by scrolling. Nothing clipped, nothing unlabelled.
- **Landscape, keyboard down.** Home scrolls in landscape and reaches the bottom of the page
  (`.emu/c6-answer-landscape.png` plus a swipe: "Quiet: off", the quiet buttons and "Last 8 weeks" all
  reachable). The Answer form is fully present in the view tree in landscape as long as the IME is
  closed. The wide dead margin on the right in landscape is plain, not broken — consistent with §10.1's
  TagTime plainness, so I am not reporting it.
- **History status-filter chip row** clips "unobserved" at the right edge but the row scrolls
  horizontally; `suppressed`, `pending` and `retracted` are all reachable. Not a finding (no scroll
  affordance is a nit, but the row visibly overflows, which is the affordance).
- **Whitespace handling** in tags: leading, trailing and repeated interior spaces are collapsed
  correctly; no empty tag is produced.
- **Enter in the Note/tags fields** does not corrupt the form value (the damage is F3, in the choice
  fields, not in the text).
- **No crashes.** Nothing in this charter produced an ANR, a force-close, or an engine-thread crash
  (relevant given C5 F2); the process survived rotation, two font-scale changes, a theme flip and the
  junk-input passes.

## Not covered / blocked

- **Unicode, emoji and RTL input could not be entered.** `emu.sh type` and `input text` drop or throw on
  non-ASCII on this emulator image (`Exception occurred while executing 'text':` for a UTF-8 payload),
  and `cmd clipboard set-text` does not exist on this build ("No shell command implementation"), so
  there was no route to get `café`, `🎉` or `עברית` into a field. What *can* be said from code: the
  tags validator is `Regex("[A-Za-z0-9_.\\-]{1,64}")` applied per whitespace-separated token
  (`AnswerScreen.kt:64`, `:220`), so a unicode tag takes exactly the F7 path — rejected with
  `Invalid tag: …`, never mangled — and free-text fields are stored verbatim as a `JsonPrimitive`
  with no charset filter. Getting real coverage needs either an ADB keyboard IME on the AVD or a Tier 2
  Compose test that sets the text directly; **the latter is the cheap fix and should be added**:
  assert an emoji tag is rejected with a message, and that an emoji/RTL Note survives a round trip
  through the event log and the CSV export unchanged.
- **Streams and Backlog in dark theme** — not screenshotted. Both are built from the same
  `MaterialTheme` and the same `statusColor` helper as History, so F4 applies to Backlog by
  construction, but neither was looked at.
- **Small screen / high density** (`wm size` / `wm density`) — not reached within the timebox. Given F1
  and F2, a shorter viewport is very likely to make the IME collapse worse, so this is the first thing
  to try after F1 is fixed.
- **Process death mid-answer** — reasoned about from the state-holding code rather than executed; see
  F6 (2).
- **Font scale 2.0** — not run; 1.5 was clean everywhere except F8, and the 2.0 case is unlikely to add
  a distinct finding until F1 is fixed.
