/**
 * Protocol interface (spec §6.1). Mirrors `pes/core/protocols/base.py`.
 *
 * A protocol's generate(protocolConfig, streamSeed, localDay, zone) returns
 * candidate UTC times for one local calendar day, in generation order. The
 * scheduler core (collision rule, quiet zones, config piecewise) is layered
 * on top in Scheduler.kt; protocols only emit candidates.
 */
package pes.core.protocols

import java.time.LocalDate
import java.time.ZoneId
import kotlinx.serialization.json.JsonObject

/**
 * One candidate ping time. `utc` is epoch seconds (whole). `dstGap` marks a
 * candidate whose local specification did not exist on this day
 * (spring-forward); the scheduler turns it into suppressed(dst).
 */
data class Candidate(val utc: Long, val dstGap: Boolean = false)

typealias GenerateFn = (JsonObject, String, LocalDate, ZoneId) -> List<Candidate>

private val registry = mutableMapOf<String, GenerateFn>()

fun register(protocolType: String, fn: GenerateFn) {
    registry[protocolType] = fn
}

fun getProtocol(protocolType: String): GenerateFn = registry.getValue(protocolType)

/** Idempotent registration of the v1 protocols. */
fun registerAll() {
    register("poisson", ::generatePoisson)
    register("stratified", ::generateStratified)
    register("fixed_interval", ::generateFixedInterval)
    register("fixed_times", ::generateFixedTimes)
}
