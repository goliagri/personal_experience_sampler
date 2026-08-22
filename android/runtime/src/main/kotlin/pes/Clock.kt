/** Injectable clock (mirrors `pes/clock.py`): epoch seconds, UTC. */
package pes

interface Clock {
    fun now(): Long
}

class SystemClock : Clock {
    override fun now(): Long = System.currentTimeMillis() / 1000
}

/** Manually advanced clock for scenario tests. */
class FakeClock(var epoch: Long) : Clock {
    override fun now(): Long = epoch

    fun advance(seconds: Long) {
        epoch += seconds
    }

    fun set(newEpoch: Long) {
        epoch = newEpoch
    }
}
