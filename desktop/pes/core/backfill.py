"""Backfill (spec §6.4): deterministic classification of past samples.

Pure function: given the resolved samples of a window, the known events, and
the imported quiet-mode history, produce the events this device should append.
Running it twice over the same inputs emits nothing the second time, and all
devices produce fold-equivalent events (duplicates fold away).
"""

from __future__ import annotations

from .timeutil import fmt_utc, parse_utc

TERMINAL_EVENTS = {"answered", "skipped", "expired", "retracted", "suppressed"}


def quiet_mode_active(instant: int, quiet_history: list[dict]) -> bool:
    """Whether quiet mode covered `instant`, per imported `quiet_changed`
    events (each: {"t": iso, "quiet_until": iso | "indefinite" | None}).
    The setting in effect at an instant is the latest change at or before it.
    """
    active = None
    for change in sorted(quiet_history, key=lambda c: c["t"]):
        if parse_utc(change["t"]) <= instant:
            active = change["quiet_until"]
    if active is None:
        return False
    if active == "indefinite":
        return True
    return instant < parse_utc(active)


def backfill(
    resolved_samples: list[dict],
    known_events: list[dict],
    quiet_history: list[dict],
    now: int,
    device_id: str,
    config_v: int,
    expiry_minutes_for: dict[str, int],
) -> list[dict]:
    """Classify samples in the window covered by `resolved_samples`.

    Each resolved sample: {"sample": id, "stream": id, "scheduled_utc": iso,
    "suppressed_reason": str | None}. `known_events` are all events already
    known to this device (own + imported). Returns new events to append, in
    scheduled order (spec §6.4 steps 1–4).
    """
    by_sample: dict[str, list[dict]] = {}
    for ev in known_events:
        if ev.get("sample"):
            by_sample.setdefault(ev["sample"], []).append(ev)

    out: list[dict] = []
    now_iso = fmt_utc(now)
    for sample in sorted(resolved_samples, key=lambda s: s["scheduled_utc"]):
        scheduled = parse_utc(sample["scheduled_utc"])
        if scheduled >= now:
            continue
        events = by_sample.get(sample["sample"], [])
        types = {ev["ev"] for ev in events}
        base = {
            "t": now_iso,
            "dev": device_id,
            "sample": sample["sample"],
            "stream": sample["stream"],
        }
        # 1. Terminal event from any known device -> nothing.
        if types & TERMINAL_EVENTS:
            continue
        # 2. Quiet zone / DST gap (from generation) or quiet mode (from
        #    imported history) -> suppressed with the reason.
        if sample.get("suppressed_reason"):
            out.append({"ev": "suppressed", "reason": sample["suppressed_reason"], **base})
            continue
        if quiet_mode_active(scheduled, quiet_history):
            out.append({"ev": "suppressed", "reason": "quiet_mode", **base})
            continue
        # 3. Fired somewhere and past expiry -> retroactive expired.
        expiry_s = expiry_minutes_for[sample["stream"]] * 60
        if "fired" in types:
            if scheduled + expiry_s < now:
                out.append({"ev": "expired", "config_v": config_v, **base})
            continue
        # 4. Never fired anywhere -> unobserved, but only once the active
        #    window has closed: a still-open sample can legitimately fire
        #    late (fired.t lags scheduled, §5.1), so the live scheduler owns
        #    it and the caller must hold its watermark back to revisit it.
        #    Guarded on "not already present" to keep backfill idempotent
        #    when another device already logged it.
        if scheduled + expiry_s <= now and "unobserved" not in types:
            out.append({"ev": "unobserved", "config_v": config_v, **base})
    return out
