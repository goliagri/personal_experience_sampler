"""Pure-Python port of fdlibm's __ieee754_log (spec §6.2).

Kotlin uses ``StrictMath.log``, which is fdlibm. Python's ``math.log`` is the
platform libm and is NOT guaranteed bit-identical across platforms, so the
schedule code must call :func:`fdlibm_log` instead. Every float operation below
is IEEE-754 double arithmetic (Python floats), so the port reproduces the C
routine bit for bit; ``spec/log_vectors.json`` pins the outputs.

Ported from fdlibm 5.3 e_log.c (Sun Microsystems, freely distributable).
"""

from __future__ import annotations

import struct


def _from_words(hi: int, lo: int) -> float:
    return struct.unpack(">d", struct.pack(">II", hi & 0xFFFFFFFF, lo & 0xFFFFFFFF))[0]


def _words(x: float) -> tuple[int, int]:
    hi, lo = struct.unpack(">II", struct.pack(">d", x))
    return hi, lo


def _set_high_word(x: float, hi: int) -> float:
    _, lo = _words(x)
    return _from_words(hi, lo)


# Constants defined by their exact IEEE-754 bit patterns, as in e_log.c.
_LN2_HI = _from_words(0x3FE62E42, 0xFEE00000)  # 6.93147180369123816490e-01
_LN2_LO = _from_words(0x3DEA39EF, 0x35793C76)  # 1.90821492927058770002e-10
_TWO54 = _from_words(0x43500000, 0x00000000)  # 1.80143985094819840000e+16
_LG1 = _from_words(0x3FE55555, 0x55555593)
_LG2 = _from_words(0x3FD99999, 0x9997FA04)
_LG3 = _from_words(0x3FD24924, 0x94229359)
_LG4 = _from_words(0x3FCC71C5, 0x1D8E78AF)
_LG5 = _from_words(0x3FC74664, 0x96CB03DE)
_LG6 = _from_words(0x3FC39A09, 0xD078C69F)
_LG7 = _from_words(0x3FC2F112, 0xDF3E5244)

_INF = float("inf")
_NAN = float("nan")


def fdlibm_log(x: float) -> float:
    hx, lx = _words(x)
    if hx >= 0x80000000:
        hx -= 1 << 32  # signed high word

    k = 0
    if hx < 0x00100000:  # x < 2**-1022
        if ((hx & 0x7FFFFFFF) | lx) == 0:
            return -_INF  # log(+-0) = -inf
        if hx < 0:
            return _NAN  # log(negative) = NaN
        k -= 54
        x *= _TWO54  # subnormal: scale up
        hx, _ = _words(x)
    if hx >= 0x7FF00000:
        return x + x  # +inf or NaN
    k += (hx >> 20) - 1023
    hx &= 0x000FFFFF
    i = (hx + 0x95F64) & 0x100000
    x = _set_high_word(x, hx | (i ^ 0x3FF00000))  # normalize x or x/2
    k += i >> 20
    f = x - 1.0
    if (0x000FFFFF & (2 + hx)) < 3:  # -2**-20 <= f < 2**-20
        if f == 0.0:
            if k == 0:
                return 0.0
            dk = float(k)
            return dk * _LN2_HI + dk * _LN2_LO
        r = f * f * (0.5 - 0.33333333333333333 * f)
        if k == 0:
            return f - r
        dk = float(k)
        return dk * _LN2_HI - ((r - dk * _LN2_LO) - f)
    s = f / (2.0 + f)
    dk = float(k)
    z = s * s
    i = hx - 0x6147A
    w = z * z
    j = 0x6B851 - hx
    t1 = w * (_LG2 + w * (_LG4 + w * _LG6))
    t2 = z * (_LG1 + w * (_LG3 + w * (_LG5 + w * _LG7)))
    i |= j
    r = t2 + t1
    if i > 0:
        hfsq = 0.5 * f * f
        if k == 0:
            return f - (hfsq - s * (hfsq + r))
        return dk * _LN2_HI - ((hfsq - (s * (hfsq + r) + dk * _LN2_LO)) - f)
    else:
        if k == 0:
            return f - s * (f - r)
        return dk * _LN2_HI - ((s * (f - r) - dk * _LN2_LO) - f)
