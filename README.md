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
  (`--data-dir`, `--cloud-dir` to relocate; the cloud is a local folder until
  the Drive store lands at Milestone 3). Tests: `cd desktop && python -m pytest`
  (conformance + property + multi-device scenario suites).
- `android/` — Kotlin Gradle project. `core` is a pure-JVM module mirroring
  `desktop/pes/core` file-for-file; it runs the same `spec/` fixtures
  (`cd android && ./gradlew :core:test`). The `:app` Android module arrives at
  Milestone 4.
- `analysis/` — offline analysis package (later milestone).

## Development

```
pip install pytest hypothesis
cd desktop && python -m pytest          # Python conformance + property tests
cd android && ./gradlew :core:test      # Kotlin conformance + property tests (JDK 17+)
```

## Privacy

Personal Experience Sampler is a self-hosted, single-user application. It
stores data only in the user's own Google Drive via the `drive.file` scope; no
data is transmitted to, collected by, or shared with the developer or any
third party.
