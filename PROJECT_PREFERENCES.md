# Personal Experience Sampler — Project Preferences

This file records what the project owner wants and why, as expressed during the design conversation. It is the intent behind `SPECIFICATION.md`. When the spec is silent or ambiguous, resolve it in the direction of these preferences. Where the owner explicitly deferred to the assistant's judgement, that is noted.

## 1. What this is and who it's for

- A personal experience-sampling app: random (or otherwise scheduled) notifications prompting the user to fill out a short custom survey; all data aggregated and easily exported.
- **Primary use case is an individual using it on themselves.** Existing ESM apps target researchers/participant groups, require a backend, and have limited customizability. This project is deliberately the single-user, self-configured, own-your-data version.
- **TagTime is the reference point** for both functionality and look/feel: plain, minimal, text-forward, quick to answer. The owner is *not* attached to any TagTime implementation detail; better approaches are welcome.
- Survey of the existing landscape (TagTime, xSample, ExperienceSampler, SampleU, Track, Moodistory, iMoodJournal, Daylio) found nothing that combines individual use, configurable sampling distributions, fully custom surveys, honest missed-ping accounting, and easy export.

## 2. Core functional requirements (owner's words, lightly organized)

### Sampling control
- Raw **Poisson pings are the sensible default**, but sampling must be more configurable than TagTime:
  - Zone out blocks of time (quiet zones).
  - Purely deterministic, e.g. every hour.
  - Fixed number of pings per interval, e.g. 1 ping every 2 hours sampled uniformly within each 2-hour window (stratified).
  - Poisson with a guaranteed minimum gap between pings.
- Design the protocol layer **modularly so any arbitrary formulation can be substituted in later**.
- **Multiple streams**, like alarms in an alarm-clock app: arbitrary number, each with its own protocol and survey, each can be turned **off and back on without deleting**. Example: one stream pinging several times a day, another once a week.
- Each stream has a **user-assigned name** (e.g. "in the moment thoughts v1") and the home screen should summarize the active streams.

### Suppression vs. off vs. domain — an important distinction
- For continuous protocols like Poisson, pings should be **generated 24/7 by default and suppressed** during quiet zones / quiet mode, rather than the domain being cut. Suppressed pings are recorded as having happened.
- Pings should be **inferable even when no device was running** (e.g. everything off overnight): from the seed and protocol you can reconstruct when they would have fired. These are recorded as unobserved.
- This is *not* a universal rule: a protocol like "ping me only at 12:00 and 20:00" has a domain of only those instants and should not generate imaginary pings at other times.
- A stream that is **off is simply non-functional** — no samples exist. Off ≠ suppressed.

### Surveys
- Tags (TagTime-style, the dotted `work.writing` convention for hierarchy is enough for now) are great, but also want mood, in-the-moment thoughts, etc. — i.e. **arbitrary, simple surveys**.
- Field types wanted: text input, numerical input (optional capped range, integer-only option, optionally shown as a bar/slider when bounded), free-form autocomplete tags, curated fixed-list tags, radio buttons, checkboxes, yes/no. Owner agreed these collapse into `text`, `number`, `tags`, `choice` (with display variants) and that a labeled scale is a bounded number.
- Survey **filling UI is the main way the user engages and is fairly important**: clear, painless, quick; one page, scroll down through fields; **no long animations**.
- A **form-based editor** for surveys and streams, plus a **raw JSON editor as an emergency backup**.
- Start simple: one survey per stream, optional "full survey every Nth ping". The intent is that the **periodic full pings can include different/broader surveys** than the quick ones. Owner chose a true every-Nth cadence (index within the local day, first ping of the day always full) over hash-based random selection. Richer multi-survey logic can come later.

### Missed / skipped / late pings
- Smarter handling than "it didn't happen". Record **time from ping to recorded answer** as metadata; record **skipped pings as having occurred but skipped**, for data completeness.
- User options at a ping: **fill out, snooze, or skip**.
- **Snooze** default 10 min; the next notification must clearly indicate it is a previously snoozed sample; number of snoozes is metadata; **latency is always measured from the original sample time** — snooze is only a tool to get a new notification.
- **Expiry** default 1 hour, after which the app stops pinging for that sample. Snooze and expiry are configurable at a **global level with per-stream overrides** (local priority → global fallback).
- **Late answers are allowed** with no real restriction, provided (1) lateness is recorded (original sample time and submission time both stored) and (2) the **UI makes it impossible to mistake an old sample for a new one**: late samples live only in a separate section, original time displayed prominently. No emphasis on nagging the user to fill old samples; something like "don't surface pings older than ~12 hours" as a backlog window.
- A "recent unanswered" **backlog** section was agreed as the way to do this.

### Multi-device
- Want **the same pings on phone and laptop** (Windows most important, Linux second), if easily doable. Agreed approach: shared seed + deterministic protocol so devices agree without talking; verified by shared test vectors.
- Devices must be able to **sync on demand** (a button), **on restart**, and **at certain times**, using the **same remote location as the data**, e.g. to pick up a changed sampling protocol.
- Duplicate protection: **user engagement first, then first device to write**. A **filled-out sample must never be overwritten by a non-filled-out one**. Snooze on phone then answer on laptop should **merge seamlessly into one sample** (snoozed, then answered). **Device used is metadata.** Owner is unsure about other conflict cases and left them to the assistant (resolved via the event-stream fold in the spec).
- The two clients should **resemble each other in form and function** so they are recognizably the same app.

### Storage, cloud, export
- **Google Drive folder as the cloud location**, via Google Cloud / Drive API (owner approved this after being warned that treating Drive as a local folder on Android is unreliable; the agreed rule is **local-first, sync is a separate best-effort step**). After review, both clients use the Drive REST API with the `drive.file` scope and a production-mode consent screen (a Testing-mode app would expire tokens every 7 days, and files written by Drive for Desktop would be invisible to the Android app under `drive.file`). The desktop mirror folder is read-only, for analysis.
- **DST**: owner's preference is to keep everything as-is (all elapsed time in UTC), treat samples scheduled for a nonexistent local time as **suppressed**, and fire samples for duplicated local times **once, at the first occurrence**. Owner does not need any particular behavior beyond "not weird or inconsistent".
- Owner's mental model: **cloud is the source of truth** because multiple devices contribute; each device periodically syncs. Agreed refinement: each device is authoritative for its own rows, cloud is the union.
- Data is tiny (text only). **Backup is important**: any device should be able to recreate lost cloud data from local files, and **periodic frozen snapshots** (weekly/monthly) should be stored.
- Owner expects a **spreadsheet-like (CSV) artifact per protocol/stream in the cloud**, naturally ingestible by other programs and roughly human-readable, produced by the app itself (not only by a separate analysis pass). Agreed: CSV exports are derived from the canonical event logs and regenerated on sync.
- **Plaintext in the cloud is fine for now.** Basic common sense on security; no encryption required at this stage.

### Analysis
- The app should be **fairly dumb**: no analysis of survey *content* in the app. Analysis of **sampling cadence and response behavior** (ping counts, a stylized calendar of past pings) in the app is welcome.
- Real analysis/visualization is a **separate tool** operating on the cloud contents (Python package + notebooks), to be built eventually.

### Other decisions
- **Timezone**: a single configured default timezone, changed only manually (propagated via sync). No automatic timezone changes — explicitly not worth the complexity/error risk.
- **Quiet mode**: a global toggle suppressing all pings, with options "until turned back on" and "for X hours:minutes" that auto-reverts.
- **Schedule hidden by default**: the user should not see upcoming ping times except via a deliberate action. A **dry-run preview** of what a schedule would look like (in the stream editor) and a **"fire a test ping now"** button are wanted.
- Two streams firing near each other is acceptable as long as confusion between them is reasonably prevented.
- Per-field tag vocabularies with optional shared named vocabularies: fine.
- **Location** capture as an optional per-stream field: yes. Other device-context capture (foreground app, etc.): **not now**; would need to be more meaningful than foreground app to be worth it.
- **Battery**: the always-on app must **not burn battery**; owner was assured exact alarms with no polling/foreground service are cheap and that location is the only real cost.
- Phone: **Samsung Galaxy**, but the app should work on any mainstream Android.
- Desktop UI: **native window (tkinter)** preferred over a local web UI.
- Ping volume: **roughly 1–12 per day** in normal use; not designed for hundreds, but may be relatively frequent.

## 3. Things the owner left to the assistant's judgement

- Repository/project structure and Android best practices ("really unfamiliar … leave to your judgement").
- Conflict handling beyond the rules above.
- License (MIT is fine), language choices (Kotlin + Python approved), minimum Android version, default setting values.
- Project name: **"Personal Experience Sampler"** for now.

## 4. Tone and working style

- Owner is technically literate, thinks in terms of sampling distributions and data completeness, and prefers honest accounting over convenience (every generated ping gets a row; denominators stay stable).
- Prefers simple, deterministic mechanisms (fixed timezone, seeded schedules, append-only logs) over clever automatic behavior.
- Wants the answering experience to be fast and frictionless above almost everything else in the UI.
- Open to better alternatives at every point; not wedded to any prior tool's specifics.
