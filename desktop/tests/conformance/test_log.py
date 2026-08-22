"""Conformance: spec/log_vectors.json (§13). The [H] bit-identity layer:
every entry must match as an exact IEEE-754 bit pattern.
"""

import json
import math
import struct

from pes.core.fdlibm_log import fdlibm_log


def _from_bits(hex_str: str) -> float:
    return struct.unpack(">d", struct.pack(">Q", int(hex_str, 16)))[0]


def _bits(x: float) -> int:
    return struct.unpack(">Q", struct.pack(">d", x))[0]


def test_ln_bit_patterns(spec_dir):
    doc = json.loads((spec_dir / "log_vectors.json").read_text())
    for case in doc["ln"]:
        x = _from_bits(case["x_bits"])
        assert _bits(fdlibm_log(x)) == int(case["ln_bits"], 16), case


def test_end_to_end_draws(spec_dir):
    doc = json.loads((spec_dir / "log_vectors.json").read_text())
    for case in doc["draws"]:
        u = _from_bits(case["u_bits"])
        gap = math.floor(-case["mean_seconds"] * fdlibm_log(1.0 - u))
        assert gap == case["gap_seconds"], case


def test_specials():
    assert fdlibm_log(1.0) == 0.0
    assert fdlibm_log(0.0) == float("-inf")
    assert fdlibm_log(float("inf")) == float("inf")
    assert math.isnan(fdlibm_log(-1.0))
    assert math.isnan(fdlibm_log(float("nan")))
