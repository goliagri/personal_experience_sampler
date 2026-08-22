# Personal Experience Sampler

A single-user experience-sampling app (TagTime generalized): configurable
protocols (Poisson, stratified, fixed-interval, fixed-times) generate ping
times for named streams; you answer a custom survey per ping. Android and
desktop clients compute identical schedules independently from a shared seed —
no server; data syncs through a Google Drive folder and exports as per-stream
CSVs.

Authoritative documents: [`SPECIFICATION.md`](SPECIFICATION.md) (design) and
[`PROJECT_PREFERENCES.md`](PROJECT_PREFERENCES.md) (intent);
[`TEST_PLAN.md`](TEST_PLAN.md) defines the verification suite.

## Layout

- `spec/` — language-neutral conformance fixtures both clients must pass
  (PRNG, fdlibm-ln bit patterns, schedules, folds, CSV exports, config
  validation). Regenerate with `python3 spec/tools/generate_vectors.py`;
  regenerated vectors must only change together with a reviewed core change.
- `desktop/` — Python 3.11+ package: `pes/core/` (shared deterministic core),
  `pes/store/` (SQLite + CloudStore), `pes/engine.py` (headless runtime:
  firing, snooze/expiry, backfill), `pes/sync.py` (§8.4 procedure), and
  `pes/ui/` (tkinter client). Run it with `cd desktop && python -m pes`
  (`--data-dir`, `--cloud-dir` to relocate). Sync targets a local folder by
  default; to use Google Drive, pass `--client-secret <path-to-oauth-json>`
  once, then Settings > "Connect Google Drive..." (browser consent; the token
  is stored locally, never synced). Tests: `cd desktop && python -m pytest`
  (conformance + property + multi-device scenario suites).
- `android/` — Kotlin Gradle project. `:core` is a pure-JVM module mirroring
  `desktop/pes/core` file-for-file; it runs the same `spec/` fixtures
  (`cd android && ./gradlew :core:test`). `:runtime` (also pure JVM) mirrors
  `desktop/pes/{engine,sync,store}` — the headless engine, the §8.4 sync
  procedure, the local db, and the Drive REST store — with the scenario
  suites as JVM tests (`./gradlew :runtime:test`). `:app` is the Android
  client (Jetpack Compose): exact alarms via `setExactAndAllowWhileIdle`,
  ping notifications with inline tags reply, the Answer screen, a permissions
  checklist, and hourly WorkManager sync to Google Drive (authorized through
  Google Identity — no client secret ships in the app). Build with
  `./gradlew :app:assembleDebug`; the first build downloads the Android SDK
  to the path in `local.properties`.
- `analysis/` — offline analysis package (later milestone).

## Development

```
pip install pytest hypothesis
cd desktop && python -m pytest                    # Python conformance + property tests
cd android && ./gradlew :core:test :runtime:test  # Kotlin conformance + scenario tests (JDK 17+)
cd android && ./gradlew :app:assembleDebug        # Android APK
```

## Privacy

Personal Experience Sampler is a self-hosted, single-user application. It
stores data only in the user's own Google Drive via the `drive.file` scope; no
data is transmitted to, collected by, or shared with the developer or any
third party.
