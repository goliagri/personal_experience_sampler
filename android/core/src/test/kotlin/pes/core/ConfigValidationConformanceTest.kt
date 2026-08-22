package pes.core

import kotlin.test.assertEquals
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test

class ConfigValidationConformanceTest {

    @Test
    fun allCases() {
        val cases = loadSpec("config_validation.json").objList("cases")
        check(cases.isNotEmpty())
        for (case in cases) {
            val surveys = (case.getValue("surveys") as JsonArray).map {
                val pair = it as JsonArray
                Pair((pair[0] as JsonPrimitive).content, (pair[1] as JsonPrimitive).content.toInt())
            }
            val got = validateConfig(case.obj("config"), surveys, case.str("now"))
            val expected = case.optStringList("expected_errors")!!
            assertEquals(expected, got, case.str("name"))
        }
    }
}
