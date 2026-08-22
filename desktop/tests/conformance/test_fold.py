"""Conformance: spec/fold_vectors.json (§13)."""

import json

from pes.core.fold import fold_sample


def test_all_cases(spec_dir):
    doc = json.loads((spec_dir / "fold_vectors.json").read_text())
    for case in doc["cases"]:
        flat = [
            (path, i, ev)
            for path, events in case["files"].items()
            for i, ev in enumerate(events)
        ]
        row = fold_sample(flat, case["expiry_minutes"])
        warnings = row.pop("warnings")
        assert row == case["expected"], case["name"]
        assert len(warnings) == case["expected_warning_count"], case["name"]
