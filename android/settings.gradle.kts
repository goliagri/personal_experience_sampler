// :core is a pure-JVM Kotlin module so the shared deterministic core and its
// conformance suite run without the Android SDK. :runtime (also pure JVM)
// mirrors desktop/pes/{engine,sync,store}.py so the sync/merge scenario suite
// runs as plain unit tests. :app is the Android client (Milestone 4).
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "pes-android"

include(":core")
include(":runtime")
include(":app")
