# C1 — The answer flow, as the owner will actually live in it

Charter run: 2026-08-30, headless emulator (Android 15, 1080x2400), debug APK already installed,
dev DB seeded by `emu_seed.py` defaults (device `emu-pes`, streams `day` Poisson + `fixed`
12:00/20:00, survey `dev` v1: tags / number(1-7, integer) / choice-single / choice-multi / text,
no required fields). Pings answered end to end during the run:

| sample | route | result |
|---|---|---|
| `day\|2026-08-31T02:59:06Z` (test) | notification body tap | full survey, all 5 fields, `latency_s: 177` |
| `fixed\|2026-08-31T03:00:00Z` | Home active card → Answer | quick: tags only via suggestion chip, `latency_s: 172` |
| `day\|2026-08-31T03:03:36Z` (test) | notification inline "Reply tags" | `partial: true`, 6 tags, `latency_s: 63` |

All three folded correctly (`.emu/pes.db` + `-wal`, `samples` table) — see "Checked and fine".

---

### F1 — With ≥5 tags in the vocabulary the suggestion row clips the last chips to a few pixels wide and 371 px tall, injecting ~330 px of dead space above the next field
Severity: bug

Evidence: `.emu/c1-suggestions.png` (tags vocab = 7 entries after the inline-reply answer).
Rendered chips: `work.writing`, `commute`, `cooking`, `email` — then two chips squeezed to
slivers. `emu.sh ui`:

```
bounds="[42,865][329,991]"    text="work.writing"
bounds="[345,865][584,991]"   text="commute"
bounds="[600,865][814,991]"   text="cooking"
bounds="[830,865][966,991]"   text="email"
bounds="[966,865][975,1236]"     <- 5th chip: 9 px wide, 371 px tall, no readable label
bounds="[1020,865][1038,1236]"   <- 6th chip: 18 px wide, off the right edge
bounds="[42,1321][1038,1489]" text="Mood (1-7)"
```

The chip row is 126 px tall when everything fits (`[..,865][..,991]`) but 424 px tall here, so
"Mood" starts at y=1321 instead of ~y=1023 — roughly 300 px of blank page between the tags field
and the next field, visible as the empty band in the screenshot. The two squeezed chips are also
unreachable: they have no tappable width and the row is neither wrapping nor horizontally
scrollable.

Cause is visible in `AnswerScreen.kt`: `d.suggestions[id] … .take(6)` rendered in a plain
`Row(horizontalArrangement = Arrangement.spacedBy(6.dp))` — a `Row` distributes overflow by
squeezing children, it does not wrap or scroll.

Repro:
1. `emu.sh seed` (defaults).
2. Streams → "Fire test ping now" on Daytime (Poisson); open it from the notification.
3. Inline-reply or type `email reading exercise cooking commute errands` and submit.
4. Fire another test ping and open it. Tags vocabulary now has 7 entries.
5. `emu.sh shot` — only 4 chips are legible; `emu.sh ui` shows two chips ~9–18 px wide, 371 px tall.

Expected / why: all offered suggestions must be readable and tappable, and no field may be
separated from the next by a screenful of nothing. Preferences §2: "one page, scroll down through
fields", "fast and frictionless above almost everything else". Tag suggestions are the single
biggest typing saver in the whole flow (they are the only autocomplete the owner asked for,
preferences §2 "free-form autocomplete tags"), so silently dropping 2 of 6 defeats the feature.
Note the count that overflows depends on tag length: with the seeded vocabulary 4 fit; with short
tags more would, with long ones fewer.

Test: Tier 2 (`androidTest`, Compose). Seed a tags vocabulary of 6 entries whose combined width
exceeds the screen, render `AnswerScreen`, and assert (a) every suggestion node returned by
`onAllNodesWithTag("tagSuggestion")` has width ≥ its minimum touch target (48 dp) and is fully
within the viewport, and (b) the vertical gap between the tags field's bottom and the next field's
top is under one chip height. A Tier 1 device check on the `ui` dump (no node narrower than 100 px
inside the suggestion row) would also catch it.

---

### F2 — Opening the soft keyboard on a lower field pans the whole window up, drawing form content under the status bar
Severity: bug

Evidence: `.emu/c1-note-kbd.png`. After tapping the "Note" field near the bottom of the form, the
window is panned: the app bar ("Experience Sampler") is gone off the top, a text-field border is
cut by the screen edge at y=0, and the "Where" label plus a chip render *behind* the status bar —
the clock ("8:01") and the wifi/battery icons sit on top of app content. Compare
`.emu/c1-answer-entry.png` (keyboard open on the *first* field), where the layout is correct.

The activity declares no `windowSoftInputMode` (`AndroidManifest.xml` line 14 area — only
`android:theme="@android:style/Theme.DeviceDefault.DayNight"`), and `MainActivity` wraps content in
a bare `Scaffold { padding -> ... }` with no ime/system-bars insets handling, so the system falls
back to panning the whole window including the ActionBar.

Repro:
1. Open any ping's Answer screen (`emu.sh shot c1-bottom` state).
2. Scroll to the bottom, tap the "Note" text field.
3. `emu.sh shot` — content is drawn under the status bar; the app bar has scrolled off.

Expected / why: text must never render under the status bar. Spec §10.3 describes the answer
screen as a single scrolling page, which implies the *content* scrolls under a stable chrome, not
the window panning. Practically the owner will hit this on every answer that uses the note field.

Test: Tier 2 (`androidTest`). With the IME shown on the last field, assert the answer screen's root
content bounds' top is ≥ the status bar inset height (`WindowInsets.statusBars`), i.e. no node's
top edge is above the safe inset.

---

### F3 — Submit is hidden behind the keyboard after typing in the last field and needs an extra scroll or a keyboard dismissal
Severity: papercut

Evidence: after typing into "Note" (`.emu/c1-note-kbd.png`), `emu.sh ui | grep -c Submit` returned
`0` — the Submit button is not in the accessibility tree at all, i.e. off-screen. One more upward
swipe brought it back (`bounds="[42,1403][285,1529]" text="Submit"`), and in the quick-answer run I
had to press BACK to dismiss the keyboard and then swipe once more before Submit appeared at
`bounds="[42,2170][285,2296]"`.

Repro:
1. Answer screen → scroll to bottom → tap "Note" → type anything.
2. `emu.sh ui | grep Submit` → nothing. Swipe up once → Submit is there.

Expected / why: Submit *is* reachable, so this is not a spec violation (§10.3 puts Submit at the
bottom, and that is what the app does). It is a papercut against "fast and frictionless": the final
gesture of every answer that ends in a text field is an extra scroll or a keyboard dismissal that
carries no information. Both other clients' equivalent (desktop tkinter) has the whole form on a
resizable window with the button always visible, so this is Android-specific friction. A sticky
Submit row pinned above the IME, or an IME "Done"/`ImeAction.Done` on the last field that submits,
would remove one gesture from every single answer — the highest-leverage change on this list after
F1. (Opinion where it prescribes the fix; the measurement is not opinion.)

Test: Tier 2. With the IME shown after typing into the last field, assert
`onNodeWithText("Submit").assertIsDisplayed()`.

---

### F4 — Enter in the tags field inserts a newline and grows the field instead of moving on; no IME "next" anywhere in the form
Severity: papercut

Evidence: `.emu/c1-tags-enter.png` — after typing `work.writing` and pressing ENTER the field is
two lines tall with the caret on line 2, and every field below has shifted down ~54 px. The IME
action key on the tags field is the newline arrow (`.emu/c1-answer-kbd.png`), while the single-line
Mood field shows a tick/Done that only closes the keyboard (`.emu/c1-mood.png`) — it does not move
focus to the next field.

In `AnswerScreen.kt` the `tags` `OutlinedTextField` sets no `singleLine` and no `keyboardOptions`,
so it is multiline with a newline IME action; no field in the form declares
`ImeAction.Next`/`FocusDirection.Next`.

Repro:
1. Open any ping from its notification (focus is already in the tags field).
2. Type a tag, press ENTER.
3. `emu.sh shot` — a blank second line inside the tags box.

Expected / why: pressing Enter after typing a tag is what a TagTime-shaped habit produces, and it
should either commit the tag or advance, not silently add whitespace and reflow the page. Tag
parsing itself is safe (`split(Regex("\\s+"))` eats the newline, confirmed by the DB row for
`day|2026-08-31T02:59:06Z`), so this is cosmetic/friction, not data corruption. Preferences §2:
"clear, painless, quick".

Test: Tier 2. Assert the tags field's `KeyboardOptions.imeAction == ImeAction.Next` and that
performing the IME action moves focus to the next field.

---

### F5 — A bounded integer field ("Mood 1-7") opens the alphabetic keyboard and accepts letters until Submit
Severity: papercut

Evidence: `.emu/c1-mood.png` — focus is in "Mood (1-7)" (min 1, max 7, `integer: true`) and Gboard
shows the QWERTY layout; the digit row is only reachable by long-press or by tapping `?123` first.
`AnswerScreen.kt`'s non-slider `number` branch passes no `keyboardOptions`, so it inherits the
default text keyboard, and validation ("Not a number" / "Min" / "Max") only runs inside `collect()`
at Submit time.

Repro:
1. Open a ping, tap the "Mood (1-7)" field, look at the keyboard.

Expected / why: the field's schema already says integer with a 1..7 range. Preferences §2 lists
"numerical input (optional capped range, integer-only option)" as a first-class type; presenting a
letter keyboard for it costs one extra tap on every numeric answer and lets an invalid value be
typed that is only rejected after the user has scrolled to the bottom and pressed Submit.
`KeyboardType.Number`/`NumberPassword` is the fix. (The seed's mood field has no
`display: "slider"`; with a slider this branch is not taken, so the papercut only bites text-entry
numbers.)

Test: Tier 2. Render a `number` field with `integer: true` and assert
`KeyboardOptions.keyboardType == KeyboardType.Number`; a Tier 1 device check could assert the IME
subtype/layout is numeric after focusing the field.

---

### F6 — The notification inline reply auto-capitalizes, so the same tag can enter the vocabulary as both `email` and `Email`
Severity: bug

Evidence: `.emu/c1-inline-reply.png` shows the RemoteInput keyboard opening in shift-engaged state;
`.emu/c1-inline-typed.png` shows that tapping the `e` key produced `Ei` — a capital E. The in-app
tags field does *not* do this (`.emu/c1-answer-kbd.png`: shift key is not engaged).

`AndroidNotifier` builds the reply with `RemoteInput.Builder(KEY_REPLY).setLabel("tags").build()`
and never constrains the input type, so the IME applies sentence auto-capitalization.
`ActionReceiver.reply()` then accepts it verbatim: `TAG_RE = [A-Za-z0-9_.\-]{1,64}` matches `Email`,
and the vocabulary table is keyed on the raw string — `.emu/pes.db` `tag_vocab` rows are
`(vocab, tag, last_used, count)` with no case folding, so `Email` would become a second row
alongside `email`, splitting suggestion counts and splitting the exported data for analysis.

Repro:
1. Fire a test ping, pull down the shade, tap "Reply tags".
2. Tap the `e` key on the on-screen keyboard (do **not** use `emu.sh type`, which bypasses the IME).
3. `emu.sh shot` — the reply box contains `E`.

Expected / why: this is a genuine data defect, not a cosmetic one — the invariant that derived
artifacts are a pure function of the logs holds, but the logged tags themselves become
inconsistent depending on which of the two answer paths the owner used, and preferences §2 ties
tags to a hierarchical `work.writing` convention that is meaningless if case varies. Either the
inline reply must request no auto-capitalization
(`setEditChoicesBeforeSending`/an `EXTRA_INPUT_TYPE` with `TYPE_TEXT_FLAG_NO_SUGGESTIONS` and no
`CAP_SENTENCES`), or tag normalization must be applied on ingest in both clients.

Test: Tier 1 (device). Send an inline reply containing `Email` via
`am broadcast` with the RemoteInput extra, then assert the resulting `answered` event's tags list
and the `tag_vocab` row. Whichever behaviour is chosen (reject-capitals, lowercase-on-ingest, or
suppress auto-caps) becomes the assertion; a mirrored assertion belongs in the Python engine too so
the two clients cannot drift.

---

### F7 — Selected and unselected choice chips are hard to tell apart, and single-choice looks identical to multi-choice
Severity: papercut (partly opinion, argued below)

Evidence: `.emu/c1-bottom.png`. "home" (selected), "alone" and "partner" (selected) differ from
"work"/"out"/"friends"/"coworkers" only by a faint lavender fill and the absence of the outline —
no check mark, no weight change. The single-select "Where" group and the multi-select "With" group
are rendered with the identical `FilterChip` treatment, so nothing on screen says one is a radio
and the other is checkboxes. `AnswerScreen.kt` builds both with `FilterChip(selected = …)` and
passes no `leadingIcon`, which is what Material3 uses for the selected check mark.

The tag-suggestion chips (`.emu/c1-suggestions.png`) use the same `FilterChip` shape as the choice
chips, so on a survey with both, "work.writing" (a suggestion you can insert) and "home" (an
answer you can select) look like the same control.

Repro: open any ping, select "home" and two "With" options, screenshot.

Expected / why: the *contrast* part is close to objective — a glanced-at chip must show its state
in a moving vehicle in daylight, and preferences §2 asks for a UI that is "clear, painless, quick".
The radio-vs-checkbox part is more clearly a divergence from intent: the owner explicitly listed
"radio buttons, checkboxes" as wanted field types and agreed to collapse them into `choice` "with
display variants" — the display variant is currently not expressed at all. The specific remedy
(check-mark leading icon vs. actual radio/checkbox rows) is my opinion; that the two cardinalities
should be visually distinguishable is not.

Test: Tier 2. Assert a selected chip exposes `SemanticsProperties.Selected` **and** a distinct node
(check icon) or a distinct content description, and that a `cardinality: single` group and a
`cardinality: multi` group produce different semantics roles
(`Role.RadioButton` vs `Role.Checkbox`).

---

### F8 — The action bar shows the constant title "Experience Sampler" on the Answer screen, spending ~145 px of the page on nothing
Severity: question (opinion)

Evidence: every screenshot in this run. On `.emu/c1-answer-entry.png` the platform action bar
(`android:id/action_bar`, `bounds="[0,128][1080,275]"`) reads "Experience Sampler" while the screen's
own header immediately below reads "Daytime (Poisson)". The activity uses
`@android:style/Theme.DeviceDefault.DayNight`, which carries an ActionBar, and the title is never
updated per screen.

Expected / why: this is a judgement call, so flagging it as a question rather than a defect. The
argument for changing it: on the answer screen — the one screen the owner will use several times a
day — 275 px of a 2400 px display (11%) is the status bar plus a title that repeats the app name
the user just came from, which directly trades against "one page, scroll down through fields". The
action bar also cannot host anything useful today (there are no menu items). The argument for
leaving it: it is standard platform chrome and it is where a per-screen title would live if screens
ever gain one; F2's panning bug would also change shape if it were removed.

Test: none until the owner decides. If they want it gone, Tier 2 asserting no ActionBar node on the
Answer screen.

---

## Tap/keystroke budget

Measured on the seeded `dev` survey (5 fields). "Scroll" = one swipe gesture.

**Full survey, arrived from a notification** (`day|2026-08-31T02:59:06Z`):

| step | cost |
|---|---|
| tap notification body | 1 tap |
| type `work.writing` (focus already in tags — correct per §10.3) | 12 keystrokes |
| tap Mood field | 1 tap |
| switch keyboard to digits (F5) + type `5` | 1 tap + 1 keystroke |
| tap "home" | 1 tap |
| tap "alone", "partner" | 2 taps |
| scroll to reach the Note field | 1 scroll |
| tap Note field | 1 tap |
| type `drafting the report` | 18 keystrokes |
| scroll again because the keyboard hid Submit (F3) | 1 scroll |
| tap Submit | 1 tap |
| **total** | **8 taps + 2 scrolls + ~31 keystrokes** |

With a populated tag vocabulary, F1 adds roughly one more scroll's worth of dead page between the
tags field and Mood.

**Quick answer, from Home's active card** (`fixed|2026-08-31T03:00:00Z`, tags only):

| step | cost |
|---|---|
| tap "Answer" on the Home card | 1 tap |
| tap the `work.writing` suggestion chip | 1 tap |
| BACK to dismiss the keyboard | 1 tap |
| scroll past Mood / Where / With / Note to reach Submit | 1 scroll |
| tap Submit | 1 tap |
| **total** | **4 taps + 1 scroll, zero typing** |

**Inline notification reply** (`day|2026-08-31T03:03:36Z`, the fastest path):
1 tap "Reply tags" + typing + 1 tap send = **2 taps, app never opened**, written as
`partial: true` with only the tags field. This path is genuinely excellent and is what the owner
will use most.

Is that defensible? The inline reply, yes — unimprovable. The full survey at 8 taps + 2 scrolls is
about right for 5 fields, but two of those gestures (the post-typing scroll in F3, the dead space in
F1) carry no information; removing them would take it to 8 taps + 1 scroll. The quick answer is the
weakest number: answering with one tag costs a scroll past three fields the user deliberately left
empty. Nothing about that is a spec violation — §10.3 puts Submit at the bottom — but it is the
place where "fast and frictionless above almost everything else" is paying the most for the least.

## Checked and fine

- **Focus on entry (§10.3)**: the tags field is focused and the caret is visible the instant the
  Answer screen opens, from a notification tap and from Home's card alike
  (`.emu/c1-answer-entry.png`, `.emu/c1-quick-entry.png`); the keyboard opens automatically
  (`dumpsys input_method` → `mInputShown=true`) and does **not** cover the tags field.
- **Notification content and actions (§10.3)**: stream name as the title, "Ping at 19:59 - answer
  now" as the body, and exactly `Reply tags` / `Snooze` / `Skip` because the survey's first field is
  `tags` and no other field is required — the inline reply correctly takes the "Open" slot
  (`.emu/c1-shade.png`).
- **Inline reply semantics**: writes `answered` with `partial: true` and only the tags field, and
  the ping's `latency_s` is measured from the scheduled time (63 s), not from when the shade opened.
- **Submit writes locally and returns to Home instantly** — no spinner, no intermediate screen, and
  Home immediately showed the next active ping (`fixed` at 20:00) on return.
- **No animations or transitions anywhere.** `MainActivity` uses a hand-rolled `stack` of screens
  with a plain `when`, so screen changes are an instant swap; nothing faded, slid or bounced during
  the whole session. Matches §10.1 and preferences §2 ("no long animations").
- **No perceptible lag**: every screen was fully populated in the first screenshot taken ~2 s after
  the tap; no "Loading…" state was ever caught on screen.
- **Tag suggestions save typing and are correctly scoped**: after the first answer the
  `work.writing` chip appeared on the next ping and one tap inserted it with a trailing space
  (`.emu/c1-quick-entry.png`); the vocabulary is keyed `dev.tags` and counts increment
  (`tag_vocab`: `work.writing` count 2). Prefix filtering behaves as coded. Only the layout is
  broken (F1) — the mechanism is right.
- **Form state is keyed on the sample**: navigating from the answered `day` ping to the `fixed` ping
  presented an empty form, no inherited half-typed values.
- **Fold correctness for all three answers**: `samples` rows carry `status: answered`,
  `observed: true`, correct `test` flag (`true` for the two "Fire test ping now" samples, `false` for
  the real 20:00 fixed ping), `snoozes: 0`, `fired_on: ["emu-pes"]`, `answered_on: "emu-pes"`,
  `late: false`, `config_version: 2`, and answers typed correctly — `mood` as the integer `5`, not
  `"5"`; `with` as a sorted array `["alone","partner"]`; `where` as the scalar `"home"`.
  Each ping produced exactly one `fired` and one `answered` event (`events` table, 4 rows for the
  first two pings), source file `events/emu-pes/2026-08.jsonl`.
- **Scheduled time is repeated next to Submit** (§10.4): "for ping at 2026-08-30 19:59"
  (`.emu/c1-bottom.png`).
- **Snooze / Skip are present as secondary actions at the top** of a non-late ping and are correctly
  *absent* from a late/backlog one (per the code path; late-sample behaviour itself is C2's charter).

## Not covered by this charter

Late/backlog entry points (C2), quiet mode and expiry during an open form (C3/C4), dark theme and
font scale on the Answer screen (C6), and rotation/process death mid-answer (C6). The `-wal` file
for the app DB was 1.8 MB against a 72 KB main DB after four events and is never checkpointed —
noted here only because `emu.sh db` pulls the main file alone and therefore returns an *empty*
database to anyone inspecting state; whoever runs the next charter should pull `pes.sqlite-wal`
too.
