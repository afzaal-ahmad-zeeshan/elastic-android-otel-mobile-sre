package dev.edotdemo

import android.app.Application
import co.elastic.otel.android.ElasticApmAgent
import co.elastic.otel.android.connectivity.Authentication
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.sdk.resources.Resource
import okhttp3.Call
import okhttp3.OkHttpClient

/**
 * EDOT Android, out of the box: one init call, no manual spans. Shared by the edot (agent only)
 * and edotFull (agent + okhttp and crash Gradle plugins) flavors. OkHttp spans and crash reports
 * come from those plugins (bytecode weaving), so callFactory() is a no-op.
 * Version, `telemetry.flavor` and identity attributes match src/otel. Compare with it: same shape on the upstream agent.
 */
object AppTelemetry {
    private var agent: ElasticApmAgent? = null

    fun start(app: Application) {
        if (BuildConfig.OTLP_ENDPOINT.isEmpty()) return // not configured: run without telemetry
        agent = ElasticApmAgent.builder(app)
            .setServiceName(BuildConfig.SERVICE_NAME)
            .setServiceVersion(BuildConfig.VERSION_NAME)
            .setResourceInterceptor { r -> r.merge(Resource.create(Attributes.of(AttributeKey.stringKey("telemetry.flavor"), BuildConfig.TELEMETRY_FLAVOR))) }
            // Same identity attributes as the otel flavor, evaluated per signal.
            .addSpanAttributesInterceptor { a -> a.withIdentity() }
            .addLogRecordAttributesInterceptor { a -> a.withIdentity() }
            .setExportUrl(BuildConfig.OTLP_ENDPOINT)
            .setExportAuthentication(Authentication.ApiKey(BuildConfig.OTLP_API_KEY))
            .build()
    }

    // No manual instrumentation in this flavor: the facade passes through.
    fun <T> action(name: String, attrs: Map<String, String> = emptyMap(), block: () -> T): T = block()

    fun event(name: String, attrs: Map<String, String> = emptyMap()) {}

    fun flush() {
        agent?.flushSpans()?.join(3, java.util.concurrent.TimeUnit.SECONDS)
        agent?.flushLogRecords()?.join(3, java.util.concurrent.TimeUnit.SECONDS)
    }

    fun callFactory(client: OkHttpClient): Call.Factory = client

    private fun Attributes.withIdentity(): Attributes =
        toBuilder().apply { AppContext.analyticsAttrs().forEach { (k, v) -> put(AttributeKey.stringKey(k), v) } }.build()
}
