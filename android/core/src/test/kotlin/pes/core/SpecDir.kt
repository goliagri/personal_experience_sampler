package pes.core

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Locate the repo-root spec/ directory from the Gradle test working dir. */
val SPEC_DIR: File = generateSequence(File(".").absoluteFile) { it.parentFile }
    .map { File(it, "spec") }
    .first { File(it, "prng_vectors.json").exists() }

fun loadSpec(name: String): JsonObject =
    Json.parseToJsonElement(File(SPEC_DIR, name).readText()) as JsonObject
