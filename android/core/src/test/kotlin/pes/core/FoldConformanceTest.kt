package pes.core

import kotlin.test.assertEquals
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Test

class FoldConformanceTest {

    @Test
    fun allCases() {
        val cases = loadSpec("fold_vectors.json").objList("cases")
        check(cases.isNotEmpty())
        for (case in cases) {
            val flat = mutableListOf<EventTriple>()
            for ((path, events) in case.obj("files")) {
                (events as kotlinx.serialization.json.JsonArray).forEachIndexed { i, ev ->
                    flat.add(Triple(path, i, ev as JsonObject))
                }
            }
            val (row, warnings) = foldSample(flat, case.int("expiry_minutes"))
            assertEquals(case.obj("expected"), row, case.str("name"))
            assertEquals(case.int("expected_warning_count"), warnings, case.str("name"))
        }
    }
}
