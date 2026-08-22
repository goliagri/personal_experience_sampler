// The Android app module (:app) is added at Milestone 4; :core is a pure-JVM
// Kotlin module so the shared deterministic core and its conformance suite
// run without the Android SDK.
rootProject.name = "pes-android"

include(":core")
