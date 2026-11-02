package dev.edotdemo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.edotdemo.ui.DemoTheme
import dev.edotdemo.ui.TaskActions
import dev.edotdemo.ui.TasksScreen
import dev.edotdemo.ui.UiModel
import java.util.concurrent.Executors

class MainActivity : ComponentActivity(), UiHooks {
    private val io = Executors.newFixedThreadPool(2)
    private lateinit var store: TaskStore
    private val ui = UiModel()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        AppContext.applyIntent(intent)
        ExitInfoReporter.report(this)
        store = TaskStore(AppContext.userId)

        AppTelemetry.action("app.launch", mapOf("launch.autopilot" to AppContext.autopilot.toString())) {
            setContent {
                DemoTheme {
                    TasksScreen(ui, BuildConfig.TELEMETRY_MODE, AppContext.userId, actions())
                }
            }
        }
        background("Couldn't load tasks. Is the backend reachable?") { store.loadList() }

        if (AppContext.autopilot) {
            Autopilot(store, this).start()
            if (AppContext.vusers > 0) VirtualUsers.start(AppContext.vusers)
        }
    }

    override fun onStart() { super.onStart(); AppTelemetry.event("app.foreground") }
    override fun onStop() { super.onStop(); AppTelemetry.event("app.background") }

    private fun actions() = TaskActions(
        add = { title -> background("Couldn't add the task. Check your connection.") { store.create(title) } },
        complete = { t -> background("Couldn't complete the task.") { store.complete(t.id) } },
        delete = { t -> background("Couldn't delete the task.") { store.delete(t.id) } },
        search = { q ->
            io.execute {
                val hits = if (q.isBlank()) store.tasks.toList() else store.search(q)
                runOnUiThread { ui.searchResults = hits }
            }
        },
        sync = { background("Couldn't sync tasks.") { store.sync() } },
        stats = { openStats() },
        crash = { throw IllegalStateException("Intentional demo crash (${BuildConfig.TELEMETRY_MODE})") },
        hang = { Thread.sleep(8_000) },
    )

    /** Fault #1 lives behind this: the stats summary is computed on the main thread. */
    override fun openStats() {
        AppTelemetry.action("screen.view", mapOf("screen" to "stats")) {
            ui.stats = Faults.computeStats(store.tasks)
        }
    }

    override fun refresh() { ui.tasks = store.tasks.toList() }

    /** Run a store operation off the main thread; refresh the list and report a failure when it ends. */
    private fun background(failure: String, op: () -> Boolean) {
        ui.busy++
        io.execute {
            val ok = op()
            runOnUiThread {
                ui.busy--
                refresh()
                if (!ok) ui.messages.trySend(failure)
            }
        }
    }
}
