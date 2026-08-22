package pes.core

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test

class QuickConformanceTest {
    private val doc = loadSpec("quick_vectors.json")

    @Test
    fun indexCases() {
        for (case in doc.objList("index_cases")) {
            val got = (0 until case.int("count")).map { isFull(it, case.int("n")) }
            val expected = case.arr("expected_full").map { (it as JsonPrimitive).content.toBooleanStrict() }
            assertEquals(expected, got, case.str("name"))
            if (got.isNotEmpty()) assertTrue(got[0]) // first ping of the day is always full
        }
    }

    @Test
    fun fieldCases() {
        for (case in doc.objList("field_cases")) {
            val got = presentedFields(case.obj("survey"), case.bool("full", false)).map { it.str("id") }
            val expected = case.optStringList("expected_field_ids")!!
            assertEquals(expected, got, case.str("name"))
        }
    }
}
