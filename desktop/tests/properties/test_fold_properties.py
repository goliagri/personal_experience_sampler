"""Property tests for the fold (test plan §3).

[H] Fold idempotence & order-invariance: folding any event multiset equals
folding it shuffled, split across files differently, or with any subset of
lines duplicated. This is the theorem the sync design rests on.

Payload-conflicting duplicates (same identity key, different payload) are a
data error whose resolution is deliberately file-path-order dependent, so the
generator never produces them: each identity key maps to one payload.
"""

import random

from hypothesis import given, settings
from hypothesis import strategies as st
from pes.core.fold import fold_sample

SAMPLE = "s|2026-08-20T15:00:00Z"
DEVICES = ["phone-a3f2c1d0", "laptop-9c11aa00", "old-11112222"]
TIMES = [f"2026-08-20T15:{m:02d}:00Z" for m in (0, 1, 3, 5, 12, 30, 59)]


def _event(kind: str, dev: str, t: str, k: int) -> dict:
    """Build one event. Payloads are pure functions of the identity key
    (dev, t, ev, n, supersedes) so the generator can never produce two events
    with the same key but different payloads (that conflict is a data error
    whose resolution is file-order dependent by design)."""
    ti = TIMES.index(t)
    base = {"ev": kind, "t": t, "dev": dev, "sample": SAMPLE, "stream": "s"}
    if kind == "fired":
        base.update(config_v=1, scheduled="2026-08-20T15:00:00Z", test=False)
    elif kind == "snoozed":
        base.update(n=(k % 3) + 1, until="2026-08-20T16:00:00Z")
    elif kind == "answered":
        # Supersedes chains are exercised deterministically by time slot.
        supersedes = TIMES[ti - 1] if (ti % 3 == 0 and ti > 0) else None
        base.update(survey={"id": "s", "version": 1}, answers={"tags": [f"tag-{dev[:2]}-{ti}"]})
        if supersedes:
            base.update(supersedes=supersedes)
    elif kind == "expired" or kind == "unobserved":
        base.update(config_v=1)
    elif kind == "suppressed":
        base.update(reason=["quiet_zone", "quiet_mode", "dst"][ti % 3])
    return base


event_strategy = st.builds(
    _event,
    st.sampled_from(
        ["fired", "snoozed", "skipped", "answered", "expired", "unobserved", "suppressed", "retracted"]
    ),
    st.sampled_from(DEVICES),
    st.sampled_from(TIMES),
    st.integers(0, 11),
)


def _fold_normalized(triples):
    row = fold_sample(triples, 60)
    row.pop("warnings", None)
    return row


def _as_files(events, n_files, seed):
    rng = random.Random(seed)
    files = {f"events/d{i}/2026-08.jsonl": [] for i in range(n_files)}
    paths = sorted(files)
    for ev in events:
        files[rng.choice(paths)].append(ev)
    return [(p, i, e) for p in paths for i, e in enumerate(files[p])]


@settings(max_examples=150, deadline=None)
@given(st.lists(event_strategy, min_size=1, max_size=12), st.integers(0, 10**6))
def test_order_and_split_invariance(events, seed):
    baseline = _fold_normalized([("events/a.jsonl", i, e) for i, e in enumerate(events)])
    rng = random.Random(seed)
    shuffled = list(events)
    rng.shuffle(shuffled)
    assert _fold_normalized([("events/a.jsonl", i, e) for i, e in enumerate(shuffled)]) == baseline
    assert _fold_normalized(_as_files(shuffled, rng.randint(1, 3), seed)) == baseline


@settings(max_examples=150, deadline=None)
@given(st.lists(event_strategy, min_size=1, max_size=10), st.integers(0, 10**6))
def test_duplication_invariance(events, seed):
    baseline = _fold_normalized([("events/a.jsonl", i, e) for i, e in enumerate(events)])
    rng = random.Random(seed)
    duplicated = events + [dict(e) for e in events if rng.random() < 0.5]
    rng.shuffle(duplicated)
    assert _fold_normalized(_as_files(duplicated, 2, seed)) == baseline


@settings(max_examples=150, deadline=None)
@given(st.lists(event_strategy, min_size=1, max_size=12))
def test_answered_never_downgraded(events):
    row = fold_sample([("f", i, e) for i, e in enumerate(events)], 60)
    kinds = {e["ev"] for e in events}
    if "retracted" in kinds:
        assert row["status"] == "retracted"
    elif "answered" in kinds:
        assert row["status"] == "answered"
