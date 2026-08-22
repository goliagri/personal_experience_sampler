"""Deterministic PRNG for schedule generation (spec §6.2).

xoshiro256** seeded via SplitMix64 from the first 8 bytes (big-endian) of
SHA-256(stream_seed || ":" || scope). Mirrors ``core/Prng.kt``.
"""

from __future__ import annotations

import hashlib

_M64 = (1 << 64) - 1


def splitmix64_next(state: int) -> tuple[int, int]:
    """Advance a SplitMix64 state; returns (new_state, output)."""
    state = (state + 0x9E3779B97F4A7C15) & _M64
    z = state
    z = ((z ^ (z >> 30)) * 0xBF58476D1CE4E5B9) & _M64
    z = ((z ^ (z >> 27)) * 0x94D049BB133111EB) & _M64
    z = z ^ (z >> 31)
    return state, z


def _rotl(x: int, k: int) -> int:
    return ((x << k) | (x >> (64 - k))) & _M64


class Xoshiro256StarStar:
    """xoshiro256** with state initialized from SplitMix64(seed_u64)."""

    def __init__(self, seed_u64: int):
        s = seed_u64 & _M64
        state = []
        for _ in range(4):
            s, out = splitmix64_next(s)
            state.append(out)
        self._s = state

    def next_u64(self) -> int:
        s = self._s
        result = (_rotl((s[1] * 5) & _M64, 7) * 9) & _M64
        t = (s[1] << 17) & _M64
        s[2] ^= s[0]
        s[3] ^= s[1]
        s[1] ^= s[2]
        s[0] ^= s[3]
        s[2] ^= t
        s[3] = _rotl(s[3], 45)
        return result

    def uniform(self) -> float:
        """53-bit uniform double in [0, 1)."""
        return uniform_double(self.next_u64())


def uniform_double(u64: int) -> float:
    return (u64 >> 11) * 2.0**-53


def seed_u64(stream_seed: str, scope: str) -> int:
    """First 8 bytes of SHA-256(stream_seed || ":" || scope), big-endian."""
    digest = hashlib.sha256(f"{stream_seed}:{scope}".encode("ascii")).digest()
    return int.from_bytes(digest[:8], "big")


def rng_for(stream_seed: str, scope: str) -> Xoshiro256StarStar:
    return Xoshiro256StarStar(seed_u64(stream_seed, scope))
