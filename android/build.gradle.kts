plugins {
    id("com.android.application") version "8.8.2" apply false
    kotlin("android") version "2.0.20" apply false
    kotlin("jvm") version "2.0.20" apply false
    kotlin("plugin.compose") version "2.0.20" apply false
    // Animal Sniffer guards :core/:runtime (pure JVM, built with JDK 17)
    // against APIs missing from the app's minSdk: Android lint's NewApi
    // cannot analyze non-Android modules, the JVM tests run on desktop
    // Java, and the emulator is API 35 — so e.g. the Java 9
    // LocalDate.ofInstant crashed only on real sub-14 phones.
    id("ru.vyarus.animalsniffer") version "2.0.1" apply false
}
