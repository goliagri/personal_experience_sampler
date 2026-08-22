# Personal Experience Sampler — Technical Specification

Version 0.3 (design complete, pre-implementation; incorporates two review rounds). Companion document: `PROJECT_PREFERENCES.md` (the owner's intent and priorities; read it first).

Changes from 0.2: event deduplication before fold; retroactive expiry on refold; bit-exact `ln` (fdlibm port in Python); collision rule covers DST-resolved candidates; root folder located by stored ID; `partial` column defined; Poisson local-day windowing; primary-role tie-break; quick-survey selection changed to per-day index (owner's preference).

Changes from 0.1: both clients use the Drive REST API with `drive.file` in production mode (fixes cross-device visibility and token expiry); restore procedure added; Poisson min-gap scoped within day; `suppressed` outranks `unobserved`; hash-based quick-survey selection; retroactive `expired` in backfill; DST rules; `late` fold-computed only; tag charset; same-second collision rule; snooze refusal near expiry; `StrictMath.log`; snapshots on Android when no desktop; test-ping contradiction resolved.

---

## 1. Overview

Personal Experience Sampler (PES) is a single-user experience-sampling system. At times determined by configurable random or deterministic **protocols**, the user is pinged with a notification and fills out a short user-defined **survey**. Every ping (answered or not) is recorded. Data is stored locally on each device, synced to a Google Drive folder owned by the app, and exported as per-stream CSV files.

Two clients share one design:

- **Android app** (Kotlin, Jetpack Compose, Room, AlarmManager, WorkManager).
- **Desktop client** (Python 3.11+, tkinter, tray icon; Windows first, Linux second).

Both clients compute the same ping schedule independently from a shared seed and configuration, so no server and no real-time communication is needed. The cloud folder is the union of all devices' append-only event logs plus versioned configuration.

Spiritual ancestor: TagTime (Poisson pings, tag-string answers, deterministic schedule from a seed, multi-device agreement). PES generalizes the sampling protocol, the survey, and non-response handling.

---

## 2. Glossary

| Term | Meaning |
|---|---|
| **Stream** | A named, independently configured sampling process: protocol + survey + quiet zones + settings + seed. Analogous to one alarm in an alarm-clock app. |
| **Protocol** | The rule that generates candidate ping times for a stream (Poisson, stratified, fixed interval, fixed times). |
| **Domain** | The set of times at which a protocol exists at all. Poisson has an all-time domain; "fixed times 12:00 and 20:00" has a domain of only those instants. |
| **Quiet zone** | A recurring wall-clock window (in the configured timezone) in which a stream's pings are generated but **suppressed** (not shown). Applied on top of the domain. |
| **Quiet mode** | A global, manually toggled state (indefinite or timed) in which all streams' pings are suppressed. |
| **Sample** | One scheduled ping of one stream. Identified by `stream_id|scheduled_utc`. |
| **Event** | An append-only record about a sample (or about global state), written by one device. A sample's state is the fold of all its events. |
| **Fold** | Deterministic reduction of a sample's events into one row (status, answer, latency, ...). |
| **Config version** | A monotonically numbered, append-only revision of the configuration with an `effective_from` time. |
| **Device** | An installation of a client with a stable generated `device_id`. |
| **Active / backlog / history** | A sample is *active* until expiry, in the *backlog* until the backlog window passes, then only in *history*. |
| **Suppressed vs. off vs. unobserved** | *Suppressed*: generated, intentionally not shown (quiet zone / quiet mode / DST gap / stale config). *Off*: the stream is disabled, no samples exist. *Unobserved*: generated, but no device was running to show it. |

---

## 3. Architecture

```
                 ┌──────────────────────────────┐
                 │   Google Drive app folder    │
                 │  config / surveys / state    │
                 │  events/<device>/*.jsonl     │
                 │  exports/*.csv  snapshots/   │
                 └──────▲───────────────▲───────┘
          Drive REST API│               │Drive REST API
          (drive.file)  │               │(drive.file)
        ┌───────────────┴──┐       ┌────┴────────────────┐
        │  Android client  │       │  Desktop client     │
        │  local SQLite    │       │  local SQLite       │
        │  scheduler (Kt)  │       │  scheduler (Py)     │
        │  alarms, notifs  │       │  tray, toasts       │
        └──────────────────┘       └─────────────────────┘
                                    (Drive for Desktop mirror:
                                     read-only, for analysis)
```

Principles:

1. **Local-first.** Every ping and answer is written to the device's local database synchronously. The ping path never touches the network.
2. **Deterministic schedule.** Given (config history, stream seed, time window), every client computes identical ping times. Verified by shared test vectors.
3. **Append-only, per-device logs.** In normal sync each device writes only its own log files. Merging is a pure fold, so no write conflicts exist for data. The **restore** procedure (§8.6) is the single exception and may create, but never overwrite, other devices' files.
4. **Cloud = union.** Each device is authoritative for its own rows; the cloud is the union; re-upload is idempotent.
5. **Derived artifacts are disposable.** CSV exports and local folded tables are pure functions of the logs and can be regenerated at any time by any device.
6. **One write path to the cloud.** Both clients write through the Drive REST API with the same OAuth client and `drive.file` scope, so every file is app-created and visible to every client.

---

## 4. Identifiers and time

- All timestamps in logs and IDs are UTC ISO-8601 with `Z`, second resolution (`2026-08-21T14:37:12Z`).
- **All elapsed-time arithmetic is in UTC** (gaps, min gaps, snooze, expiry, latency, backlog window). DST and timezone therefore never affect it.
- **Configured timezone** (`config.timezone`, IANA name) is the single timezone used to convert wall-clock specifications into UTC instants: quiet zones, fixed clock times, interval anchors, local-day tiling, and `scheduled_local` in exports. It changes only when the user edits it; no automatic timezone following. A timezone change is a config change with `effective_from`.
- **DST rules** (apply wherever a local wall-clock time is converted to UTC):
  - A local time that does not exist on a spring-forward day produces a sample at the instant the wall clock would have shown it, immediately logged `suppressed(reason="dst")`. (Concretely: resolve as if the pre-transition offset still applied.)
  - A local time that occurs twice on a fall-back day resolves to the **first** occurrence.
  - A "local day" is the actual interval from one local midnight to the next (23, 24, or 25 h), expressed in UTC.
  - Quiet zones are evaluated on wall-clock local time; on transition days they are simply an hour shorter or longer.
- `stream_id`: user-chosen slug `[a-z0-9_]{1,32}`, immutable after creation (display `name` is freely editable).
- `sample_id`: `"{stream_id}|{scheduled_utc}"`. Uniqueness within a stream is guaranteed by the collision rule in §6.2, which applies to all resolved candidates including DST-resolved ones.
- `device_id`: `"{platform}-{8 hex}"` generated on first run, e.g. `phone-a3f2c1d0`; never changes. User-visible device name is separate.
- `survey_id`: slug, immutable; `survey_version`: integer ≥ 1.
- Ping times are whole seconds (see §6.2).

---

## 5. Data model

### 5.1 Events

One JSON object per line. Common fields: `ev` (type), `t` (UTC time the event was created), `dev` (device_id). Sample events also carry `sample` and `stream`.

| `ev` | Extra fields | Meaning |
|---|---|---|
| `fired` | `config_v`, `scheduled`, `test` (bool, default false) | Device showed a notification for this sample. `t` is the actual fire time (may lag `scheduled` by alarm delay). |
| `snoozed` | `n` (1-based snooze index), `until` | User snoozed. |
| `skipped` | — | User explicitly declined. |
| `answered` | `survey{id,version}`, `answers{field_id: value}`, `loc` (nullable), `supersedes` (nullable: `t` of the answered event being edited), `partial` (bool; true for inline tags-only replies) | User submitted the survey. |
| `expired` | `config_v` | Active window closed without an answer, as observed by this device (at the instant, or retroactively via backfill). |
| `unobserved` | `config_v` | Device, on waking/syncing, determined that this sample was scheduled in a period when no device was running. |
| `suppressed` | `reason` ∈ `quiet_zone`, `quiet_mode`, `dst`, `stale_config` | Generated but intentionally not shown. |
| `retracted` | `note` (optional) | User hid this sample from analysis (soft delete). |
| `quiet_changed` | `quiet_until` (ISO, `"indefinite"`, or `null`) | Global quiet mode toggled. Not a sample event. |
| `config_applied` | `config_v` | Device began using this config version. Not a sample event. |

Answer value encoding by field type: `text` → string; `number` → number; `tags` → array of strings; `choice` → string (single) or array (multi). Unanswered optional fields are omitted.

`loc` when present: `{"lat":..,"lon":..,"acc_m":..,"age_s":..}`.

### 5.2 Fold rules (sample state)

Given all events for one `sample_id` across all devices:

0. **Deduplication**: before folding, events are deduplicated by identity key `(dev, sample, ev, t, n)` (`n` present only for `snoozed`; `supersedes` included for `answered`). Exact duplicate lines arising from restores (§8.6) or repeated imports collapse to one. Events with the same key but different payloads are a data error; the first encountered (by file path order) is kept and a warning is logged.

1. **Status precedence** (highest wins): `retracted` > `answered` > `skipped` > `expired` > `suppressed` > `unobserved` > (`fired`/`snoozed` only → `pending`) > no events (→ `scheduled`, not yet materialized).
   - `retracted` sets `status=retracted`; the prior status is kept in `prior_status`.
   - `suppressed` outranks `unobserved` because it is the more informed claim (the device knew why the ping was not shown). A device backfilling before it has imported another device's `quiet_changed` history may log `unobserved`; the later sync and refold corrects this. `status=unobserved` with `observed=true` cannot persist because refold applies retroactive expiry (§8.4 step 3).
2. **Answer selection**: among `answered` events, chains are formed via `supersedes`; the *latest* in a chain is the effective answer of that chain. Among distinct chains, the chain whose root has the earliest `t` wins; all other chains are marked `duplicate_answer` and retained. The folded `partial` column is the `partial` flag of the winning answer.
3. **Snoozes**: `snoozes = count(snoozed)` across devices.
4. **Devices**: `fired_on = [dev...]`, `answered_on = dev of winning answer`.
5. **Latency**: `latency_s = answered.t − scheduled_utc` (original scheduled time, not snooze time).
6. **Late**: computed only in the fold: `late = latency_s > expiry_minutes*60` under the stream's effective settings at `scheduled_utc`. Not stored in events.
7. **Observed**: `observed = any(fired)`. **Test**: `test = any(fired.test)`.

The fold is implemented once per client and covered by shared test vectors (§13).

### 5.3 Local database (both clients)

SQLite. Tables:

- `events` — mirror of this device's own log (authoritative for own rows) plus imported rows from other devices (cache; used for folding and for restore). Columns: `id`, `dev`, `ev`, `sample`, `stream`, `t`, `payload_json`, `synced` (bool for own rows), `source_file`.
- `samples` — folded view, rebuilt incrementally. Columns match the CSV export.
- `schedule` — materialized upcoming samples (horizon 48h): `sample_id`, `stream`, `scheduled_utc`, `config_v`, `state` (`planned`/`fired`/`suppressed`).
- `config_cache`, `survey_cache`, `state_cache`, `device` — local copies of cloud documents.
- `tag_vocab` — `(vocab_name, tag, last_used, count)` for autocomplete.
- `sync_meta` — Drive file IDs and `modifiedTime`s last seen, `last_materialized_at`.

---

## 6. Scheduler

### 6.1 Interface

```
generate(stream_config, seed, local_day) -> list[Candidate]   # candidate UTC times for one local day
```

`local_day` is a calendar date in the configured timezone; its UTC bounds are computed per §4. Every candidate belongs to exactly one local-day window; protocols whose natural scope is not the local day (Poisson) generate all overlapping scopes and filter to the window. Implemented by a registry keyed by `protocol.type`. The scheduler core (outside protocols) then applies:

1. **Config history**: for each instant, the config version in effect is the highest version with `effective_from <= instant`. Days are generated piecewise when versions change mid-day.
2. **Enabled**: disabled streams generate nothing.
3. **Collision rule** (§6.2), applied to the full set of resolved candidates for the window, including those resolved through DST rules.
4. **Quiet zones**: evaluated in configured timezone; matching candidates become `suppressed(quiet_zone)` samples (still materialized, still logged).
5. **DST gap**: candidates flagged by a protocol as nonexistent-local-time become `suppressed(dst)`.
6. **Quiet mode**: evaluated at fire time against `state.quiet_until`; matching become `suppressed(quiet_mode)`.

New protocols must only implement `generate` and ship test vectors.

### 6.2 Determinism rules

- PRNG: **xoshiro256\*\*** with state initialized from **SplitMix64** seeded by `seed_u64 = first 8 bytes of SHA-256(stream_seed || ":" || scope)` big-endian, where `scope` is the protocol-defined re-seeding scope (e.g. `"day:2026-08-21"`, `"interval:2026-08-21:05"`). Re-seeding per scope means changing config on day D does not shift days D+1 onward, and both clients can regenerate any day independently.
- Uniform doubles: `u = (next_u64() >> 11) * 2^-53` (53-bit, in [0,1)).
- Exponential draw: `gap_seconds = floor(-mean_seconds * ln(1 - u))`. `ln` is **fdlibm's `__ieee754_log`** in both implementations: Kotlin uses `StrictMath.log` (which is fdlibm); the Python core ships a ~50-line pure-Python port of the same routine (`core/fdlibm_log.py`) rather than `math.log`, so results are bit-identical on every platform. All emitted times are whole UTC seconds.
- **Collision rule**: after all candidates for a stream's window are resolved to UTC seconds (including DST resolution), if two occupy the same second, the one later in generation order (for `fixed_times`: later in `times_local` order) is shifted forward by one second, repeating until unique. Applied before quiet-zone evaluation.
- `stream_seed`: 32 hex chars, generated at stream creation; editable (edit = config change).

### 6.3 Protocols (v1)

**`poisson`** — `{mean_gap_minutes, min_gap_minutes?}`
Scope: per UTC day (`"day:YYYY-MM-DD"`; Poisson is timezone-agnostic so the UTC day is used for stability). For a requested local day, generate every UTC day that overlaps it (normally two) and keep only candidates within the local-day bounds. Starting at 00:00Z of each UTC day, draw exponential gaps with the given mean and emit times until the day ends. If `min_gap_minutes` is set, any candidate closer than that to the previous emitted time **within the same UTC day** is shifted to `prev + min_gap` and the process continues from there. The min gap is *not* enforced across midnight UTC; a shorter gap across the day boundary is possible and accepted, preserving per-day re-seeding independence. Memorylessness is broken by the min gap; documented and accepted.

**`stratified`** — `{interval_minutes, pings_per_interval}`
Scope: per interval index within the local day (`"interval:YYYY-MM-DD:k"`). Intervals tile the actual local day (23/24/25 h) from local midnight; a trailing partial interval gets `floor(pings_per_interval * fraction)` pings. Draw offsets uniformly in whole seconds within the interval; sort.

**`fixed_interval`** — `{every_minutes, anchor_local: "HH:MM"}`
Deterministic: resolve the anchor to a UTC instant for the local day (DST rules §4), then emit every `every_minutes` in UTC until the end of the local day.

**`fixed_times`** — `{times_local: ["HH:MM", ...], days?: ["mon",...]}`
Deterministic; domain is exactly these instants on matching local days, resolved per §4 (nonexistent → `suppressed(dst)`, duplicated → first occurrence). Quiet zones still apply (a fixed ping inside a quiet zone is suppressed, not skipped).

### 6.4 Horizon, materialization, backfill

- Each client materializes the schedule 48 hours ahead into `schedule`, regenerating on: app start, config change, sync, day rollover.
- **Backfill** runs on app start, after sync, at each alarm fire, and whenever the clock is detected to have jumped (desktop sleep/hibernate). It covers `[last_materialized_at, now)` and, for every sample in that window, applies in order:
  1. If the sample has a terminal event (`answered`, `skipped`, `expired`, `retracted`, `suppressed`) from any known device → nothing.
  2. Else if it falls in a quiet zone, a DST gap, or a quiet-mode window per imported `quiet_changed` history → log `suppressed` with the reason.
  3. Else if it has a `fired` event from any known device and `scheduled_utc + expiry < now` → log `expired` (retroactive expiry; fixes samples that expired while every device was off).
  4. Else if it has no `fired` event → log `unobserved`.
  `last_materialized_at` then advances to `now`. Since all devices backfill deterministically, duplicates fold away.
- **Stale config**: if a device learns at sync that a config version with an earlier `effective_from` existed that it did not have, samples it fired under the old config in that window are additionally logged as `suppressed(stale_config)`; the fold treats an `answered` as still valid (precedence), so no answer is lost.

### 6.5 Active window, snooze, expiry

Effective settings cascade: `stream.overrides` → `config.defaults`.

- On fire: show notification; sample is *active* until `scheduled_utc + expiry_minutes`.
- **Snooze**: re-fire at `now + snooze_minutes`. Refused (action hidden/disabled) if fewer than `snooze_minutes` remain before expiry, or if `max_snoozes` (default 3) is reached. Notification text indicates "snoozed ×n" and the original time.
- **Skip**: logs `skipped`; notification dismissed.
- **Expire**: at the expiry instant, log `expired`, cancel the notification, sample moves to backlog. If no device is awake at that instant, backfill step 3 logs it later.
- **Backlog**: samples with status `expired`/`unobserved`/`pending` and `scheduled_utc > now − backlog_hours` (default 12). Shown in a distinct section; never pushed via notification.
- **Late answer**: any sample can be answered from backlog or history at any time; `late=true` if past expiry. UI must make the original time unmistakable (§10.4).
- Two streams firing close together: both notifications are shown; notification content always includes stream name and original time. If a survey is open when another fires, the new one queues and is presented after submit.

---

## 7. Survey schema

```json
{
  "id": "thoughts",
  "version": 2,
  "title": "In-the-moment thoughts",
  "fields": [ ...Field ]
}
```

Surveys are immutable once written; editing produces a new version. Field `id`s are stable across versions (rename the label, not the id). Removed fields simply stop appearing; analysis treats them as missing.

**Field** common: `id` (slug, unique within survey), `type`, `label`, `help?`, `required` (default false), `quick` (bool, default false; see quick surveys below).

| `type` | Params | Rendering |
|---|---|---|
| `text` | `multiline` (bool), `max_len?` | Single/multi-line text box. |
| `number` | `min?`, `max?`, `integer` (bool), `display` ∈ `input`,`slider`, `end_labels?` `[low, high]` | `slider` requires both bounds. A labeled Likert scale is `number{min:1,max:7,integer:true,display:slider,end_labels}`. |
| `tags` | `vocab` (name; default = `"{survey_id}.{field_id}"`), `curated?` (array of allowed tags; if present only these are accepted), `suggest_recent` (bool, default true) | Free-form tokenized input with prefix autocomplete from `tag_vocab`. Tags match `[A-Za-z0-9_.\-]{1,64}`; whitespace separates tags; other characters are rejected at input. Dotted hierarchy convention (`work.writing`) supported by prefix matching; no structural hierarchy in v1. |
| `choice` | `options` (array of `{value, label?}` or strings), `cardinality` ∈ `single`,`multi`, `display` ∈ `radio`,`checkbox`,`dropdown`,`chips`,`yesno` | `yesno` requires exactly two options and `single`. Option values match the tag charset. |

**Quick surveys.** Stream-level `full_survey_every_n` (default 1). Let `i` be the sample's 0-based index in its local day's resolved candidate list (after collision rule, **before** quiet-zone/DST suppression, so suppressed candidates count). The sample is *full* iff `n == 1` or `i mod n == 0`; otherwise only fields with `quick: true` are presented (if no field is flagged, the first `tags` field is used). Consequences: the first ping of each local day is always full; full pings occur at a fixed cadence within the day; a day with fewer than `n` pings has one full ping. Since generation is per-day deterministic, all devices agree. The intent is that the periodic full pings can carry a broader survey than the quick ones. v1 supports one survey per stream.

---

## 8. Configuration and sync

### 8.1 `config/current.json`

```json
{
  "version": 7,
  "base_version": 6,
  "written_by": "laptop-9c11aa00",
  "written_at": "2026-08-21T14:05:12Z",
  "effective_from": "2026-08-21T15:00:00Z",
  "timezone": "America/Los_Angeles",
  "defaults": {
    "snooze_minutes": 10, "max_snoozes": 3,
    "expiry_minutes": 60, "backlog_hours": 12,
    "location": "off"
  },
  "streams": [
    {
      "id": "thoughts",
      "name": "In-the-moment thoughts v1",
      "enabled": true,
      "seed": "8f3a9c1e...(32 hex)",
      "protocol": { "type": "poisson", "mean_gap_minutes": 90, "min_gap_minutes": 15 },
      "quiet_zones": [ { "days": ["mon","tue","wed","thu","fri","sat","sun"], "from": "23:00", "to": "07:30" } ],
      "survey": { "id": "thoughts", "version": 2 },
      "full_survey_every_n": 1,
      "location": "coarse",
      "overrides": { "expiry_minutes": 30 },
      "notification": { "sound": "default", "vibrate": true }
    }
  ]
}
```

- `location` ∈ `off`, `coarse` (last-known / low-power fused fix), `precise` (fresh fix; battery cost; off by default). Desktop ignores.
- `effective_from` defaults to the next top-of-hour ≥ now + 5 min; the editor lets the user choose "next hour" / "next midnight" / custom. It may not be in the past.
- Validation: stream ids unique; seeds 32 hex; protocol params in range; survey references exist; quiet zone times `HH:MM`; `from == to` is invalid; `from > to` wraps midnight.

### 8.2 Versioning and conflicts

- A write must carry `base_version` equal to the version the writer last read. Writer sets `version = base_version + 1`.
- The writer also stores the full document as `config/history/config_v{version:04}.json`.
- On sync, if the cloud `current.json` has a version ≠ local and a different `base_version` lineage (i.e. two writers branched from the same base), the one with the later `written_at` is kept; the other is moved to `config/conflicts/` and both clients display a one-line warning with a "view rejected version" action. Rejected versions can be re-applied by the user (as a new version).
- Clients log `config_applied` when they begin using a version.

### 8.3 `state.json` (ephemeral, unversioned)

```json
{ "quiet_until": "2026-08-21T16:30:00Z", "set_by": "phone-a3f2c1d0", "set_at": "2026-08-21T15:30:00Z" }
```

`quiet_until` ∈ ISO time | `"indefinite"` | `null`. Last-writer-wins by `set_at`. Read at every sync and at every fire. Also mirrored as a `quiet_changed` event, which is what backfill uses to classify past quiet-mode windows.

### 8.4 Sync procedure (both clients)

Triggers: manual "Sync now"; app start / boot; before showing each notification (lightweight: metadata compare of `current.json` and `state.json` only; skipped if offline); periodic (Android WorkManager ~hourly with network constraint; desktop every 30 min while running).

Steps:
1. Download `config/current.json`, `state.json`, any new `surveys/**`; apply if changed (regenerate schedule).
2. Upload own `events/{device_id}/{YYYY-MM}.jsonl` for months with unsynced rows (full-file overwrite of that month only).
3. List other devices' event files; download those whose Drive `modifiedTime` changed; import into `events`; refold affected samples. For every refolded sample that has a `fired` event, no terminal event, and `scheduled_utc + expiry < now`, log `expired` (retroactive expiry, independent of the backfill watermark). Cancel local notifications for samples now terminal elsewhere.
4. Regenerate `exports/{stream}.csv` + `columns.json` for streams whose folded rows changed; upload.
5. Update `devices/{device_id}.json` (`last_sync`, app version, `role`).
6. Run backfill (§6.4).

All steps are idempotent; failures are retried on the next trigger. Never block the UI on sync.

### 8.5 Google Drive access

- **Both clients** use the Drive REST API v3 via OAuth 2.0 with scope `drive.file` only, sharing one Google Cloud project and OAuth consent screen. The consent screen is **published to production** (not Testing): `drive.file` is non-sensitive so no verification is required, and production status removes the 7-day refresh-token expiry that applies to all Testing-mode apps. One OAuth client per platform (Android client ID; Desktop client ID) under the same project is fine; `drive.file` visibility is per project.
- Clients store the root folder's Drive file ID in `sync_meta` and use it for all subsequent access (renames in Drive are harmless). Only on first run / adoption: the client locates the folder with `files.list` (`name`, `mimeType=folder`, `trashed=false`) and adopt it. If two roots are found, the client refuses to proceed and asks the user to pick (manual resolution; should only occur if two clients start simultaneously before ever syncing).
- Drive for Desktop (or any mirroring tool) may additionally mirror the folder to disk for the analysis package; clients never write through the mirror.
- Tokens are handled by the platform libraries (Credential Manager on Android; `google-auth-oauthlib` with the loopback flow on desktop, token stored via OS keyring) and never logged.
- Storage layer is abstracted (`CloudStore` interface: `get`, `put_if_absent`, `put`, `list`, `metadata`) so a local-folder/Syncthing backend can be substituted.

### 8.6 Restore procedure

Available from Settings on any client. Intended for a lost or corrupted cloud folder.

1. Client lists the cloud `events/` tree and compares with its local `events` cache (own rows plus imported rows from other devices, grouped by `source_file`).
2. For each `(device, month)` file missing in the cloud: upload the cached copy with `put_if_absent`. Files that exist in the cloud are never overwritten; instead, if the cached copy contains event lines absent from the cloud file, the extra lines are written to `restored/{from_device}/{device}/{YYYY-MM}.jsonl`.
3. Missing `config/history/*`, `surveys/*`, `manifest.json` are likewise restored with `put_if_absent` from the local cache. `current.json` is restored as the highest version in the local cache if absent.
4. The fold reads `events/**` and `restored/**` identically.
5. A normal sync follows.

---

## 9. Cloud folder layout

```
PersonalExperienceSampler/
  manifest.json                     {format_version, created_at, install_id}
  config/
    current.json
    history/config_v0001.json ...
    conflicts/config_v0007_rejected_<dev>_<time>.json
  surveys/<survey_id>/v<N>.json
  state.json
  devices/<device_id>.json          {device_id, name, platform, app_version, last_sync, role}
  events/<device_id>/<YYYY-MM>.jsonl
  restored/<from_device>/<device_id>/<YYYY-MM>.jsonl     (only after a restore)
  exports/<stream_id>.csv
  exports/<stream_id>.columns.json
  snapshots/weekly/<YYYY-MM-DD>.zip
  snapshots/monthly/<YYYY-MM>.zip
```

**Snapshots**: a zip of the entire folder (excluding `snapshots/`). Taken weekly (first sync after Sunday 03:00 local); the first weekly snapshot of each calendar month is also copied to `monthly/`. Retention: 12 weekly, 12 monthly. The snapshotting device is the one whose `devices/*.json` has `role: "primary"`; the desktop client claims `primary` by default on first run, and the Android client claims it if no device with `role: primary` has a `last_sync` within the past 14 days. If two devices both hold `primary` at a sync, the one with the lexicographically lower `device_id` keeps it and the other clears its role. Any device may hold it; only one does.

---

## 10. Clients: shared design

### 10.1 Design language

Both clients use the same screen set, names, status vocabulary, colors, and iconography. Visual style: minimal, text-forward, TagTime-like plainness; no decorative animation; dark and light themes.

Status palette (shared): `answered` green, `skipped` gray, `expired` amber, `unobserved` blue-gray, `suppressed` muted, `pending` accent, `retracted` struck-through.

### 10.2 Screens

- **Home**: active sample card (if any) with Answer / Snooze / Skip; backlog count + link; list of enabled streams by name with today's counts (fired / answered / expired) and next-ping *hidden*; quiet mode toggle (tap: "until turned off" / "for H:MM"); sync status line; ping calendar (last 8 weeks, one cell per day colored by answer rate).
- **Answer** (§10.3).
- **Backlog**: samples within backlog window, grouped by stream, each showing original scheduled time in large type, status, and an Answer button. Header: "These pings have expired. Answers will be marked late."
- **History**: all samples, filterable by stream/status/date; tap to view or answer (late) / edit / retract.
- **Streams**: list (enabled toggle, name, protocol summary). Stream editor: name, enabled, protocol type + params, quiet zones, survey + version, overrides, location, notification; "Preview next 24 h" (dry-run list, not persisted); "Fire test ping now" (creates a real sample at the current second with `test: true` on its `fired` event; it flows through the normal answer path and is included in exports with `test=true`).
- **Surveys**: list; editor with add/remove/reorder fields, per-field type/params/quick/required; "Save as new version"; raw JSON editor tab.
- **Settings**: timezone, defaults, Drive connection, device name, permissions checklist (Android), snapshot role, Restore cloud (§8.6), "Show schedule" (hidden behind an explicit action; reveals next 48 h per stream).

### 10.3 Answer screen

Single vertically scrolling page: header with stream name and scheduled time (and "LATE — originally 14:37, 3 h ago" banner when applicable); fields in schema order; `tags` field first receives focus; Submit button at bottom; Snooze and Skip as secondary actions at top. No transitions or animations. Validation inline; required fields block submit. Submit writes locally and returns to Home instantly.

Android notification: stream name, original time, Open / Snooze / Skip actions. If the survey's first field is `tags` **and no other field is `required`**, an inline reply box is also offered; submitting it logs an `answered` event with `partial: true` and only the tags field. If any non-tags field is required, the inline reply is not offered.

### 10.4 Late-answer safeguards

Late samples are reachable only from Backlog and History, never from Home's active card or a notification. The answer screen shows the banner above, and the scheduled time is repeated next to the Submit button.

---

## 11. Android client

- Kotlin, Jetpack Compose, Room, Kotlin coroutines. `minSdk 29` (Android 10), target latest.
- **Scheduling**: `AlarmManager.setExactAndAllowWhileIdle` for the next ping only; each alarm's receiver logs `fired`, posts the notification, and schedules the next. `BOOT_COMPLETED` and `TIME_CHANGED`/`TIMEZONE_CHANGED` receivers re-materialize, backfill, and reschedule. `SCHEDULE_EXACT_ALARM` requested with in-app rationale.
- **Expiry**: a single "next expiry" exact alarm.
- **No foreground service, no polling, no wake locks** beyond the alarm receiver's brief work.
- **Sync**: WorkManager periodic (1 h, `NetworkType.CONNECTED`) + one-shot on triggers.
- **Permissions checklist** (Settings + first run): notifications, exact alarms, battery optimization exemption, Samsung "never sleeping apps" deep link with instructions, location (only if any stream uses it).
- **Location**: fused provider; `coarse` uses `getLastLocation` with max age 10 min, else one low-power request with 5 s timeout; `precise` one high-accuracy request with 10 s timeout. Never continuous.
- Drive auth via Credential Manager / Google Identity; REST calls via a thin OkHttp client (no heavy Drive SDK).
- Build: Gradle; GitHub Actions builds a signed release APK on tag; distributed via GitHub Releases; Obtainium for on-device updates.

---

## 12. Desktop client

- Python 3.11+, `tkinter` UI, `pystray` tray icon, notifications via `winrt` toasts (Windows) / `notify-send` (Linux). Packaged with PyInstaller (single exe). Autostart via Startup folder (Windows) / XDG autostart (Linux).
- Runs as a tray-resident process; scheduler thread sleeps until the next materialized ping; on wake, fires, notifies, and opens the Answer window on click. Clock jumps (sleep/hibernate) trigger backfill.
- Same screens as Android in tkinter; keyboard-first Answer window (Enter submits when on the last field).
- Cloud access via Drive REST API (§8.5); default `primary` role for snapshots.

---

## 13. Shared conformance tests

`spec/` directory in the repo holds language-neutral fixtures both implementations must pass:

- `prng_vectors.json`: seeds → first 64 outputs of SplitMix64 and xoshiro256**.
- `log_vectors.json`: inputs → fdlibm `ln` outputs as IEEE-754 bit patterns (verifies the Python port against `StrictMath.log`).
- `schedule_vectors.json`: (stream config, seed, local day) → expected scheduled times, for each protocol, including min-gap within day, midnight-crossing, a local day spanning two UTC days for Poisson, same-second collision (including a DST-resolved candidate colliding with a real one), quiet-zone suppression, config changes mid-day, and **DST cases**: spring-forward and fall-back days for `fixed_times` (nonexistent → suppressed(dst); duplicated → first), `fixed_interval` anchors, `stratified` tiling of 23/25-h days, and quiet zones spanning transitions.
- `fold_vectors.json`: event lists → expected folded rows (duplicated event lines from restore, duplicate answers across devices, snooze-then-answer on another device, supersedes chains, retraction, suppressed-vs-unobserved precedence, retroactive expiry, late computation).
- `export_vectors/`: event logs + surveys → expected CSV bytes.
- `config_validation.json`: valid/invalid config documents.
- `quick_vectors.json`: local-day candidate lists and `n` → full/quick per sample.

CI runs the Python test-suite against the vectors and the Kotlin unit tests against the same files.

---

## 14. Exports

`exports/{stream_id}.csv`, UTF-8, RFC 4180 quoting, header:

```
sample_id,scheduled_utc,scheduled_local,status,prior_status,late,test,partial,answered_at,latency_s,snoozes,fired_on,answered_on,config_version,survey_version,lat,lon,loc_accuracy_m,loc_age_s,f_<field_id>...
```

- `status` vocabulary as in §5.2; `scheduled` (future) rows are not exported.
- `fired_on` is `;`-joined device ids; multi-value answers `;`-joined (safe because tags and option values cannot contain `;`); newlines preserved inside quoted text.
- Rows with `test=true` or `status=retracted` are **included** (every generated ping gets a row); the analysis package excludes them by default.
- `{stream_id}.columns.json`: `[{column, field_id, type, survey_versions:[..]}]` for `f_` columns.
- Sorted by `scheduled_utc` ascending. Regenerated in full on each sync that changed the stream, by whichever device performed the sync.

---

## 15. Analysis library (out of app scope, in repo)

`analysis/` Python package: `load(folder) -> {stream: DataFrame}` reading exports or folding raw events (from a Drive mirror or a downloaded copy); helpers for response-rate, latency distributions, tag explosion, calendar heatmaps; default filters exclude `test` and `retracted`. Notebooks live here. No survey-content analysis in the clients; clients only show cadence/response stats.

---

## 16. Repository layout

```
personal-experience-sampler/
  README.md  LICENSE (MIT)  SPECIFICATION.md  PROJECT_PREFERENCES.md
  spec/                      # conformance fixtures (§13)
  android/                   # Gradle project
    app/src/main/java/.../   # packages: core (scheduler, fold, models), data (Room, cloud), ui, alarm, sync
    app/src/test/            # unit tests incl. conformance
  desktop/
    pes/                     # package: core (scheduler, fold, models), store, ui, tray, sync, snapshot
    tests/
    pyproject.toml
  analysis/
    pes_analysis/  notebooks/
  .github/workflows/         # android-build.yml, desktop-build.yml, conformance.yml
```

`core` modules in both languages mirror each other file-for-file where practical (`protocols/poisson.py` ↔ `protocols/Poisson.kt`).

---

## 17. Milestones

1. **Core + conformance**: models, PRNG, scheduler with 4 protocols (incl. DST and collision rules), fold, CSV export, fixtures passing in Python and Kotlin.
2. **Desktop MVP**: tray, fires pings, Answer window, local DB, local-folder `CloudStore` (for development), config/survey editors, backlog/history.
3. **Drive store**: Google Cloud project (production consent screen, `drive.file`), REST `CloudStore` in Python and Kotlin, root-folder discovery, sync procedure.
4. **Android MVP**: alarms, notifications (with conditional inline reply), Answer screen, local DB, permissions checklist.
5. **Sync hardening**: conflicts, stale config, cross-device duplicate handling, retroactive expiry, restore, snapshots.
6. **Polish**: ping calendar, stream summaries, dry-run preview, test ping, quiet mode timer, themes.
7. **Analysis package** and notebooks.

---

## 18. Deferred / explicitly out of scope for v1

- Multiple surveys per stream beyond quick/full; conditional fields.
- True hierarchical tags; cross-stream minimum spacing; min gap across UTC midnight.
- Automatic timezone following.
- Encryption of cloud data.
- Device context capture beyond location (foreground app, screen state, sensors).
- iOS client.
- Web UI.
