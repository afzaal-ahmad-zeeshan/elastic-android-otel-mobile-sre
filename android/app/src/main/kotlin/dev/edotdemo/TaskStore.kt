package dev.edotdemo

import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The app's controller. The UI and the autopilot call the same methods, and every user-visible
 * operation is one telemetry action. Call from a worker thread (these block on the network).
 * Only IOException is handled; any other exception is a bug and crashes the app on purpose.
 */
class TaskStore(val user: String, private val virtual: Boolean = false) {
    val tasks = CopyOnWriteArrayList<Task>()

    private fun attrs(vararg extra: Pair<String, String>): Map<String, String> =
        buildMap {
            put("user.id", user)
            if (virtual) put("app.virtual", "true")
            putAll(extra)
        }

    /** The list screen's page load: time until its data is in hand and it can render. */
    fun loadList(): Boolean = try {
        AppTelemetry.action("screen.view", attrs("screen" to "list")) {
            val remote = TaskApi.list(user)
            tasks.clear(); tasks.addAll(remote)
        }
        true
    } catch (_: IOException) { false }

    fun create(title: String): Boolean = try {
        AppTelemetry.action("task.create", attrs("task.title_length" to title.length.toString())) {
            Faults.notificationPreview(title)
            tasks += TaskApi.create(user, title)
        }
        true
    } catch (_: IOException) { false }

    fun complete(id: String): Boolean = try {
        AppTelemetry.action("task.complete", attrs("task.id" to id)) {
            TaskApi.complete(user, id)
            val i = tasks.indexOfFirst { it.id == id }
            if (i >= 0) tasks[i] = tasks[i].copy(done = true)
        }
        true
    } catch (_: IOException) { false }

    fun delete(id: String): Boolean = try {
        AppTelemetry.action("task.delete", attrs("task.id" to id)) {
            TaskApi.delete(user, id)
            tasks.removeAll { it.id == id }
        }
        true
    } catch (_: IOException) { false }

    fun search(q: String): List<Task> = try {
        AppTelemetry.action("task.search", attrs("query_length" to q.length.toString())) { TaskApi.search(user, q) }
    } catch (_: IOException) { emptyList() }

    fun sync(): Boolean = try {
        AppTelemetry.action("sync", attrs()) {
            val remote = TaskApi.sync(user, strict = !virtual)
            tasks.clear(); tasks.addAll(remote)
        }
        true
    } catch (_: IOException) { false }
}
