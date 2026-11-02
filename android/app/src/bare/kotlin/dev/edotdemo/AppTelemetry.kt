package dev.edotdemo

import android.app.Application
import android.os.Build
import okhttp3.Call
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.time.Instant
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * "Bare HTTP": no OpenTelemetry. Everything is a JSON document POSTed to Elasticsearch's _bulk API.
 *
 * What you get is what you write. There is no trace context, no session, no crash capture, no
 * disk buffer (events are dropped if the POST fails or the process dies), and no prebuilt dashboards.
 */
object AppTelemetry {
    private const val INDEX = "mobile-bare-telemetry"
    private const val SERVICE = "mobile-demo-android-bare"

    private val queue = LinkedBlockingQueue<JSONObject>(5000)
    // Separate client with no interceptor, so exporting never generates telemetry about itself.
    private val exporter = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build()

    fun start(app: Application) {
        if (BuildConfig.ES_URL.isEmpty()) return
        Thread({
            while (true) {
                val batch = ArrayList<JSONObject>()
                batch += queue.take()
                queue.drainTo(batch, 199)
                send(batch)
            }
        }, "bare-telemetry-flush").apply { isDaemon = true }.start()
    }

    /** Best effort: send whatever is queued, on the caller's thread. */
    fun flush() {
        val batch = ArrayList<JSONObject>()
        queue.drainTo(batch)
        if (batch.isNotEmpty()) send(batch)
    }

    /** One document per user-visible operation. */
    fun <T> action(name: String, attrs: Map<String, String> = emptyMap(), block: () -> T): T {
        val doc = doc(name, attrs)
        val startNs = System.nanoTime()
        try {
            val r = block()
            doc.put("event.outcome", "success")
            return r
        } catch (e: Throwable) {
            doc.put("event.outcome", "failure")
            doc.put("error.type", e.javaClass.name)
            doc.put("error.message", e.message ?: "")
            throw e
        } finally {
            putDuration(doc, startNs)
            queue.offer(doc)
        }
    }

    /** event.duration is nanoseconds (as in OTel); event.duration_ms is the same value for humans and SLO queries. */
    private fun putDuration(doc: JSONObject, startNs: Long) {
        val ns = System.nanoTime() - startNs
        doc.put("event.duration", ns)
        doc.put("event.duration_ms", ns / 1_000_000.0)
    }

    fun event(name: String, attrs: Map<String, String> = emptyMap()) {
        queue.offer(doc(name, attrs))
    }

    fun callFactory(client: OkHttpClient): Call.Factory =
        client.newBuilder().addInterceptor(Interceptor { chain ->
            val req = chain.request()
            val startNs = System.nanoTime()
            val doc = doc("http.client", emptyMap()).apply {
                put("http.request.method", req.method)
                put("url.full", req.url.toString())
                put("url.domain", req.url.host)
            }
            try {
                val resp = chain.proceed(req)
                doc.put("http.response.status_code", resp.code)
                doc.put("event.outcome", if (resp.code >= 400) "failure" else "success")
                resp
            } catch (e: Exception) {
                doc.put("event.outcome", "failure")
                doc.put("error.type", e.javaClass.name)
                doc.put("error.message", e.message ?: "")
                throw e
            } finally {
                putDuration(doc, startNs)
                queue.offer(doc)
            }
        }).build()

    private fun doc(name: String, attrs: Map<String, String>) = JSONObject().apply {
        put("@timestamp", Instant.now().toString())
        put("service.name", SERVICE)
        put("service.version", BuildConfig.VERSION_NAME)
        put("event.dataset", "mobile.bare")
        put("event.name", name)
        put("os.name", "Android")
        put("os.version", Build.VERSION.RELEASE)
        put("device.model.identifier", Build.MODEL)
        (AppContext.commonAttrs() + attrs).forEach { (k, v) -> put(k, v) }
    }

    private fun send(batch: List<JSONObject>) {
        val body = buildString { batch.forEach { append("{\"index\":{}}\n").append(it).append('\n') } }
        val req = Request.Builder()
            .url("${BuildConfig.ES_URL.trimEnd('/')}/$INDEX/_bulk")
            .header("Authorization", "ApiKey ${BuildConfig.ES_API_KEY}")
            .post(body.toRequestBody("application/x-ndjson".toMediaType()))
            .build()
        try {
            exporter.newCall(req).execute().close()
        } catch (_: Exception) {
            // Dropped. A bare implementation has no retry or disk buffering.
        }
    }
}
