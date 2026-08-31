# Tier 3 findings

One file per charter run (see `../CHARTERS.md`). A finding is closed when the fix
has landed **and** the test named in its `Test:` line exists; otherwise it stays
here as a known issue or an open question for the owner.

| Run | Charter | Findings | Outcome |
|---|---|---|---|
| 2026-08-30 | [C1 — answer flow](C1-answer-flow.md) | 8 | 5 fixed (suggestion wrap, `choice.display`, IME insets, numeric keyboard, Enter/next); 1 open question (tag case), 1 owner call (action bar), 1 superseded by C6 F1 |
| 2026-08-30 | [C2 — late vs. fresh](C2-late-vs-fresh.md) | 5 | 4 fixed (route-based LATE banner, frozen banner, stale notification, snooze-after-expiry); 1 accepted (Backlog returns to Backlog) |
| 2026-08-30 | [C3 — accounting](C3-accounting.md) | 3 | 2 fixed (`unobserved` not terminal — shared core; backlog label); 1 open question (stale clock after reboot) |
| 2026-08-30 | [C4 — config surfaces](C4-config-surfaces.md) | 9 | 8 fixed (Home staleness, checklist staleness, timed quiet ×2, ping calendar, protocol summary, snapshot role, test-ping message); 1 accepted (schedule reveal shows ids — both clients) |
| 2026-08-30 | [C5 — degradation](C5-degradation.md) | 6 | 5 fixed (silent sync, invisible sync failure, engine-thread crash, unknown protocol, alarm-consequence wording); 1 open question (future config versions) |
| 2026-08-30 | [C6 — presentation](C6-presentation.md) | 8 | 7 fixed (IME viewport collapse, landscape, phantom radio select, status palette, dark calendar, draft loss, tag message, focus vs. LATE banner); 1 open question (resume the Answer screen after process death) |

Open questions for the owner are collected at the end of each file; they are
deliberately **not** turned into tests until they are decided.
