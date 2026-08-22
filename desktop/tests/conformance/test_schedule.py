"""Conformance: spec/schedule_vectors.json (§13)."""

import json
from datetime import date

from pes.core.scheduler import resolve_day
from pes.core.timeutil import fmt_utc


def load_cases(spec_dir):
    return json.loads((spec_dir / "schedule_vectors.json").read_text())["cases"]


def test_all_cases(spec_dir):
    for case in load_cases(spec_dir):
        resolved = resolve_day(
            case["config_history"], case["stream"], date.fromisoformat(case["local_day"])
        )
        got = [
            {
                "scheduled_utc": fmt_utc(r.scheduled_utc),
                "suppressed": r.suppressed_reason,
                "config_v": r.config_v,
                "index": r.index,
            }
            for r in resolved
        ]
        assert got == case["expected"], case["name"]


def test_case_names_unique(spec_dir):
    names = [c["name"] for c in load_cases(spec_dir)]
    assert len(names) == len(set(names))
