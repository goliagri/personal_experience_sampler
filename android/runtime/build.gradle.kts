// Pure-JVM runtime layer mirroring desktop/pes/{engine,sync,store}: the
// headless engine, the §8.4 sync procedure, and the local database written
// against androidx.sqlite so the same code runs in JVM scenario tests
// (BundledSQLiteDriver) and inside the Android app.
plugins {
    kotlin("jvm")
    id("ru.vyarus.animalsniffer") // Android minSdk API guard; see root build file
}

dependencies {
    signature("com.toasttab.android:gummy-bears-api-29:0.12.0@signature")
    api(project(":core"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    api("androidx.sqlite:sqlite-bundled:2.5.2")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(17)
}

tasks.test {
    useJUnitPlatform()
}
