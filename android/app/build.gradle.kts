import co.elastic.otel.android.plugin.extensions.ElasticExtension
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21"
    id("co.elastic.otel.android.agent") version "1.10.0"
    id("co.elastic.otel.android.instrumentation.okhttp") version "1.10.0"
    id("co.elastic.otel.android.instrumentation.crash") version "1.10.0"
}

val telemetryProps = Properties().apply {
    rootProject.file("telemetry.properties").takeIf { it.exists() }?.reader()?.use(::load)
}

fun prop(name: String, default: String = ""): String =
    (telemetryProps.getProperty(name) ?: System.getenv(name) ?: default).trim()

android {
    namespace = "dev.edotdemo"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.edotdemo.telemetry"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        buildConfigField("String", "ES_URL", "\"${prop("ES_URL")}\"")
        buildConfigField("String", "ES_API_KEY", "\"${prop("ES_API_KEY")}\"")
        buildConfigField("String", "OTLP_ENDPOINT", "\"${prop("OTLP_ENDPOINT")}\"")
        buildConfigField("String", "OTLP_API_KEY", "\"${prop("OTLP_API_KEY")}\"")
        buildConfigField("String", "TARGET_BASE_URL", "\"${prop("TARGET_BASE_URL", "http://10.0.2.2:8080")}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    // One flavor per telemetry approach. Pick the build variant (bareDebug, otelDebug,
    // edotDebug, edotFullDebug, ...) in Android Studio or with ./gradlew installOtelDebug.
    flavorDimensions += "telemetry"
    productFlavors {
        create("bare") {
            dimension = "telemetry"
            applicationIdSuffix = ".bare"
            buildConfigField("String", "TELEMETRY_MODE", "\"bare\"")
            buildConfigField("String", "TELEMETRY_FLAVOR", "\"bare\"")
            // No bytecode weaving: this flavor must stay free of agent instrumentation.
            extensions.configure<ElasticExtension> { bytecodeInstrumentation.disabled.set(true) }
        }
        create("otel") {
            dimension = "telemetry"
            applicationIdSuffix = ".otel"
            buildConfigField("String", "TELEMETRY_MODE", "\"otel\"")
            buildConfigField("String", "TELEMETRY_FLAVOR", "\"otel\"")
            extensions.configure<ElasticExtension> { bytecodeInstrumentation.disabled.set(true) }
        }
        // EDOT as its getting-started page says: agent only, no instrumentation plugins.
        create("edot") {
            dimension = "telemetry"
            applicationIdSuffix = ".edot"
            buildConfigField("String", "TELEMETRY_MODE", "\"edot\"")
            buildConfigField("String", "TELEMETRY_FLAVOR", "\"edot\"")
            buildConfigField("String", "SERVICE_NAME", "\"mobile-demo-android-edot\"")
        }
        // EDOT basic + the documented okhttp and crash instrumentation plugins.
        create("edotFull") {
            dimension = "telemetry"
            applicationIdSuffix = ".edotfull"
            buildConfigField("String", "TELEMETRY_MODE", "\"edotFull\"")
            buildConfigField("String", "TELEMETRY_FLAVOR", "\"edot\"")
            buildConfigField("String", "SERVICE_NAME", "\"mobile-demo-android-edotfull\"")
        }
        // edotFull + the facade turns taps, screen views, sync and lifecycle events into spans (src/edotFullTraced).
        // The name must keep the "edotFull" prefix: the configuration filter below keys on it to keep the plugins.
        create("edotFullTraced") {
            dimension = "telemetry"
            applicationIdSuffix = ".edotfulltraced"
            buildConfigField("String", "TELEMETRY_MODE", "\"edotFullTraced\"")
            buildConfigField("String", "TELEMETRY_FLAVOR", "\"edot\"")
            buildConfigField("String", "SERVICE_NAME", "\"mobile-demo-android-edotfulltraced\"")
        }
    }

    // edotFull reuses the edot facade; only the plugins (and service.name) differ.
    sourceSets.getByName("edotFull").kotlin.srcDir("src/edot/kotlin")

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // UI (all flavors): Jetpack Compose + Material 3 Expressive.
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.material3:material3:1.5.0-beta01") // newer than the BOM's 1.4.0
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.13.0")

    // Shared by every flavor: the app's own HTTP stack.
    // Pinned so all three flavors run the same OkHttp (the OTel instrumentation would otherwise pull 5.x).
    implementation("com.squareup.okhttp3:okhttp") { version { strictly("4.12.0") } }

    // otel flavor: the upstream OpenTelemetry Android agent, used as shipped (one init call, no
    // extra instrumentation modules). The opentelemetry-sdk dependency is only here so the
    // harness can flush before an autopilot run exits; it adds no telemetry.
    "otelImplementation"(platform("io.opentelemetry.android:opentelemetry-android-bom:1.7.0-alpha"))
    "otelImplementation"("io.opentelemetry.android:android-agent")
    "otelImplementation"("io.opentelemetry:opentelemetry-sdk")

    // edot flavor: the agent library itself comes from the co.elastic.otel.android.agent plugin.
}

// The Elastic Gradle plugins are applied to the whole module and add the EDOT agent to every
// variant. Strip it (and, for bare, all of OpenTelemetry) so each flavor contains only its own
// telemetry stack and APK size / method count comparisons are fair.
configurations.configureEach {
    val isBare = name.startsWith("bare")
    val isOtel = name.startsWith("otel")
    val isEdotBasic = name.startsWith("edot") && !name.startsWith("edotFull")
    if (isEdotBasic) {
        // Basic EDOT: keep the agent, drop the okhttp/crash plugin libraries the Gradle plugins add.
        // Keep co.elastic.otel.android.instrumentation:api: the agent itself loads it at startup.
        listOf("okhttp-library", "okhttp-bytebuddy", "crash-library").forEach {
            exclude(group = "co.elastic.otel.android.instrumentation", module = it)
        }
    }
    if (isBare || isOtel) {
        exclude(group = "co.elastic.otel.android")
        exclude(group = "co.elastic.otel.android.instrumentation")
    }
    if (isBare) {
        exclude(group = "io.opentelemetry")
        exclude(group = "io.opentelemetry.android")
        exclude(group = "io.opentelemetry.instrumentation")
        exclude(group = "io.opentelemetry.semconv")
    }
}
