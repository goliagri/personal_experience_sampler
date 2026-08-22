"""Per-stream CSV export (spec §14).

Pure function of folded rows + surveys + timezone. Byte-exact output is pinned
by ``spec/export_vectors/``: UTF-8, RFC 4180 (CRLF line endings, minimal
quoting, ``""`` escaping), sorted by ``scheduled_utc`` ascending.

Pinned encoding details (the spec leaves them to the fixtures):
- booleans are ``true`` / ``false``;
- missing values are empty cells;
- multi-value answers are ``;``-joined (tag/option charset cannot contain ;);
- ``scheduled_local`` is local wall-clock ``YYYY-MM-DDTHH:MM:SS`` (no offset);
- numbers use their shortest exact representation (Python ``repr`` semantics),
  integers without a decimal point;
- ``f_`` columns are the union over the survey versions used by the stream's
  rows, ordered by first appearance scanning versions in ascending order.
"""

from __future__ import annotations

import json
from zoneinfo import ZoneInfo

from .timeutil import fmt_local, parse_utc

FIXED_COLUMNS = [
    "sample_id",
    "scheduled_utc",
    "scheduled_local",
    "status",
    "prior_status",
    "late",
    "test",
    "partial",
    "answered_at",
    "latency_s",
    "snoozes",
    "fired_on",
    "answered_on",
    "config_version",
    "survey_version",
    "lat",
    "lon",
    "loc_accuracy_m",
    "loc_age_s",
]


def field_columns(rows: list[dict], surveys: dict[tuple[str, int], dict]) -> list[dict]:
    """The ``f_`` column descriptors for a stream (also ``columns.json``)."""
    versions_used = sorted(
        {(r["survey_id"], r["survey_version"]) for r in rows if r.get("survey_id")},
        key=lambda sv: sv[1],
    )
    columns: list[dict] = []
    by_field: dict[str, dict] = {}
    for survey_key in versions_used:
        survey = surveys[survey_key]
        for field in survey["fields"]:
            fid = field["id"]
            if fid not in by_field:
                descriptor = {
                    "column": f"f_{fid}",
                    "field_id": fid,
                    "type": field["type"],
                    "survey_versions": [survey_key[1]],
                }
                by_field[fid] = descriptor
                columns.append(descriptor)
            else:
                by_field[fid]["survey_versions"].append(survey_key[1])
    return columns


def _cell(value) -> str:
    if value is None:
        return ""
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, list):
        return ";".join(str(v) for v in value)
    if isinstance(value, float):
        return repr(value)
    return str(value)


def _quote(cell: str) -> str:
    if any(c in cell for c in ',"\r\n'):
        return '"' + cell.replace('"', '""') + '"'
    return cell


def export_csv(
    rows: list[dict], surveys: dict[tuple[str, int], dict], timezone: str
) -> tuple[bytes, list[dict]]:
    """Render (csv_bytes, columns_descriptors) for one stream's folded rows.

    ``rows`` are fold output dicts; ``scheduled`` (future) rows must not be
    passed. ``surveys`` maps (survey_id, version) -> survey document.
    """
    tz = ZoneInfo(timezone)
    columns = field_columns(rows, surveys)
    header = FIXED_COLUMNS + [c["column"] for c in columns]

    lines = [",".join(header)]
    for row in sorted(rows, key=lambda r: r["scheduled_utc"]):
        loc = row.get("loc") or {}
        cells = [
            row["sample"],
            row["scheduled_utc"],
            fmt_local(parse_utc(row["scheduled_utc"]), tz),
            row["status"],
            row.get("prior_status"),
            row.get("late", False),
            row.get("test", False),
            row.get("partial", False),
            row.get("answered_at"),
            row.get("latency_s"),
            row.get("snoozes", 0),
            row.get("fired_on", []),
            row.get("answered_on"),
            row.get("config_version"),
            row.get("survey_version"),
            loc.get("lat"),
            loc.get("lon"),
            loc.get("acc_m"),
            loc.get("age_s"),
        ]
        answers = row.get("answers", {})
        cells += [answers.get(c["field_id"]) for c in columns]
        lines.append(",".join(_quote(_cell(c)) for c in cells))

    return ("\r\n".join(lines) + "\r\n").encode("utf-8"), columns


def columns_json(columns: list[dict]) -> bytes:
    """Byte-exact ``{stream_id}.columns.json`` rendering."""
    return (json.dumps(columns, indent=2) + "\n").encode("utf-8")
