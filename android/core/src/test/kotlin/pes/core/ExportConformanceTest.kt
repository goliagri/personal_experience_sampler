package pes.core

import java.io.File
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Test

class ExportConformanceTest {

    @Test
    fun exportCases() {
        val caseDirs = File(SPEC_DIR, "export_vectors").listFiles { f: File -> f.isDirectory }!!
        check(caseDirs.isNotEmpty())
        for (caseDir in caseDirs.sortedBy { it.name }) {
            val spec = Json.parseToJsonElement(File(caseDir, "input.json").readText()) as JsonObject
            val bySample = linkedMapOf<String, MutableList<EventTriple>>()
            for ((path, events) in spec.obj("events")) {
                (events as JsonArray).forEachIndexed { i, ev ->
                    val e = ev as JsonObject
                    bySample.getOrPut(e.str("sample")) { mutableListOf() }.add(Triple(path, i, e))
                }
            }
            val rows = bySample.values.map { foldSample(it, spec.int("expiry_minutes")).first }
            val surveys = spec.objList("surveys").associateBy { Pair(it.str("id"), it.int("version")) }
            val (csvBytes, columns) = exportCsv(rows, surveys, spec.str("timezone"))
            assertEquals(
                File(caseDir, "expected.csv").readBytes().toList(),
                csvBytes.toList(),
                caseDir.name,
            )
            assertEquals(
                File(caseDir, "expected.columns.json").readBytes().decodeToString(),
                columnsJson(columns).decodeToString(),
                caseDir.name,
            )
        }
    }
}
