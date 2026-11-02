buildscript {
    dependencies {
        // AGP's built-in Kotlin is 2.2.x; the upstream OpenTelemetry Android agent 1.7.0 ships
        // Kotlin 2.4 metadata, so raise the compiler to match the Compose plugin version.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
    }
}

plugins {
    id("com.android.application") version "9.4.1" apply false
}
