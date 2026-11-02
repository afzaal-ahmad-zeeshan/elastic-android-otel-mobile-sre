package dev.edotdemo

import android.app.Application
import co.elastic.otel.android.ElasticApmAgent
import co.elastic.otel.android.connectivity.Authentication
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.sdk.resources.Resource
import okhttp3.Call
import okhttp3.OkHttpClient

/**
 * edotFull (agent + okhttp + crash plugins) plus ONE addition: the facade turns the app's own moments
 * into spans. Each `action(...)` (tap, screen view, sync, launch) is a span and the current span while its
 * block runs, so the okhttp plugin's HTTP client span becomes its child and the backend span follows through
 * `traceparent`. `event(...)` records an instant span (foreground, background, session end, hang).
 * Everything else is identical to edotFull, so the difference between the two is exactly this file.
 */
object AppTelemetry {
    private var agent: ElasticApmAgent? = null
    private var tracer: Tracer? = null

    fun start(app: Application) {
        if (BuildConfig.OTLP_ENDPOINT.isEmpty()) return // not configured: run without telemetry
        agent = ElasticApmAgent.builder(app)
            .setServiceName(BuildConfig.SERVICE_NAME)
            .setServiceVersion(BuildConfig.VERSION_NAME)
            .setResourceInterceptor { r -> r.merge(Resource.create(Attributes.of(AttributeKey.stringKey("telemetry.flavor"), BuildConfig.TELEMETRY_FLAVOR))) }
            .addSpanAttributesInterceptor { a -> a.withIdentity() }
            .addLogRecordAttributesInterceptor { a -> a.withIdentity() }
            .setExportUrl(BuildConfig.OTLP_ENDPOINT)
            .setExportAuthentication(Authentication.ApiKey(BuildConfig.OTLP_API_KEY))
            .build()
        tracer = agent?.getOpenTelemetry()?.getTracer("dev.edotdemo.app")
    }

    fun <T> action(name: String, attrs: Map<String, String> = emptyMap(), block: () -> T): T {
        val t = tracer ?: return block()
        val span = t.spanBuilder(name).setSpanKind(SpanKind.INTERNAL).also { b ->
            attrs.forEach { (k, v) -> b.setAttribute(k, v) }
        }.startSpan()
        val scope = span.makeCurrent()
        try {
            return block()
        } catch (e: Throwable) {
            span.recordException(e)
            span.setStatus(StatusCode.ERROR, e.message ?: e.javaClass.simpleName)
            throw e
        } finally {
            scope.close()
            span.end()
        }
    }

    fun event(name: String, attrs: Map<String, String> = emptyMap()) {
        val t = tracer ?: return
        t.spanBuilder(name).setSpanKind(SpanKind.INTERNAL).also { b ->
            b.setAttribute("app.event", "true")
            attrs.forEach { (k, v) -> b.setAttribute(k, v) }
        }.startSpan().end()
    }

    fun flush() {
        agent?.flushSpans()?.join(3, java.util.concurrent.TimeUnit.SECONDS)
        agent?.flushLogRecords()?.join(3, java.util.concurrent.TimeUnit.SECONDS)
    }

    fun callFactory(client: OkHttpClient): Call.Factory = client

    private fun Attributes.withIdentity(): Attributes =
        toBuilder().apply { AppContext.analyticsAttrs().forEach { (k, v) -> put(AttributeKey.stringKey(k), v) } }.build()
}
