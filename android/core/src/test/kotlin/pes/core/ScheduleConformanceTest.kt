package pes.core

import java.time.LocalDate
import kotlin.test.assertEquals
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Test

class ScheduleConformanceTest {

    @Test
    fun allCases() {
        val cases = loadSpec("schedule_vectors.json").objList("cases")
        check(cases.isNotEmpty())
        for (case in cases) {
            val resolved = resolveDay(
                case.objList("config_history"),
                case.str("stream"),
                LocalDate.parse(case.str("local_day")),
            )
            val expected = case.objList("expected")
            assertEquals(expected.size, resolved.size, case.str("name"))
            for ((exp, got) in expected.zip(resolved)) {
                assertEquals(exp.str("scheduled_utc"), fmtUtc(got.scheduledUtc), case.str("name"))
                val expReason = if (exp.getValue("suppressed") is JsonNull) null else exp.str("suppressed")
                assertEquals(expReason, got.suppressedReason, case.str("name"))
                assertEquals(exp.int("config_v"), got.configV, case.str("name"))
                assertEquals(exp.int("index"), got.index, case.str("name"))
            }
        }
    }
}
