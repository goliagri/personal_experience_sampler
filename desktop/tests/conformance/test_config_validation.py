"""Conformance: spec/config_validation.json (§13)."""

import json

from pes.core.config_validation import validate_config


def test_all_cases(spec_dir):
    doc = json.loads((spec_dir / "config_validation.json").read_text())
    for case in doc["cases"]:
        surveys = [tuple(s) for s in case["surveys"]]
        got = validate_config(case["config"], surveys, case["now"])
        assert got == case["expected_errors"], case["name"]
