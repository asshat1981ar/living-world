"""32-bit float emulation (Kotlin Float) and the two Float-to-text rules used by the kernel."""
import math
import struct

_P = struct.Struct("<f")
_I = struct.Struct("<i")
INF = math.inf


def f32(x):
    """Round a double (or int) to the nearest IEEE-754 binary32 value, returned as a Python float."""
    try:
        return _P.unpack(_P.pack(x))[0]
    except OverflowError:
        return INF if x > 0 else -INF


def coerce_in(v, lo, hi):
    """Kotlin Float.coerceIn (NaN passes through)."""
    if v < lo:
        return lo
    if v > hi:
        return hi
    return v


def coerce_at_least(v, lo):
    return lo if v < lo else v


def raw_bits(v):
    return _I.unpack(_P.pack(v))[0]


def java_round(x):
    """java.lang.Math.round(double): floor(x + 1/2) computed exactly, saturating to Long; NaN -> 0."""
    if x != x:
        return 0
    if x in (INF, -INF):
        return 2**63 - 1 if x > 0 else -(2**63)
    fl = math.floor(x)
    r = fl + 1 if (x - fl) >= 0.5 else fl
    return max(-(2**63), min(2**63 - 1, r))


def _text(scaled):
    magnitude = -scaled if scaled < 0 else scaled
    fraction = str(magnitude % 10000).rjust(4, "0").rstrip("0") or "0"
    return ("-" if scaled < 0 else "") + str(magnitude // 10000) + "." + fraction


def f4(value):
    """CanonicalDump.f4: Math.round (half up) on value*10000 in double."""
    if value != value:
        return "NaN"
    return _text(java_round(value * 10000.0))


def stable_float_text(value):
    """beliefs/Belief.kt stableFloatText: kotlin.math.round (half even) on value*10000 in double."""
    if value != value:
        return "NaN"
    if value in (INF, -INF):
        return "Infinity" if value > 0 else "-Infinity"
    if abs(value) >= 1.0e9:
        return "bits:" + str(raw_bits(value))
    return _text(int(round(value * 10000.0)))
