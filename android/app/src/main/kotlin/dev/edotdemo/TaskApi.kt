package dev.edotdemo

import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class Task(val id: String, val title: String, val done: Boolean, val created: Long, val due: String?)

/** Blocking client for backend/server.py. Throws IOException on transport errors and non-2xx. */
object TaskApi {
    private val client = OkHttpClient.Builder()
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    private val calls: Call.Factory by lazy { AppTelemetry.callFactory(client) }
    private val base get() = AppContext.backend.trimEnd('/')
    private val json = "application/json".toMediaType()

    private fun exec(req: Request): String =
        calls.newCall(req).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            body
        }

    private fun get(path: String) = exec(Request.Builder().url("$base$path").build())

    fun list(user: String): List<Task> = tasks(get("/tasks?user=$user"), strict = false)

    fun create(user: String, title: String): Task {
        val body = JSONObject().put("user", user).put("title", title).toString().toRequestBody(json)
        return task(JSONObject(exec(Request.Builder().url("$base/tasks").post(body).build())), strict = false)
    }

    fun complete(user: String, id: String) {
        exec(Request.Builder().url("$base/tasks/$id/complete?user=$user").post("".toRequestBody(json)).build())
    }

    fun delete(user: String, id: String) {
        exec(Request.Builder().url("$base/tasks/$id?user=$user").delete().build())
    }

    fun search(user: String, q: String): List<Task> =
        tasks(get("/search?user=$user&q=${java.net.URLEncoder.encode(q, "UTF-8")}"), strict = false)

    /**
     * Sync uses the strict parser; this is where backend schema drift crashes the app (fault #2).
     * Virtual users pass strict = false so one drift does not kill a whole load run.
     */
    fun sync(user: String, strict: Boolean = true): List<Task> = tasks(get("/sync?user=$user"), strict)

    private fun tasks(body: String, strict: Boolean): List<Task> {
        val arr: JSONArray = JSONObject(body).getJSONArray("tasks")
        return List(arr.length()) { task(arr.getJSONObject(it), strict) }
    }

    private fun task(o: JSONObject, strict: Boolean): Task {
        val due: String? = if (strict) {
            // Assumes `due` is a string or null. A backend release that sends an object breaks this.
            val d = o.opt("due")
            if (d == null || d == JSONObject.NULL) null else d as String
        } else {
            o.optString("due").ifEmpty { null }
        }
        return Task(o.getString("id"), o.getString("title"), o.getBoolean("done"), o.getLong("created"), due)
    }
}
