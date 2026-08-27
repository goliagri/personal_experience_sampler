"""Generate the language-neutral conformance fixtures in spec/ (spec §13).

Run from the repo root:  python3 spec/tools/generate_vectors.py

Inputs are hand-authored here; expected outputs are computed by the Python
core. Guard assertions verify that each hard case actually exercises its edge
(min-gap shift present, cross-midnight pair unshifted, DST collision occurs,
re-seeding independence holds), so a regression in the core fails generation
rather than silently producing self-consistent-but-wrong fixtures. PRNG
values are additionally pinned against published reference outputs in the
test suite, and log vectors against high-precision arithmetic.

Regenerating after an intentional core change is allowed only together with
review of the diff (§13: fold/protocol changes ship with updated vectors).
"""

from __future__ import annotations

import json
import math
import struct
import sys
from datetime import date, timedelta
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "desktop"))

import itertools

from pes.core import prng
from pes.core.config_validation import validate_config
from pes.core.export import columns_json, export_csv
from pes.core.fdlibm_log import fdlibm_log
from pes.core.fold import fold_sample
from pes.core.quick import is_full, presented_fields
from pes.core.scheduler import resolve_day
from pes.core.timeutil import fmt_utc, parse_utc

SPEC = REPO / "spec"

SEED_A = "8f3a9c1e5b2d4a6c8e0f1a2b3c4d5e6f"
SEED_B = "0123456789abcdef0123456789abcdef"


def u64_hex(v: int) -> str:
    return f"0x{v:016x}"


def dbl_bits(x: float) -> str:
    return f"0x{struct.unpack('>Q', struct.pack('>d', x))[0]:016x}"


def write(path: Path, doc) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(doc, indent=2, sort_keys=False) + "\n")
    print(f"wrote {path.relative_to(REPO)}")


# ---------------------------------------------------------------- PRNG

def gen_prng() -> None:
    splitmix = []
    for seed in (0, 1, 0x9E3779B97F4A7C15):
        s, outs = seed, []
        for _ in range(64):
            s, o = prng.splitmix64_next(s)
            outs.append(u64_hex(o))
        splitmix.append({"seed": u64_hex(seed), "outputs": outs})

    xoshiro = []
    for seed in (0, 1, 0x9E3779B97F4A7C15):
        rng = prng.Xoshiro256StarStar(seed)
        xoshiro.append(
            {"seed_u64": u64_hex(seed), "outputs": [u64_hex(rng.next_u64()) for _ in range(64)]}
        )

    derivation = [
        {
            "stream_seed": stream_seed,
            "scope": scope,
            "seed_u64": u64_hex(prng.seed_u64(stream_seed, scope)),
        }
        for stream_seed, scope in [
            (SEED_A, "day:2026-08-21"),
            (SEED_A, "interval:2026-08-21:5"),
            (SEED_B, "day:2026-01-01"),
            (SEED_B, "interval:2026-11-01:0"),
        ]
    ]

    uniform = [
        {"u64": u64_hex(u), "double_bits": dbl_bits(prng.uniform_double(u))}
        for u in (0, (1 << 64) - 1, 1 << 11, (1 << 63) | 0x7FF, 0xDEADBEEFCAFEBABE)
    ]

    write(
        SPEC / "prng_vectors.json",
        {
            "splitmix64": splitmix,
            "xoshiro256starstar": xoshiro,
            "seed_derivation": derivation,
            "uniform_double": uniform,
        },
    )


# ---------------------------------------------------------------- fdlibm log

def gen_log() -> None:
    import math as pymath
    import random

    inputs: list[float] = [
        5e-324,  # min subnormal
        2.2250738585072014e-308,  # min normal
        float.fromhex("0x1.fffffffffffffp-1"),  # just below 1
        float.fromhex("0x1.0000000000001p+0"),  # just above 1
        0.5,
        2.0,
        math.e,
        10.0,
        1e300,
    ]
    # Typical 1-u values from the actual PRNG.
    rng = prng.Xoshiro256StarStar(prng.seed_u64(SEED_A, "day:2026-08-21"))
    inputs += [1.0 - rng.uniform() for _ in range(16)]
    # Values where fdlibm and this platform's libm differ (the [H] entries).
    r = random.Random(7)
    differing = []
    while len(differing) < 24:
        x = r.random()
        if x and fdlibm_log(x) != pymath.log(x):
            differing.append(x)
    inputs += differing

    ln = [{"x_bits": dbl_bits(x), "ln_bits": dbl_bits(fdlibm_log(x))} for x in inputs]

    draws = []
    for mean_s, u_bits in [
        (5400.0, dbl_bits(0.0)),
        (5400.0, dbl_bits(0.5)),
        (5400.0, dbl_bits(1.0 - 2**-53)),
        (60.0, dbl_bits(0.9999)),
        # floor boundary: pick u so that -mean*ln(1-u) is within ~1e-9 of an int
        (1000.0, dbl_bits(1.0 - math.exp(-1.0)))
    ]:
        u = struct.unpack(">d", struct.pack(">Q", int(u_bits, 16)))[0]
        gap = math.floor(-mean_s * fdlibm_log(1.0 - u))
        draws.append({"mean_seconds": mean_s, "u_bits": u_bits, "gap_seconds": gap})

    write(SPEC / "log_vectors.json", {"ln": ln, "draws": draws})


# ---------------------------------------------------------------- schedules

def base_config(streams: list[dict], version=1, effective="2026-01-01T00:00:00Z", tz="America/Los_Angeles") -> dict:
    return {
        "version": version,
        "effective_from": effective,
        "timezone": tz,
        "defaults": {"snooze_minutes": 10, "max_snoozes": 3, "expiry_minutes": 60, "backlog_hours": 12},
        "streams": streams,
    }


def stream(id: str, protocol: dict, seed=SEED_A, quiet_zones=None, enabled=True, **kw) -> dict:
    return {
        "id": id,
        "name": id,
        "enabled": enabled,
        "seed": seed,
        "protocol": protocol,
        "quiet_zones": quiet_zones or [],
        "survey": {"id": "s", "version": 1},
        **kw,
    }


def sched_case(name: str, history: list[dict], stream_id: str, day: str, note: str = "") -> dict:
    resolved = resolve_day(history, stream_id, date.fromisoformat(day))
    return {
        "name": name,
        "note": note,
        "config_history": history,
        "stream": stream_id,
        "local_day": day,
        "expected": [
            {
                "scheduled_utc": fmt_utc(r.scheduled_utc),
                "suppressed": r.suppressed_reason,
                "config_v": r.config_v,
                "index": r.index,
            }
            for r in resolved
        ],
    }


def gen_schedules() -> None:
    cases = []

    # --- Poisson ---
    poisson = {"type": "poisson", "mean_gap_minutes": 90}
    poisson_mg = {"type": "poisson", "mean_gap_minutes": 90, "min_gap_minutes": 15}

    cfg_utc = base_config([stream("p", poisson)], tz="UTC")
    cases.append(sched_case("poisson_plain_utc_day", [cfg_utc], "p", "2026-08-20",
                            "UTC timezone: local day == UTC day, no min gap"))

    cfg_mg = base_config([stream("p", poisson_mg)])
    c = sched_case("poisson_min_gap_within_day", [cfg_mg], "p", "2026-08-20",
                   "[H] min gap shifts candidates to prev+min_gap within a UTC day")
    times = [parse_utc(e["scheduled_utc"]) for e in c["expected"]]
    assert any(b - a == 15 * 60 for a, b in itertools.pairwise(times)), "min-gap shift not exercised"
    cases.append(c)

    # [H] cross-UTC-midnight pair closer than min gap: scan for a day where the
    # last ping of UTC day D and first of D+1 are < min_gap apart.
    found = None
    d = date(2026, 1, 1)
    while d < date(2027, 1, 1):
        r = resolve_day([cfg_mg], "p", d)
        times = [x.scheduled_utc for x in r]
        for a, b in itertools.pairwise(times):
            if b - a < 15 * 60 and (a // 86400) != (b // 86400):
                found = d
                break
        if found:
            break
        d += timedelta(days=1)
    assert found, "no cross-midnight close pair found in 2026"
    cases.append(sched_case("poisson_min_gap_not_across_utc_midnight", [cfg_mg], "p",
                            found.isoformat(),
                            "[H] pair closer than min_gap across 00:00Z is NOT shifted"))

    cfg_la_plain = base_config([stream("p", poisson)])
    c = sched_case("poisson_local_day_two_utc_days", [cfg_la_plain], "p", "2026-08-20",
                   "[H] LA local day spans two UTC days; both generated, filtered to bounds")
    days = {parse_utc(e["scheduled_utc"]) // 86400 for e in c["expected"]}
    assert len(days) == 2, "seam case must draw from both UTC days"
    assert c["expected"] != cases[0]["expected"], "must differ from the UTC-timezone case"
    cases.append(c)

    # [H] config change mid-day; day D+1 unaffected (re-seeding independence).
    cfg_v1 = base_config([stream("p", poisson_mg)], version=1)
    cfg_v2 = base_config([stream("p", {"type": "poisson", "mean_gap_minutes": 45, "min_gap_minutes": 15})],
                         version=2, effective="2026-08-20T22:00:00Z")
    cases.append(sched_case("poisson_config_change_mid_day", [cfg_v1, cfg_v2], "p", "2026-08-20",
                            "[H] piecewise: v1 before 22:00Z, v2 after"))
    day_after_two = resolve_day([cfg_v1, cfg_v2], "p", date(2026, 8, 21))
    day_after_only2 = resolve_day([base_config([stream("p", {"type": "poisson", "mean_gap_minutes": 45, "min_gap_minutes": 15})])], "p", date(2026, 8, 21))
    assert [x.scheduled_utc for x in day_after_two] == [x.scheduled_utc for x in day_after_only2], \
        "re-seeding independence violated"
    cases.append(sched_case("poisson_day_after_config_change", [cfg_v1, cfg_v2], "p", "2026-08-21",
                            "[H] day D+1 fully under v2, identical to v2-only generation"))

    # [H] effective_from need not rise with version: v3 (written later) takes
    # effect before v2's still-pending change, so v2 never becomes effective.
    # The effective version at any instant is the highest one whose
    # effective_from has passed; nothing may be generated under two versions
    # (that would mint phantom +1 s pings via the collision rule).
    cfg_nm2 = base_config(
        [stream("p", {"type": "poisson", "mean_gap_minutes": 45, "min_gap_minutes": 15})],
        version=2, effective="2026-08-21T00:00:00Z")
    cfg_nm3 = base_config([stream("p", poisson_mg)], version=3, effective="2026-08-20T20:00:00Z")
    c = sched_case("non_monotonic_effective_from", [cfg_v1, cfg_nm2, cfg_nm3], "p", "2026-08-20",
                   "[H] v3 effective before v2: highest-effective-version rule; v2 never "
                   "in effect, and identical v1/v3 protocols produce no duplicate instants")
    only_v1 = [fmt_utc(r.scheduled_utc) for r in resolve_day([cfg_v1], "p", date(2026, 8, 20))]
    assert [e["scheduled_utc"] for e in c["expected"]] == only_v1, "phantom +1 s pings generated"
    assert {e["config_v"] for e in c["expected"]} == {1, 3}
    cases.append(c)

    cfg_seed_b = base_config([stream("p", poisson_mg, seed=SEED_B)])
    c2 = sched_case("poisson_seed_edit", [cfg_seed_b], "p", "2026-08-20",
                    "same day, different stream seed -> different times")
    assert c2["expected"] != cases[1]["expected"]
    cases.append(c2)

    # --- Stratified ---
    strat = {"type": "stratified", "interval_minutes": 600, "pings_per_interval": 5}
    cfg_s = base_config([stream("s1", strat)])
    cases.append(sched_case("stratified_normal_day", [cfg_s], "s1", "2026-08-20",
                            "24 h day: 2 full 10 h intervals + 4 h partial -> floor(5*0.4)=2 pings"))
    c = sched_case("stratified_23h_day", [cfg_s], "s1", "2026-03-08",
                   "[H] spring-forward 23 h day: 2 full intervals + 3 h partial -> floor(5*0.3)=1")
    assert len(c["expected"]) == 5 + 5 + 1
    cases.append(c)
    c = sched_case("stratified_25h_day", [cfg_s], "s1", "2026-11-01",
                   "[H] fall-back 25 h day: 2 full intervals + 5 h partial -> floor(5*0.5)=2")
    assert len(c["expected"]) == 5 + 5 + 2
    cases.append(c)

    # --- fixed_interval ---
    fi = {"type": "fixed_interval", "every_minutes": 180, "anchor_local": "09:00"}
    cfg_fi = base_config([stream("fi", fi)])
    cases.append(sched_case("fixed_interval_basic", [cfg_fi], "fi", "2026-08-20",
                            "anchor 09:00 local, every 3 h until end of local day"))
    cfg_fi_gap = base_config([stream("fi", {"type": "fixed_interval", "every_minutes": 180, "anchor_local": "02:30"})])
    c = sched_case("fixed_interval_spring_forward_anchor", [cfg_fi_gap], "fi", "2026-03-08",
                   "[H] anchor 02:30 nonexistent -> pre-transition instant, suppressed(dst); later emissions clean")
    assert c["expected"][0]["suppressed"] == "dst" and c["expected"][1]["suppressed"] is None
    cases.append(c)
    cfg_fi_fb = base_config([stream("fi", {"type": "fixed_interval", "every_minutes": 60, "anchor_local": "00:30"})])
    c = sched_case("fixed_interval_through_fall_back", [cfg_fi_fb], "fi", "2026-11-01",
                   "[H] hourly through the fall-back: UTC spacing stays 60 min, 25 emissions")
    times = [parse_utc(e["scheduled_utc"]) for e in c["expected"]]
    assert len(times) == 25 and all(b - a == 3600 for a, b in itertools.pairwise(times))
    cases.append(c)

    # --- fixed_times ---
    ft = {"type": "fixed_times", "times_local": ["08:00", "12:30", "20:00"], "days": ["mon", "wed", "fri"]}
    cfg_ft = base_config([stream("ft", ft)])
    cases.append(sched_case("fixed_times_basic_matching_day", [cfg_ft], "ft", "2026-08-19",
                            "2026-08-19 is a Wednesday: all three times"))
    cases.append(sched_case("fixed_times_nonmatching_day", [cfg_ft], "ft", "2026-08-20",
                            "Thursday: empty"))
    cfg_ft_dst = base_config([stream("ft", {"type": "fixed_times", "times_local": ["02:30", "12:00"]})])
    c = sched_case("fixed_times_spring_forward", [cfg_ft_dst], "ft", "2026-03-08",
                   "[H] 02:30 nonexistent -> 10:30Z suppressed(dst)")
    assert c["expected"][0] == {"scheduled_utc": "2026-03-08T10:30:00Z", "suppressed": "dst", "config_v": 1, "index": 0}
    cases.append(c)
    cfg_ft_fb = base_config([stream("ft", {"type": "fixed_times", "times_local": ["01:30", "12:00"]})])
    c = sched_case("fixed_times_fall_back_first_occurrence", [cfg_ft_fb], "ft", "2026-11-01",
                   "[H] 01:30 duplicated -> first occurrence (PDT, 08:30Z)")
    assert c["expected"][0]["scheduled_utc"] == "2026-11-01T08:30:00Z"
    cases.append(c)

    # --- scheduler core ---
    cfg_coll = base_config([stream("c", {"type": "fixed_times", "times_local": ["12:00", "12:00", "12:00"]})])
    c = sched_case("collision_same_second", [cfg_coll], "c", "2026-08-20",
                   "[H] identical times shift +1 s in times_local order")
    assert [e["scheduled_utc"][-3:] for e in c["expected"]] == ["00Z", "01Z", "02Z"]
    cases.append(c)

    cfg_coll_dst = base_config([stream("c", {"type": "fixed_times", "times_local": ["02:30", "03:30"]})])
    c = sched_case("collision_dst_resolved_candidate", [cfg_coll_dst], "c", "2026-03-08",
                   "[H] 02:30 (nonexistent, ->10:30Z) collides with real 03:30 PDT (10:30Z); "
                   "later-in-order 03:30 shifts to 10:30:01Z")
    assert c["expected"][0] == {"scheduled_utc": "2026-03-08T10:30:00Z", "suppressed": "dst", "config_v": 1, "index": 0}
    assert c["expected"][1] == {"scheduled_utc": "2026-03-08T10:30:01Z", "suppressed": None, "config_v": 1, "index": 1}
    cases.append(c)

    cfg_day_end = base_config(
        [stream("c", {"type": "fixed_times", "times_local": ["23:59"] * 61})], tz="UTC")
    c = sched_case("collision_shift_past_day_end", [cfg_day_end], "c", "2026-08-20",
                   "[H] 61 collisions at 23:59: the last shifts to 00:00:00Z of the next day "
                   "but remains assigned to the generating day")
    assert c["expected"][-1]["scheduled_utc"] == "2026-08-21T00:00:00Z"
    cases.append(c)

    qz_plain = [{"days": ["mon", "tue", "wed", "thu", "fri", "sat", "sun"], "from": "13:00", "to": "15:00"}]
    cfg_qz = base_config([stream("q", {"type": "fixed_times", "times_local": ["12:00", "13:00", "14:59", "15:00"]}, quiet_zones=qz_plain)])
    c = sched_case("quiet_zone_plain_half_open", [cfg_qz], "q", "2026-08-20",
                   "window [13:00,15:00): 13:00 and 14:59 suppressed, 12:00 and 15:00 not; "
                   "applies to fixed_times (suppressed, not skipped)")
    assert [e["suppressed"] for e in c["expected"]] == [None, "quiet_zone", "quiet_zone", None]
    cases.append(c)

    qz_wrap = [{"days": ["thu"], "from": "23:00", "to": "07:30"}]
    cfg_qzw = base_config([stream("q", {"type": "fixed_times", "times_local": ["07:00", "22:59", "23:30"]}, quiet_zones=qz_wrap)])
    c = sched_case("quiet_zone_midnight_wrap", [cfg_qzw], "q", "2026-08-21",
                   "window starts Thu 23:00, wraps to Fri 07:30: Fri 07:00 suppressed "
                   "(tail of Thu window), Fri 22:59 not, Fri 23:30 not (Fri not listed)")
    assert [e["suppressed"] for e in c["expected"]] == ["quiet_zone", None, None]
    cases.append(c)

    qz_all = [{"days": ["mon", "tue", "wed", "thu", "fri", "sat", "sun"], "from": "23:00", "to": "07:30"}]
    cfg_qzd = base_config([stream("q", {"type": "fixed_interval", "every_minutes": 60, "anchor_local": "00:15"}, quiet_zones=qz_all)])
    c = sched_case("quiet_zone_spanning_spring_forward", [cfg_qzd], "q", "2026-03-08",
                   "quiet zone 23:00-07:30 across the transition is an hour shorter in "
                   "elapsed time; evaluated on wall clock")
    cases.append(c)

    cfg_off = base_config([stream("q", ft, enabled=False)])
    c = sched_case("disabled_stream_empty", [cfg_off], "q", "2026-08-19",
                   "disabled stream generates nothing (off != suppressed)")
    assert c["expected"] == []
    cases.append(c)

    write(SPEC / "schedule_vectors.json", {"cases": cases})


# ---------------------------------------------------------------- folds

SAMPLE = "thoughts|2026-08-20T15:00:00Z"


def ev(dev: str, ev_type: str, t: str, **extra) -> dict:
    return {"ev": ev_type, "t": t, "dev": dev, "sample": SAMPLE, "stream": "thoughts", **extra}


def answered(dev: str, t: str, answers=None, version=1, **extra) -> dict:
    return ev(dev, "answered", t,
              survey={"id": "s", "version": version},
              answers=answers or {"tags": ["work"]}, **extra)


def fold_case(name: str, files: dict[str, list[dict]], expiry_minutes=60, note="") -> dict:
    flat = [(path, i, e) for path, evs in files.items() for i, e in enumerate(evs)]
    row = fold_sample(flat, expiry_minutes)
    warnings = row.pop("warnings")
    return {
        "name": name,
        "note": note,
        "expiry_minutes": expiry_minutes,
        "files": files,
        "expected": row,
        "expected_warning_count": len(warnings),
    }


def gen_folds() -> None:
    P, L = "phone-a3f2c1d0", "laptop-9c11aa00"
    FP, FL = f"events/{P}/2026-08.jsonl", f"events/{L}/2026-08.jsonl"
    cases = []

    fired = ev(P, "fired", "2026-08-20T15:00:02Z", config_v=1, scheduled="2026-08-20T15:00:00Z", test=False)

    # Status precedence, adjacent pairs.
    cases.append(fold_case("precedence_retracted_over_answered",
        {FP: [fired, answered(P, "2026-08-20T15:01:00Z"), ev(P, "retracted", "2026-08-20T16:00:00Z")]},
        note="retracted wins; prior_status keeps answered"))
    cases.append(fold_case("precedence_answered_over_skipped",
        {FP: [fired, ev(P, "skipped", "2026-08-20T15:00:30Z"), answered(P, "2026-08-20T15:01:00Z")]}))
    cases.append(fold_case("precedence_skipped_over_expired",
        {FP: [fired, ev(P, "expired", "2026-08-20T16:00:00Z", config_v=1), ev(P, "skipped", "2026-08-20T15:30:00Z")]}))
    cases.append(fold_case("precedence_expired_over_suppressed",
        {FP: [ev(P, "suppressed", "2026-08-20T15:00:00Z", reason="quiet_zone"),
              ev(P, "expired", "2026-08-20T16:00:00Z", config_v=1)]}))
    cases.append(fold_case("precedence_suppressed_over_unobserved",
        {FL: [ev(L, "unobserved", "2026-08-21T09:00:00Z", config_v=1)],
         FP: [ev(P, "suppressed", "2026-08-20T15:00:00Z", reason="quiet_mode")]},
        note="[H] suppressed is the more informed claim and outranks unobserved"))
    cases.append(fold_case("precedence_unobserved_over_pending",
        {FP: [fired], FL: [ev(L, "unobserved", "2026-08-21T09:00:00Z", config_v=1)]},
        note="unobserved outranks bare fired (pending is not a logged status)"))
    cases.append(fold_case("pending_only_fired",
        {FP: [fired, ev(P, "snoozed", "2026-08-20T15:05:00Z", n=1, until="2026-08-20T15:15:00Z")]}))

    # [H] dedup: identical lines in events/** and restored/**.
    dup_files = {
        FP: [fired,
             ev(P, "snoozed", "2026-08-20T15:05:00Z", n=1, until="2026-08-20T15:15:00Z"),
             answered(P, "2026-08-20T15:20:00Z")],
        f"restored/{L}/{P}/2026-08.jsonl": [
             fired,
             ev(P, "snoozed", "2026-08-20T15:05:00Z", n=1, until="2026-08-20T15:15:00Z"),
             answered(P, "2026-08-20T15:20:00Z")],
    }
    c = fold_case("dedup_restore_duplicates", dup_files,
                  note="[H] exact duplicates from a restore collapse: snoozes=1, one answer chain")
    assert c["expected"]["snoozes"] == 1 and c["expected"]["duplicate_answers"] == 0
    cases.append(c)

    conflict_files = {
        FP: [answered(P, "2026-08-20T15:20:00Z", answers={"tags": ["work"]})],
        f"restored/{L}/{P}/2026-08.jsonl": [answered(P, "2026-08-20T15:20:00Z", answers={"tags": ["play"]})],
    }
    c = fold_case("dedup_same_key_different_payload", conflict_files,
                  note="[H] same identity key, different payload: first by file-path order "
                       "(events/** sorts before restored/**) kept, warning recorded")
    assert c["expected"]["answers"] == {"tags": ["work"]} and c["expected_warning_count"] == 1
    cases.append(c)

    # [H] snooze on A, answer on B.
    c = fold_case("snooze_phone_answer_laptop",
        {FP: [fired, ev(P, "snoozed", "2026-08-20T15:05:00Z", n=1, until="2026-08-20T15:15:00Z")],
         FL: [ev(L, "fired", "2026-08-20T15:00:03Z", config_v=1, scheduled="2026-08-20T15:00:00Z"),
              answered(L, "2026-08-20T15:12:00Z")]},
        note="[H] one sample: snoozes=1, answered_on=laptop, latency from original scheduled time")
    assert c["expected"]["snoozes"] == 1 and c["expected"]["answered_on"] == L
    assert c["expected"]["latency_s"] == 720 and c["expected"]["fired_on"] == [L, P]
    cases.append(c)

    # [H] duplicate answers on two devices.
    c = fold_case("duplicate_answers_two_devices",
        {FP: [fired, answered(P, "2026-08-20T15:03:00Z", answers={"tags": ["first"]})],
         FL: [answered(L, "2026-08-20T15:04:00Z", answers={"tags": ["second"]})]},
        note="[H] earliest-root chain wins; the other chain is retained as duplicate")
    assert c["expected"]["answers"] == {"tags": ["first"]} and c["expected"]["duplicate_answers"] == 1
    cases.append(c)

    # [H] supersedes chain including cross-device edit.
    c = fold_case("supersedes_chain_cross_device",
        {FP: [fired, answered(P, "2026-08-20T15:03:00Z", answers={"tags": ["v1"]}),
              answered(P, "2026-08-20T15:10:00Z", answers={"tags": ["v2"]}, supersedes="2026-08-20T15:03:00Z")],
         FL: [answered(L, "2026-08-20T18:00:00Z", answers={"tags": ["v3"]}, supersedes="2026-08-20T15:10:00Z")]},
        note="[H] latest in chain effective, even when the edit came from another device")
    assert c["expected"]["answers"] == {"tags": ["v3"]} and c["expected"]["duplicate_answers"] == 0
    assert c["expected"]["latency_s"] == 180  # latency from winning chain's effective answer? no: winner's t
    cases.append(c)

    # Retraction of a pending sample.
    c = fold_case("retract_pending",
        {FP: [fired, ev(P, "retracted", "2026-08-20T16:00:00Z", note="mistake")]},
        note="retraction of an unanswered sample: prior_status=pending")
    assert c["expected"]["status"] == "retracted" and c["expected"]["prior_status"] == "pending"
    cases.append(c)

    # [H] retroactive expiry.
    c = fold_case("retroactive_expiry",
        {FP: [fired],
         FL: [ev(L, "unobserved", "2026-08-21T09:00:00Z", config_v=1),
              ev(L, "expired", "2026-08-21T09:00:00Z", config_v=1)]},
        note="[H] fired + unobserved + later expired folds to expired (§8.4 step 3)")
    assert c["expected"]["status"] == "expired" and c["expected"]["observed"] is True
    cases.append(c)

    # late boundary: expiry 30 min; exactly at boundary is NOT late.
    c = fold_case("late_boundary_exact",
        {FP: [fired, answered(P, "2026-08-20T15:30:00Z")]}, expiry_minutes=30,
        note="latency == expiry -> late=false (strictly greater required)")
    assert c["expected"]["late"] is False
    cases.append(c)
    c = fold_case("late_one_second_past",
        {FP: [fired, answered(P, "2026-08-20T15:30:01Z")]}, expiry_minutes=30)
    assert c["expected"]["late"] is True
    cases.append(c)

    # partial inline answer; test flag.
    test_fired = ev(P, "fired", "2026-08-20T15:00:02Z", config_v=1, scheduled="2026-08-20T15:00:00Z", test=True)
    c = fold_case("partial_inline_answer_and_test",
        {FP: [test_fired, answered(P, "2026-08-20T15:01:00Z", answers={"tags": ["quick"]}, partial=True)]},
        note="partial = winning answer's flag; test = any fired.test")
    assert c["expected"]["partial"] is True and c["expected"]["test"] is True
    cases.append(c)

    # stale config: suppressed(stale_config) + answered -> answered wins.
    c = fold_case("stale_config_answer_survives",
        {FP: [fired, answered(P, "2026-08-20T15:02:00Z"),
              ev(P, "suppressed", "2026-08-20T18:00:00Z", reason="stale_config")]},
        note="answered outranks suppressed(stale_config); no answer is lost")
    assert c["expected"]["status"] == "answered"
    cases.append(c)

    # Corrupt supersedes cycle: no chain root exists. The fold must stay
    # total — every event becomes a root; the earliest-t chain still wins and
    # the other chain counts as a duplicate.
    c = fold_case("supersedes_cycle_total",
        {FP: [fired, answered(P, "2026-08-20T15:05:00Z", supersedes="2026-08-20T15:06:00Z")],
         FL: [answered(L, "2026-08-20T15:06:00Z", answers={"tags": ["late"]},
                       supersedes="2026-08-20T15:05:00Z")]},
        note="[H] a supersedes cycle has no root; fold treats every event as one instead of crashing")
    assert c["expected"]["status"] == "answered"
    assert c["expected"]["answered_at"] == "2026-08-20T15:05:00Z"
    assert c["expected"]["duplicate_answers"] == 1
    cases.append(c)

    write(SPEC / "fold_vectors.json", {"cases": cases})


# ---------------------------------------------------------------- quick

def gen_quick() -> None:
    survey_no_quick = {
        "id": "s", "version": 1, "title": "t",
        "fields": [
            {"id": "mood", "type": "number", "label": "Mood", "min": 1, "max": 7, "integer": True},
            {"id": "tags", "type": "tags", "label": "Tags"},
            {"id": "note", "type": "text", "label": "Note"},
        ],
    }
    survey_quick = {
        "id": "s", "version": 2, "title": "t",
        "fields": [
            {"id": "mood", "type": "number", "label": "Mood", "quick": True},
            {"id": "tags", "type": "tags", "label": "Tags", "quick": True},
            {"id": "note", "type": "text", "label": "Note"},
        ],
    }
    index_cases = [
        {"name": "n3_over_7", "n": 3, "count": 7,
         "expected_full": [is_full(i, 3) for i in range(7)],
         "note": "[H] index counts suppressed candidates too; i=0 always full"},
        {"name": "n1_all_full", "n": 1, "count": 4, "expected_full": [True] * 4},
        {"name": "fewer_than_n", "n": 5, "count": 3, "expected_full": [is_full(i, 5) for i in range(3)],
         "note": "a day with fewer than n pings still has exactly one full ping (i=0)"},
        {"name": "mid_day_config_change", "n": 2, "count": 6,
         "expected_full": [is_full(i, 2) for i in range(6)],
         "note": "[H] the index runs over the concatenated piecewise candidate list; "
                 "the list itself comes from schedule_vectors (time-sorted resolved list)"},
    ]
    field_cases = [
        {"name": "no_quick_field_first_tags", "survey": survey_no_quick, "full": False,
         "expected_field_ids": [f["id"] for f in presented_fields(survey_no_quick, False)]},
        {"name": "quick_fields", "survey": survey_quick, "full": False,
         "expected_field_ids": [f["id"] for f in presented_fields(survey_quick, False)]},
        {"name": "full_shows_all", "survey": survey_quick, "full": True,
         "expected_field_ids": [f["id"] for f in presented_fields(survey_quick, True)]},
    ]
    assert field_cases[0]["expected_field_ids"] == ["tags"]
    assert field_cases[1]["expected_field_ids"] == ["mood", "tags"]
    write(SPEC / "quick_vectors.json", {"index_cases": index_cases, "field_cases": field_cases})


# ---------------------------------------------------------------- exports

def gen_exports() -> None:
    P, L = "phone-a3f2c1d0", "laptop-9c11aa00"
    stream_id = "daily"
    survey_v1 = {
        "id": "daily", "version": 1, "title": "Daily",
        "fields": [
            {"id": "mood", "type": "number", "label": "Mood", "min": 1, "max": 7, "integer": True},
            {"id": "tags", "type": "tags", "label": "Tags"},
            {"id": "note", "type": "text", "label": "Note", "multiline": True},
            {"id": "context", "type": "choice", "label": "Where",
             "options": ["home", "work", "out"], "cardinality": "multi", "display": "checkbox"},
        ],
    }
    survey_v2 = {
        "id": "daily", "version": 2, "title": "Daily",
        "fields": [
            {"id": "mood", "type": "number", "label": "Mood", "min": 1, "max": 7, "integer": True},
            {"id": "tags", "type": "tags", "label": "Tags"},
            {"id": "energy", "type": "number", "label": "Energy"},
            {"id": "context", "type": "choice", "label": "Where",
             "options": ["home", "work", "out"], "cardinality": "multi", "display": "checkbox"},
        ],
    }

    def sev(dev, ev_type, sample, t, **extra):
        return {"ev": ev_type, "t": t, "dev": dev, "sample": sample, "stream": stream_id, **extra}

    s = [f"{stream_id}|2026-08-2{i}T1{i}:00:00Z" for i in range(6)]
    events = {
        f"events/{P}/2026-08.jsonl": [
            sev(P, "fired", s[0], "2026-08-20T10:00:01Z", config_v=1, scheduled="2026-08-20T10:00:00Z"),
            sev(P, "answered", s[0], "2026-08-20T10:02:00Z", survey={"id": "daily", "version": 1},
                answers={"mood": 5, "tags": ["work.writing", "deep"],
                         "note": "line one\nwith \"quotes\", commas", "context": ["home", "work"]},
                loc={"lat": 34.05, "lon": -118.25, "acc_m": 20, "age_s": 12}),
            sev(P, "fired", s[1], "2026-08-21T11:00:00Z", config_v=1, scheduled="2026-08-21T11:00:00Z", test=True),
            sev(P, "answered", s[1], "2026-08-21T11:01:00Z", survey={"id": "daily", "version": 1},
                answers={"tags": ["test"]}, partial=True),
            sev(P, "fired", s[2], "2026-08-22T12:00:02Z", config_v=1, scheduled="2026-08-22T12:00:00Z"),
            sev(P, "expired", s[2], "2026-08-22T13:00:00Z", config_v=1),
            sev(P, "suppressed", s[3], "2026-08-23T13:00:00Z", reason="quiet_zone"),
        ],
        f"events/{L}/2026-08.jsonl": [
            sev(L, "fired", s[4], "2026-08-24T14:00:01Z", config_v=2, scheduled="2026-08-24T14:00:00Z"),
            sev(L, "answered", s[4], "2026-08-24T16:30:00Z", survey={"id": "daily", "version": 2},
                answers={"mood": 3, "tags": ["rest"], "energy": 2.5, "context": ["home"]}),
            sev(L, "fired", s[5], "2026-08-25T15:00:00Z", config_v=2, scheduled="2026-08-25T15:00:00Z"),
            sev(L, "answered", s[5], "2026-08-25T15:05:00Z", survey={"id": "daily", "version": 2},
                answers={"mood": 4, "tags": ["x"], "context": ["out"]}),
            sev(L, "retracted", s[5], "2026-08-25T15:06:00Z"),
        ],
    }

    by_sample: dict[str, list] = {}
    for path, evs in events.items():
        for i, e in enumerate(evs):
            by_sample.setdefault(e["sample"], []).append((path, i, e))
    rows = [fold_sample(v, 60) for v in by_sample.values()]
    for row in rows:
        row.pop("warnings")
    surveys = {("daily", 1): survey_v1, ("daily", 2): survey_v2}
    csv_bytes, columns = export_csv(rows, surveys, "America/Los_Angeles")

    case_dir = SPEC / "export_vectors" / "daily_stream"
    case_dir.mkdir(parents=True, exist_ok=True)
    write(case_dir / "input.json", {
        "stream": stream_id,
        "timezone": "America/Los_Angeles",
        "expiry_minutes": 60,
        "surveys": [survey_v1, survey_v2],
        "events": events,
        "note": "covers: all four field types, ; joins, embedded newline/quote/comma, "
                "missing optional fields, partial answer, test row, retracted row, "
                "late answer, survey version union in f_ columns",
    })
    (case_dir / "expected.csv").write_bytes(csv_bytes)
    (case_dir / "expected.columns.json").write_bytes(columns_json(columns))
    print(f"wrote {case_dir.relative_to(REPO)}/expected.csv + columns")

    # Sanity: late answer present (s[4]: 2.5 h latency > 60 min), retracted row present.
    text = csv_bytes.decode()
    assert "true" in text and "retracted" in text


# ---------------------------------------------------------------- config validation

def gen_config_validation() -> None:
    now = "2026-08-21T12:00:00Z"
    surveys = [["thoughts", 2]]
    valid = {
        "version": 7,
        "base_version": 6,
        "written_by": "laptop-9c11aa00",
        "written_at": "2026-08-21T14:05:12Z",
        "effective_from": "2026-08-21T15:00:00Z",
        "timezone": "America/Los_Angeles",
        "defaults": {"snooze_minutes": 10, "max_snoozes": 3, "expiry_minutes": 60,
                     "backlog_hours": 12, "location": "off"},
        "streams": [{
            "id": "thoughts", "name": "In-the-moment thoughts v1", "enabled": True,
            "seed": SEED_A,
            "protocol": {"type": "poisson", "mean_gap_minutes": 90, "min_gap_minutes": 15},
            "quiet_zones": [{"days": ["mon", "tue", "wed", "thu", "fri", "sat", "sun"],
                             "from": "23:00", "to": "07:30"}],
            "survey": {"id": "thoughts", "version": 2},
            "full_survey_every_n": 1,
            "location": "coarse",
            "overrides": {"expiry_minutes": 30},
            "notification": {"sound": "default", "vibrate": True},
        }],
    }

    import copy

    def variant(mutate, name, note=""):
        doc = copy.deepcopy(valid)
        mutate(doc)
        return {"name": name, "note": note, "now": now, "surveys": surveys, "config": doc,
                "expected_errors": validate_config(doc, [tuple(s) for s in surveys], now)}

    cases = [
        {"name": "valid_reference", "note": "the §8.1 example", "now": now, "surveys": surveys,
         "config": valid, "expected_errors": []},
        variant(lambda d: d["streams"].append(copy.deepcopy(d["streams"][0])), "duplicate_stream_id"),
        variant(lambda d: d["streams"][0].update(seed="xyz"), "malformed_seed"),
        variant(lambda d: d["streams"][0]["quiet_zones"][0].update({"from": "08:00", "to": "08:00"}),
                "quiet_zone_from_equals_to"),
        variant(lambda d: d["streams"][0]["protocol"].update(mean_gap_minutes=0), "protocol_param_out_of_range"),
        variant(lambda d: d["streams"][0]["survey"].update(version=9), "dangling_survey_reference"),
        variant(lambda d: d.update(effective_from="2026-08-21T11:00:00Z"), "past_effective_from"),
        variant(lambda d: d["streams"][0]["quiet_zones"][0].update({"from": "25:00"}), "bad_hhmm"),
        variant(lambda d: d["streams"][0].update(id="Bad-Id"), "bad_stream_id_charset"),
        variant(lambda d: d["streams"][0].update(
                    protocol={"type": "fixed_interval", "every_minutes": 7.5, "anchor_local": "09:00"}),
                "fractional_every_minutes",
                "whole minutes required: both cores do integer-second arithmetic"),
        variant(lambda d: d["streams"][0].update(
                    protocol={"type": "stratified", "interval_minutes": 90.5, "pings_per_interval": 2}),
                "fractional_interval_minutes",
                "whole minutes required: both cores do integer-second arithmetic"),
        variant(lambda d: d.update(timezone="Not/AZone"), "bad_timezone"),
        # valid edges
        variant(lambda d: None, "valid_unchanged"),
        variant(lambda d: d["streams"][0]["quiet_zones"][0].update({"from": "23:00", "to": "07:30"}),
                "valid_midnight_wrapping_zone", "from > to wraps midnight and is valid"),
        variant(lambda d: d["streams"][0].update(overrides={"snooze_minutes": 5}), "valid_overrides_subset"),
        variant(lambda d: d.update(streams=[]), "valid_empty_streams"),
    ]
    for c in cases:
        if c["name"].startswith("valid"):
            assert c["expected_errors"] == [], (c["name"], c["expected_errors"])
        else:
            assert c["expected_errors"], c["name"]
    write(SPEC / "config_validation.json", {"cases": cases})


if __name__ == "__main__":
    gen_prng()
    gen_log()
    gen_schedules()
    gen_folds()
    gen_quick()
    gen_exports()
    gen_config_validation()
    print("all vectors generated")
