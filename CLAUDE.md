# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project state

**Milestones 1–4 implemented** (shared core + conformance in Python and Kotlin; desktop MVP; Drive store: library-free OAuth in `desktop/pes/store/oauth.py` + REST `DriveStore` in `drive.py`; Android MVP: `android/runtime` is a pure-JVM Kotlin port of engine/sync/db/cloud with the scenario suites mirrored as JVM tests, `android/app` is the Compose client — exact alarms, notifications with conditional inline reply, Answer screen, permissions checklist, WorkManager sync, Kotlin `DriveStore` + Google Identity auth). M4's manual on-phone checklist and the Android OAuth client registration (needs the app SHA-1) are still pending with the owner. Next per §17: sync hardening (M5). Authoritative documents:

- `SPECIFICATION.md` — the full technical spec: data model, scheduler, sync, clients, repo layout, milestones.
- `PROJECT_PREFERENCES.md` — the owner's intent and priorities. **Read it first**; when the spec is silent or ambiguous, resolve in the direction of these preferences.
- `TEST_PLAN.md` — the verification suite: conformance-vector inventory, cross-device scenario tests, properties, manual checklist, and per-milestone done-gates. Hard cases are marked **[H]**; implement those tests first.

Commands:

- Python tests: `cd desktop && python -m pytest` (needs `pytest hypothesis requests`; subsets: `tests/conformance`, `tests/properties`, `tests/scenarios`; single test: `python -m pytest tests/scenarios/test_expiry_snooze.py::test_fire_expire_backlog`).
- Kotlin tests: `cd android && ./gradlew :core:test :runtime:test` (JDK 17+; `:runtime:test` is the mirrored scenario suite). Android build: `./gradlew :app:assembleDebug` (SDK auto-installs to the path in `local.properties`).
- Regenerate conformance vectors (from repo root): `python3 spec/tools/generate_vectors.py` — only alongside a reviewed core change, and any core change must keep **both** languages' cores and suites in step.
- Run the desktop app: `cd desktop && python -m pes` (`--data-dir`, `--cloud-dir`).
- Lint: `ruff check desktop/pes desktop/tests`.

Key desktop layering: `pes/core/` is pure and deterministic (mirrored file-for-file by `android/core`); `pes/engine.py` is the headless runtime (injected clock + notifier — scenario tests drive it with `FakeClock`/`RecordingNotifier`, two SimDevices sharing one folder); `pes/sync.py` implements §8.4 over `pes/store/cloud.py`'s `CloudStore`; `pes/ui/` (tkinter) and `pes/tray.py` sit on top and contain no scheduling/fold logic.

The Android side mirrors this layering exactly: `android/runtime` (pure JVM) ports `engine.py`→`Engine.kt`, `sync.py`→`Sync.kt`, `store/db.py`→`store/Db.kt` (same SQL schema, over androidx.sqlite's bundled driver so it runs in JVM tests and on-device), `store/cloud.py`→`store/Cloud.kt`, `store/drive.py`→`store/Drive.kt` (injectable `HttpSession`; `Http.kt` has the HttpURLConnection session + `TokenSource`/`AuthorizedSession`). Keep the Python and Kotlin runtime layers in step the same way the cores are kept in step. `android/app` holds only platform glue: `PesApp`/`EngineHost` (engine on one dedicated thread; the sync worker opens its own Db — WAL), `Alarms` (one `setExactAndAllowWhileIdle` at `engine.nextWake`), `Notifications` (inline tags reply only when §10.3 allows), Compose screens, `DriveConnection` (Google Identity AuthorizationClient — no client secret in the app).

## What this is

A single-user experience-sampling app (TagTime generalized): configurable **protocols** (Poisson, stratified, fixed-interval, fixed-times) generate ping times for named **streams**; the user answers a custom **survey** per ping. Two clients (Android Kotlin, desktop Python 3.11+/tkinter) compute identical schedules independently from a shared seed — no server. Data syncs through a Google Drive folder and exports as per-stream CSVs.

## Design invariants (violating these breaks the design)

- **Local-first**: the ping/answer path never touches the network; sync is a separate best-effort step.
- **Deterministic scheduling**: xoshiro256** seeded via SplitMix64 from SHA-256(stream_seed + scope), re-seeded per protocol-defined scope (e.g. per UTC day) so config changes on day D don't shift later days. `ln` must be fdlibm's (Kotlin `StrictMath.log`; Python ships a pure-Python fdlibm port, not `math.log`) so schedules are bit-identical, verified by shared fixtures in `spec/`.
- **Append-only per-device event logs**: each device writes only its own `events/<device_id>/*.jsonl` (the restore procedure is the sole exception and may create but never overwrite others' files); sample state is a deterministic **fold** of all events, deduplicated by identity key first (precedence: retracted > answered > skipped > expired > suppressed > unobserved > pending). A filled-out sample is never overwritten by an unfilled one.
- **Honest accounting**: every generated ping gets a row — suppressed (quiet zone/mode), unobserved (no device running), skipped, and expired pings are all recorded. "Suppressed" ≠ "off": a disabled stream generates nothing; a quiet zone suppresses pings that are still logged. Latency is always measured from the original scheduled time, regardless of snoozes.
- **Derived artifacts are disposable**: CSV exports and folded tables are pure functions of the event logs, regenerable by any device.
- **One configured timezone**, changed only manually; all timestamps in logs are UTC ISO-8601 with `Z`, second resolution.
- Surveys are **immutable per version**; field ids are stable across versions.

## Cross-language conformance

The `spec/` directory (once created) holds language-neutral test vectors (PRNG, schedules, folds, CSV exports, config validation) that **both** the Kotlin and Python implementations must pass. Any new protocol or fold change must ship with updated vectors. `core` modules mirror each other file-for-file across languages where practical (`protocols/poisson.py` ↔ `protocols/Poisson.kt`).

## Owner priorities to keep in mind

- The answer flow must be fast and frictionless above almost everything else: one scrolling page, no animations.
- Prefer simple deterministic mechanisms over clever automatic behavior.
- Late answers are allowed but the UI must make it impossible to mistake an old sample for a new one (backlog/history only, original time prominent).
- No survey-content analysis in the clients — only cadence/response stats; real analysis lives in the separate `analysis/` Python package.
- Android must not burn battery: exact alarms only, no polling, no foreground service.
