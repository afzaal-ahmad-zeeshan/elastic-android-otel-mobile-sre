package dev.edotdemo

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.system.exitProcess

/** What the autopilot needs from the UI. Both run on the main thread, like a real tap. */
interface UiHooks {
    fun openStats()
    fun refresh()
}

object Titles {
    private val plain = listOf("Buy milk", "Email Sam", "Book dentist", "Pay rent", "Plan sprint", "Call mum", "Fix bike", "Renew passport")
    private val fancy = listOf("Ship it 🚀🔥", "Café meeting ☕", "Party 🎉🎉🎉")

    fun next(emojiRate: Double): String =
        if (Random.nextDouble() < emojiRate) fancy.random() else "${plain.random()} #${Random.nextInt(1000)}"
}

/** Drives the real controller the way a persona would. Think times are scaled by `speed`. */
class Autopilot(private val store: TaskStore, private val ui: UiHooks) {
    private val main = Handler(Looper.getMainLooper())

    fun start() {
        thread(name = "autopilot") {
            val deadline = System.nanoTime() + AppContext.durationSec * 1_000_000_000L
            store.sync()
            while (System.nanoTime() < deadline) {
                val t0 = System.nanoTime()
                step()
                val ms = (System.nanoTime() - t0) / 1_000_000
                if (AppContext.persona == "impatient" && ms > 3_000) return@thread finish("abandoned")
                Thread.sleep((Random.nextLong(800, 3000) / AppContext.speed).toLong())
            }
            finish("completed")
        }
    }

    private fun step() {
        val open = store.tasks.filter { !it.done }
        val action = pick(
            when (AppContext.persona) {
                "planner" -> listOf("create" to 60, "stats" to 10, "sync" to 10, "complete" to 10, "search" to 10)
                "browser" -> listOf("search" to 40, "stats" to 30, "sync" to 20, "create" to 10)
                else -> listOf("complete" to 45, "create" to 25, "sync" to 15, "delete" to 10, "stats" to 5) // finisher, impatient
            }
        )
        when {
            action == "create" || (action in setOf("complete", "delete") && open.isEmpty()) ->
                store.create(Titles.next(AppContext.faults.emojiRate))
            action == "complete" -> store.complete(open.random().id)
            action == "delete" -> store.delete(store.tasks.random().id)
            action == "search" -> store.search(listOf("milk", "mail", "call", "plan").random())
            action == "sync" -> store.sync()
            action == "stats" -> onMain { ui.openStats() }
        }
        main.post { ui.refresh() }
    }

    private fun pick(weights: List<Pair<String, Int>>): String {
        var r = Random.nextInt(weights.sumOf { it.second })
        for ((k, w) in weights) { if (r < w) return k; r -= w }
        return weights.last().first
    }

    private fun onMain(block: () -> Unit) {
        val latch = CountDownLatch(1)
        main.post { try { block() } finally { latch.countDown() } }
        latch.await()
    }

    private fun finish(reason: String) {
        AppTelemetry.event("session.end", mapOf("reason" to reason))
        AppTelemetry.event("app.background")
        AppTelemetry.flush()
        exitProcess(0)
    }
}

/** N in-process users hitting the backend concurrently. Network-level only; they share this process's session. */
object VirtualUsers {
    fun start(n: Int) {
        val run = Random.nextInt(100_000)
        repeat(n) { i ->
            thread(name = "vu-$i", isDaemon = true) {
                val store = TaskStore("vu-$run-$i", virtual = true)
                Thread.sleep(Random.nextLong(0, 3000)) // stagger the ramp-up
                store.sync()
                while (true) {
                    val open = store.tasks.filter { !it.done }
                    when (Random.nextInt(10)) {
                        in 0..3 -> store.create("${listOf("Buy milk", "Email Sam", "Plan sprint").random()} #${Random.nextInt(1000)}")
                        in 4..6 -> if (open.isNotEmpty()) store.complete(open.random().id)
                        7 -> store.search("milk")
                        else -> store.sync()
                    }
                    Thread.sleep((Random.nextLong(800, 3000) / AppContext.speed).toLong())
                }
            }
        }
    }
}
