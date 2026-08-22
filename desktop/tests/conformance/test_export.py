"""Conformance: spec/export_vectors/ (§13). Byte-exact CSV + columns.json,
produced from the raw event logs via the fold (the full §14 pipeline).
"""

import json

from pes.core.export import columns_json, export_csv
from pes.core.fold import fold_sample


def test_export_cases(spec_dir):
    for case_dir in sorted((spec_dir / "export_vectors").iterdir()):
        if not case_dir.is_dir():
            continue
        spec = json.loads((case_dir / "input.json").read_text())
        by_sample = {}
        for path, events in spec["events"].items():
            for i, ev in enumerate(events):
                by_sample.setdefault(ev["sample"], []).append((path, i, ev))
        rows = [fold_sample(evs, spec["expiry_minutes"]) for evs in by_sample.values()]
        for row in rows:
            row.pop("warnings")
        surveys = {(s["id"], s["version"]): s for s in spec["surveys"]}
        csv_bytes, columns = export_csv(rows, surveys, spec["timezone"])
        assert csv_bytes == (case_dir / "expected.csv").read_bytes(), case_dir.name
        assert columns_json(columns) == (case_dir / "expected.columns.json").read_bytes(), case_dir.name
