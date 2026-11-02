package dev.edotdemo

import android.app.Application
import io.opentelemetry.android.OpenTelemetryRum
import io.opentelemetry.android.agent.OpenTelemetryRumInitializer
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.sdk.OpenTelemetrySdk
import okhttp3.Call
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Upstream OpenTelemetry Android agent, out of the box: one init call, default instrumentation,
 * no manual spans, no HTTP wrapping. The only settings are the export endpoint and service.name
 * (needed to filter this app in Kibana), the version, the `telemetry.flavor` marker and the identity attributes
 * from AppContext.analyticsAttrs(), which the edot flavor attaches identically. Compare with src/edot, which is the same shape.
 */
object AppTelemetry {
    private var rum: OpenTelemetryRum? = null

    fun start(app: Application) {
        if (BuildConfig.OTLP_ENDPOINT.isEmpty()) return // not configured: run without telemetry
        rum = OpenTelemetryRumInitializer.initialize(app) {
            httpExport {
                baseUrl = BuildConfig.OTLP_ENDPOINT
                baseHeaders = mapOf("Authorization" to "ApiKey ${BuildConfig.OTLP_API_KEY}")
            }
            resource {
                put("service.name", "mobile-demo-android-otel")
                put("service.version", BuildConfig.VERSION_NAME)
                put("telemetry.flavor", BuildConfig.TELEMETRY_FLAVOR)
            }
            // Same identity attributes as the edot flavor, evaluated per signal.
            // globalAttributes {} is evaluated once at init; the supplier form is evaluated per signal.
            globalAttributesSupplier {
                {
                    Attributes.builder().apply { AppContext.analyticsAttrs().forEach { (k, v) -> put(k, v) } }.build()
                }
            }
        }
    }

    // No manual instrumentation in this flavor: the facade passes through.
    fun <T> action(name: String, attrs: Map<String, String> = emptyMap(), block: () -> T): T = block()

    fun event(name: String, attrs: Map<String, String> = emptyMap()) {}

    fun flush() {
        (rum?.openTelemetry as? OpenTelemetrySdk)?.sdkTracerProvider?.forceFlush()?.join(3, TimeUnit.SECONDS)
        (rum?.openTelemetry as? OpenTelemetrySdk)?.sdkLoggerProvider?.forceFlush()?.join(3, TimeUnit.SECONDS)
    }

    fun callFactory(client: OkHttpClient): Call.Factory = client
}
