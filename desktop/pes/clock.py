"""Injectable clock so the engine and sync are testable with simulated time.

All engine time is whole epoch seconds (UTC), matching the core.
"""

from __future__ import annotations

import time


class SystemClock:
    def now(self) -> int:
        return int(time.time())


class FakeClock:
    """Settable clock for scenario tests."""

    def __init__(self, epoch: int):
        self._now = epoch

    def now(self) -> int:
        return self._now

    def set(self, epoch: int) -> None:
        self._now = epoch

    def advance(self, seconds: int) -> None:
        self._now += seconds
