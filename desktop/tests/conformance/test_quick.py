"""Conformance: spec/quick_vectors.json (§13)."""

import json

from pes.core.quick import is_full, presented_fields


def test_index_cases(spec_dir):
    doc = json.loads((spec_dir / "quick_vectors.json").read_text())
    for case in doc["index_cases"]:
        got = [is_full(i, case["n"]) for i in range(case["count"])]
        assert got == case["expected_full"], case["name"]
        if case["count"] >= 1:
            assert got[0] is True  # first ping of the day is always full


def test_field_cases(spec_dir):
    doc = json.loads((spec_dir / "quick_vectors.json").read_text())
    for case in doc["field_cases"]:
        got = [f["id"] for f in presented_fields(case["survey"], case["full"])]
        assert got == case["expected_field_ids"], case["name"]
