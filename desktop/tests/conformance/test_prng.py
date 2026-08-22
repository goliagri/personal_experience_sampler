"""Conformance: spec/prng_vectors.json (§13), plus independent anchors
against the published SplitMix64 reference outputs so the vectors and the
implementation cannot drift together unnoticed.
"""

import json

from pes.core import prng


def load(spec_dir):
    return json.loads((spec_dir / "prng_vectors.json").read_text())


def test_splitmix64_vectors(spec_dir):
    for case in load(spec_dir)["splitmix64"]:
        state = int(case["seed"], 16)
        for expected in case["outputs"]:
            state, out = prng.splitmix64_next(state)
            assert out == int(expected, 16)


def test_splitmix64_published_reference():
    # First outputs for seed 0 from the reference C implementation.
    state, outs = 0, []
    for _ in range(3):
        state, o = prng.splitmix64_next(state)
        outs.append(o)
    assert outs == [0xE220A8397B1DCDAF, 0x6E789E6AA1B965F4, 0x06C45D188009454F]


def test_xoshiro_vectors(spec_dir):
    for case in load(spec_dir)["xoshiro256starstar"]:
        rng = prng.Xoshiro256StarStar(int(case["seed_u64"], 16))
        for expected in case["outputs"]:
            assert rng.next_u64() == int(expected, 16)


def test_seed_derivation(spec_dir):
    for case in load(spec_dir)["seed_derivation"]:
        assert prng.seed_u64(case["stream_seed"], case["scope"]) == int(case["seed_u64"], 16)


def test_uniform_double(spec_dir):
    import struct

    for case in load(spec_dir)["uniform_double"]:
        got = prng.uniform_double(int(case["u64"], 16))
        got_bits = struct.unpack(">Q", struct.pack(">d", got))[0]
        assert got_bits == int(case["double_bits"], 16)
        assert 0.0 <= got < 1.0
