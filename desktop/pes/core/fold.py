"""Sample-state fold (spec §5.2).

``fold_sample`` reduces all events for one sample_id (across all devices and
files) to a single row. It is a pure function: order-invariant and
duplication-invariant after the identity-key deduplication step, which is the
property the whole sync design rests on.
"""

from __future__ import annotations

from .timeutil import parse_utc

# Highest wins. `pending` / `scheduled` are derived, not event types.
STATUS_PRECEDENCE = ["retracted", "answered", "skipped", "expired", "suppressed", "unobserved"]


def identity_key(ev: dict) -> tuple:
    """Dedup key (§5.2 rule 0): (dev, sample, ev, t, n?, supersedes?)."""
    key = [ev["dev"], ev.get("sample"), ev["ev"], ev["t"]]
    if ev["ev"] == "snoozed":
        key.append(ev.get("n"))
    if ev["ev"] == "answered":
        key.append(ev.get("supersedes"))
    return tuple(key)


def dedupe(events: list[tuple[str, int, dict]]) -> tuple[list[dict], list[str]]:
    """Deduplicate by identity key; first by (file path, line) order wins.

    Returns (kept events, warnings). Same key with a different payload is a
    data error: first kept, warning recorded.
    """
    ordered = sorted(events, key=lambda e: (e[0], e[1]))
    seen: dict[tuple, dict] = {}
    kept: list[dict] = []
    warnings: list[str] = []
    for path, _line, ev in ordered:
        key = identity_key(ev)
        if key in seen:
            if seen[key] != ev:
                warnings.append(f"conflicting duplicate for {key} in {path}")
            continue
        seen[key] = ev
        kept.append(ev)
    return kept, warnings


def _answer_chains(answered: list[dict]) -> tuple[dict | None, dict | None, list[dict]]:
    """Resolve supersedes chains (§5.2 rule 2).

    Returns (winning chain root, winning chain effective answer, other
    chains' effective answers). Chains are keyed by the `t` of the superseded
    event; an event whose `supersedes` target is absent starts its own chain.
    The effective answer of a chain is its latest event (ties broken by dev,
    ascending, last wins); the winning chain is the one whose root has the
    earliest `t` (tie: dev).

    The root matters separately from the effective answer: answered_at and
    latency are measured from the original submission (the root), so a later
    edit never changes them; answer content comes from the effective event.
    """
    if not answered:
        return None, None, []
    existing_ts = {ev["t"] for ev in answered}
    roots = [ev for ev in answered if not ev.get("supersedes") or ev["supersedes"] not in existing_ts]
    if not roots:
        # Corrupt data: every event supersedes another (a cycle). The fold
        # must stay total — treat all events as chain roots rather than crash
        # every future refold of this sample.
        roots = list(answered)

    def effective(root: dict) -> dict:
        current = root
        visited = {id(root)}
        while True:
            nexts = [
                ev
                for ev in answered
                if ev.get("supersedes") == current["t"] and id(ev) not in visited
            ]
            if not nexts:
                return current
            current = max(nexts, key=lambda ev: (ev["t"], ev["dev"]))
            visited.add(id(current))

    roots.sort(key=lambda ev: (ev["t"], ev["dev"]))
    winner_root, *other_roots = roots
    return winner_root, effective(winner_root), [effective(r) for r in other_roots]


def fold_sample(
    events: list[tuple[str, int, dict]], expiry_minutes: int | None = None
) -> dict:
    """Fold one sample's events into a row.

    ``events``: (source_file_path, line_index, event) triples, any order.
    ``expiry_minutes``: effective expiry at scheduled_utc (stream override ->
    global default), used only for the ``late`` computation; None disables it.
    """
    deduped, warnings = dedupe(events)
    if not deduped:
        return {"status": "scheduled", "warnings": warnings}

    sample_id = deduped[0]["sample"]
    stream_id, scheduled_utc = sample_id.split("|", 1)
    scheduled_epoch = parse_utc(scheduled_utc)

    by_type: dict[str, list[dict]] = {}
    for ev in deduped:
        by_type.setdefault(ev["ev"], []).append(ev)

    def status_ignoring_retraction() -> str:
        for status in STATUS_PRECEDENCE[1:]:
            if status in by_type:
                return status
        if "fired" in by_type or "snoozed" in by_type:
            return "pending"
        return "scheduled"

    if "retracted" in by_type:
        status = "retracted"
        prior_status = status_ignoring_retraction()
    else:
        status = status_ignoring_retraction()
        prior_status = None

    root, winner, duplicates = _answer_chains(by_type.get("answered", []))

    fired = by_type.get("fired", [])
    row = {
        "sample": sample_id,
        "stream": stream_id,
        "scheduled_utc": scheduled_utc,
        "status": status,
        "prior_status": prior_status,
        "observed": bool(fired),
        "test": any(ev.get("test", False) for ev in fired),
        "snoozes": len(by_type.get("snoozed", [])),
        "fired_on": sorted({ev["dev"] for ev in fired}),
        "answered_on": winner["dev"] if winner else None,
        "answered_at": root["t"] if root else None,
        "latency_s": (parse_utc(root["t"]) - scheduled_epoch) if root else None,
        "late": False,
        "partial": bool(winner.get("partial", False)) if winner else False,
        "survey_id": winner["survey"]["id"] if winner else None,
        "survey_version": winner["survey"]["version"] if winner else None,
        "answers": winner.get("answers", {}) if winner else {},
        "loc": winner.get("loc") if winner else None,
        "suppressed_reason": (
            min(by_type["suppressed"], key=lambda ev: (ev["t"], ev["dev"])).get("reason")
            if "suppressed" in by_type
            else None
        ),
        "duplicate_answers": len(duplicates),
        "config_version": _first_config_v(deduped),
        "warnings": warnings,
    }
    if winner and expiry_minutes is not None:
        row["late"] = row["latency_s"] > expiry_minutes * 60
    return row


def _first_config_v(deduped: list[dict]) -> int | None:
    carrying = [ev for ev in deduped if "config_v" in ev]
    if not carrying:
        return None
    return min(carrying, key=lambda ev: (ev["t"], ev["dev"]))["config_v"]
