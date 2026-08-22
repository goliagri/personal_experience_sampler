# Personal Experience Sampler — Test Plan

Companion to `SPECIFICATION.md` v0.3. This plan defines what must be verified, not how each assertion is written; implementers fill in between the lines. It references spec sections rather than restating rules — if a test here seems to contradict the spec, the spec wins and this file has drifted.

Layers, from most to least automated:

1. **Conformance vectors** (`spec/`, §13) — language-neutral fixtures both implementations must pass byte-for-byte. The cross-language agreement layer.
2. **Scenario tests** — per-client integration tests of multi-step, multi-device sequences, run against the local-folder `CloudStore`.
3. **Property-based tests** — invariants checked over generated inputs.
4. **Manual release checklist** — what automation can't reach cheaply (alarms under Doze, OAuth, hibernate).

Priority marker: **[H]** = a foreseen hard case from design review; write these first.

---

## 1. Conformance vector inventory (`spec/`)

Each bullet becomes one or more entries in the named fixture file. Both the Python and Kotlin suites load the same files (CI job `conformance.yml`).

### 1.1 `prng_vectors.json` (§6.2)

- [ ] SplitMix64: ≥3 seeds (0, 1, a large odd value) → first 64 outputs.
- [ ] xoshiro256**: state from each SplitMix64 seed → first 64 outputs.
- [ ] Seed derivation: (`stream_seed`, `scope`) → `seed_u64`, covering both scope forms (`day:…`, `interval:…:k`).
- [ ] Uniform-double mapping: u64 bit patterns → expected doubles (include 0, max, and a value exercising the 53-bit shift).

### 1.2 `log_vectors.json` (§6.2)

- [ ] fdlibm `ln` outputs as IEEE-754 bit patterns for inputs spanning: subnormal-adjacent, just below 1, just above 1, typical `1-u` values, and values known to differ between fdlibm and common libm builds. **[H]** — this is what licenses the "bit-identical schedules" claim; the Python port must match `StrictMath.log` exactly on every entry.
- [ ] End-to-end draw: (mean_seconds, u) → `gap_seconds`, including a case where the product lands within 1e-9 of an integer (floor boundary).

### 1.3 `schedule_vectors.json` (§6.1–6.3)

For each vector: (stream config incl. timezone, seed, local day) → full resolved candidate list with per-candidate flags (`suppressed` reason or none).

**Poisson**
- [ ] Plain day, no min gap: exact times.
- [ ] **[H]** Min gap active within a UTC day: candidate shifted to `prev + min_gap`, process continues correctly after the shift.
- [ ] **[H]** Cross-UTC-midnight pair closer than min gap: *not* shifted (min gap not enforced across 00:00Z).
- [ ] **[H]** Local day spanning two UTC days: both UTC days generated, candidates filtered to local-day bounds, none dropped or duplicated at the seam.
- [ ] **[H]** Config change mid-day (`effective_from` at e.g. 15:00): piecewise generation; day D+1 unaffected by the change (re-seeding independence).
- [ ] Seed edit: same day, different seed → different times (and old seed still reproduces old times).

**Stratified**
- [ ] Normal 24 h day: intervals tile from local midnight, offsets sorted.
- [ ] **[H]** 23 h and 25 h local days: correct tiling, trailing partial interval gets `floor(pings * fraction)`.
- [ ] Interval scope re-seeding: changing one interval's config leaves other intervals' draws unchanged.

**fixed_interval / fixed_times**
- [ ] Basic emission incl. `days` filter (fixed_times) and anchor resolution (fixed_interval).
- [ ] **[H]** Spring-forward: nonexistent local time → candidate at pre-transition-offset instant, flagged `suppressed(dst)`.
- [ ] **[H]** Fall-back: duplicated local time → first occurrence only.
- [ ] **[H]** fixed_interval emitting *through* a transition: intervals stay `every_minutes` apart in UTC (wall-clock gap visibly shifts).

**Scheduler core (all protocols)**
- [ ] **[H]** Same-second collision: later-in-generation-order candidate shifted +1 s, repeated until unique; fixed_times ordering by `times_local` position.
- [ ] **[H]** DST-resolved candidate colliding with a real candidate at the same second.
- [ ] **[H]** Collision shift at 23:59:59 of the local day: pin the intended behavior (shifted candidate remains assigned to the generating day).
- [ ] Quiet zone: plain window; midnight-wrapping window (`from > to`); window spanning a DST transition (an hour shorter/longer, §4).
- [ ] Quiet zone applies to fixed_times (suppressed, not skipped).
- [ ] Disabled stream → empty candidate list.

### 1.4 `fold_vectors.json` (§5.2)

Each vector: raw event lines (possibly across multiple simulated files) → expected folded row.

- [ ] Status precedence: one vector per adjacent pair in the precedence chain, including **[H]** `suppressed` beating `unobserved`.
- [ ] **[H]** Deduplication: identical lines present in `events/**` and `restored/**` → snooze count and answer chains unaffected; same-key-different-payload → first by file-path order kept.
- [ ] **[H]** Snooze on device A + answer on device B: one sample, `snoozes=1`, `answered_on=B`, latency from original `scheduled_utc`.
- [ ] **[H]** Duplicate answers on two devices: earliest-root chain wins, loser marked `duplicate_answer` and retained.
- [ ] **[H]** Supersedes chain (edit, then edit again, incl. an edit from a different device): latest in chain is effective.
- [ ] Retraction: `status=retracted`, `prior_status` preserved; retraction of an answered vs. a pending sample.
- [ ] **[H]** Retroactive expiry state: `fired` + `unobserved` + later `expired` → `expired` (the §8.4-step-3 outcome).
- [ ] `late` computation from effective settings at `scheduled_utc` (per-stream override vs. global default), and `late=false` exactly at the boundary.
- [ ] `partial` = winning answer's flag; `test` = any fired.test; `observed`, `fired_on` aggregation.
- [ ] `answered` with `partial: true` and only the tags field → valid row, other `f_` columns empty.
- [ ] Stale config: `suppressed(stale_config)` + `answered` → `answered` wins.

### 1.5 `quick_vectors.json` (§7)

- [ ] `i mod n` over a day's resolved candidate list; first ping of the day full.
- [ ] **[H]** Suppressed/DST candidates counted in the index (a quiet-zone candidate occupying `i=0`).
- [ ] Day with fewer than `n` candidates → exactly one full ping.
- [ ] **[H]** Mid-day config change: index runs over the concatenated piecewise list (pin this — it is only implied by §6.1/§7).
- [ ] `n == 1` → all full; stream with no `quick` field → first `tags` field used.

### 1.6 `export_vectors/` (§14)

- [ ] Golden CSV bytes for a stream exercising: all four field types; multi-value `;` joins; embedded newline in quoted text; RFC 4180 quoting of `"` and `,`; missing optional fields.
- [ ] `test=true` and `status=retracted` rows present; future `scheduled` rows absent.
- [ ] Survey version change mid-stream: `f_` columns are the union; `columns.json` lists `survey_versions` per column.
- [ ] Sort order and exact header.

### 1.7 `config_validation.json` (§8.1)

- [ ] Valid reference document (the §8.1 example).
- [ ] Invalid: duplicate stream ids; malformed seed; `from == to` quiet zone; out-of-range protocol params; dangling survey reference; past `effective_from`; bad `HH:MM`.
- [ ] Valid edge: midnight-wrapping quiet zone; `overrides` subset; empty `streams`.

---

## 2. Scenario tests (per client, local-folder CloudStore)

Multi-step sequences driven through the real sync/backfill/scheduler code with a simulated clock and two or more simulated devices sharing one folder. Assert on folded state and on the event lines written.

**Sync and merge**
- [ ] **[H]** Phone snoozes, goes offline; laptop answers; both sync → single sample, merged as in fold vector 1.4, no duplicate notification on phone after sync (notification cancelled per §8.4 step 3).
- [ ] **[H]** An `answered` sample is never downgraded: replay every other event type after an answer, across devices and syncs.
- [ ] **[H]** Backfill-before-knowledge: laptop backfills a window as `unobserved`, then imports phone's `quiet_changed` history and `suppressed` events → refold yields `suppressed`; laptop's stale `unobserved` line remains in its log (append-only) but loses the fold.
- [ ] **[H]** Retroactive expiry via refold (§8.4 step 3): phone fires, never syncs until after expiry; laptop logs `unobserved`; phone's events arrive → importing device logs `expired` even though its backfill watermark has passed; both devices doing so folds to one status.
- [ ] Month-file upload: only months with unsynced rows re-uploaded; re-upload idempotent (byte-identical file).
- [ ] Export regeneration triggered only for streams whose folded rows changed.

**Config**
- [ ] **[H]** Concurrent config edit from two devices branching the same `base_version`: later `written_at` wins, loser lands in `config/conflicts/`, both clients surface the warning, rejected version re-appliable as a new version.
- [ ] Config change with future `effective_from`: schedule regenerates piecewise; no already-fired sample is disturbed.
- [ ] **[H]** Stale config (§6.4): device offline through a config change fires under the old version; on sync logs `suppressed(stale_config)`; answers survive.
- [ ] Timezone change as a config change: quiet zones and local-day tiling switch at `effective_from`, not before.

**Backfill and clock**
- [ ] All devices off overnight: on wake, exactly one row per generated candidate — `unobserved`, or `suppressed` with correct reason; watermark advances; second backfill run adds nothing.
- [ ] Clock jump (desktop sleep/hibernate simulation): backfill triggered, window covered exactly once.
- [ ] Expiry while device awake: `expired` logged at the instant, notification cancelled, sample enters backlog; backlog excludes samples older than `backlog_hours`.
- [ ] Snooze refusal: within `snooze_minutes` of expiry, and at `max_snoozes`.

**Restore (§8.6)**
- [ ] **[H]** Cloud folder deleted; single surviving device restores: missing files recreated via `put_if_absent`; fold over restored cloud equals fold before loss.
- [ ] **[H]** Partial loss with divergence: cloud file exists but lacks lines the cache has → extra lines land in `restored/{from}/{dev}/…`; existing cloud files untouched; fold over `events/** + restored/**` complete and dedup-correct.
- [ ] Restore run twice, and from two different devices → idempotent fold (no double counts).

**Drive store (Milestone 3, mockable HTTP layer)**
- [ ] Root folder: created on first run; adopted by ID thereafter; rename in Drive harmless; two roots found → refuse and prompt.
- [ ] `modifiedTime`-gated download; interrupted upload retried on next trigger without corruption (idempotence, §8.4).

**Test ping & quick surveys**
- [ ] "Fire test ping now": real sample at current second, `test: true` on `fired`, flows through answer path, exported with `test=true`.
- [ ] Quick/full presentation matches `quick_vectors` assignment end-to-end (a quick ping renders only `quick` fields; inline reply only offered when first field is `tags` and nothing else `required`).

---

## 3. Property-based tests

Run with generated event logs / configs (both languages where the subject is shared core; Python-only acceptable for store-level properties).

- [ ] **Fold idempotence & order-invariance**: folding any event multiset equals folding it shuffled, split across files differently, or with any subset of lines duplicated. **[H]** (this is the theorem the whole sync design rests on)
- [ ] **Backfill idempotence**: running backfill twice over the same window emits no second event.
- [ ] **Sync idempotence**: every §8.4 step re-run against an unchanged cloud is a no-op.
- [ ] **Completeness**: for any config history and window, every generated candidate ends with exactly one folded row; no candidate appears in two local-day windows (seam property, incl. DST days).
- [ ] **Min gap**: within any UTC day, consecutive Poisson emissions ≥ `min_gap`.
- [ ] **Statistical sanity** (fixed seed, wide tolerance): Poisson empirical mean gap ≈ `mean_gap_minutes`; stratified count per full interval = `pings_per_interval`; uniform offsets pass a coarse KS check. These guard against off-by-factor bugs, not distribution purity.
- [ ] **Schedule stability**: regenerating any past day under an unchanged config history is byte-identical to its first generation.
- [ ] **Export round-trip**: CSV parse → values equal folded rows (tags/choice `;` split-safe by charset).

---

## 4. Manual release checklist

Not automated; run before each tagged release on the real target devices (Samsung Galaxy for Android; Windows, then Linux, for desktop).

**Android**
- [ ] Ping fires within seconds of schedule with app swiped away, screen off, in Doze (leave idle ≥1 h).
- [ ] Survives Samsung battery management with the documented settings (never-sleeping apps); permissions checklist correctly detects each missing grant.
- [ ] `BOOT_COMPLETED`: reboot phone across a scheduled ping → ping appears as backfilled, next pings reschedule.
- [ ] Notification: inline tags reply submits `partial` answer; Open/Snooze/Skip actions; snoozed notification shows "snoozed ×n" + original time; two near-simultaneous streams both visible and distinguishable.
- [ ] Expiry alarm cancels the notification.
- [ ] Drive OAuth: first-run consent (production screen, no unverified warning), token survives >7 days, revoke-and-reconnect works.
- [ ] Location `coarse`/`precise` capture and the no-permission fallback; battery drain over 48 h of normal pinging is negligible.

**Desktop**
- [ ] Tray-resident across sleep/hibernate: pings during sleep appear as backfilled on wake, no burst of stale notifications.
- [ ] Toast → Answer window on Windows and `notify-send` path on Linux; keyboard-only answer flow (Enter on last field submits).
- [ ] Autostart (Startup folder / XDG); PyInstaller exe runs on a machine without Python.
- [ ] Weekly snapshot fires (clock set past Sunday 03:00), monthly promotion, retention pruning; primary-role handoff after 14 days and the lexicographic tie-break.

**Cross-device (one phone + one laptop, real Drive)**
- [ ] Same seed → same pings observed on both for a full day.
- [ ] Snooze-on-phone / answer-on-laptop merge, live.
- [ ] Config edit on laptop picked up by phone by `effective_from`.
- [ ] Restore drill: delete the Drive folder, restore from the laptop, verify the phone re-adopts and data is intact.
- [ ] Late-answer safeguards: an expired sample is reachable only via Backlog/History and shows the LATE banner with original time.

---

## 5. Milestone gates

A milestone (§17) is done when:

1. **Core + conformance** — every §1 fixture file exists with all **[H]** entries populated, and both suites pass all of them; §3 fold/backfill/completeness properties pass.
2. **Desktop MVP** — §2 backfill/expiry/snooze scenarios pass against the local-folder store; manual desktop checklist (except snapshots) passes once.
3. **Drive store** — §2 Drive-store and config-conflict scenarios pass; cross-device OAuth items verified once manually.
4. **Android MVP** — manual Android checklist passes on the target phone; scenario suite runs on the Kotlin side for sync/merge cases.
5. **Sync hardening** — restore, stale-config, and retroactive-expiry scenarios pass on both clients; restore drill done once against real Drive.
6. **Polish / Analysis** — export round-trip property passes; analysis `load()` reads a real folder and applies default `test`/`retracted` filters.

New protocols or fold changes after Milestone 1 must land with new vectors in the same commit (§6.1, §13).
