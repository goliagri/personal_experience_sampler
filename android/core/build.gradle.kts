plugins {
    kotlin("jvm")
    id("ru.vyarus.animalsniffer") // Android minSdk API guard; see root build file
}

dependencies {
    signature("com.toasttab.android:gummy-bears-api-29:0.12.0@signature")
    // Tree API only (Json.parseToJsonElement); no serialization compiler plugin needed.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
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
