package dev.edotdemo

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * App-owned hang detection (none of bare/otel/edot ships this). A background thread posts a
 * heartbeat to the main thread; if it runs more than THRESHOLD_MS late, emit `app.hang` once the
 * main thread recovers. If the system kills the app first (ANR), `ExitInfoReporter` reports it
 * on the next launch instead.
 */
object HangWatchdog {
    private const val THRESHOLD_MS = 2_000L
    private const val PERIOD_MS = 500L

    fun start() {
        val main = Handler(Looper.getMainLooper())
        Thread({
            while (true) {
                val posted = SystemClock.uptimeMillis()
                main.post {
                    val lateMs = SystemClock.uptimeMillis() - posted
                    if (lateMs > THRESHOLD_MS) {
                        AppTelemetry.event("app.hang", mapOf("duration_ms" to lateMs.toString()))
                    }
                }
                Thread.sleep(PERIOD_MS)
            }
        }, "hang-watchdog").apply { isDaemon = true }.start()
    }
}
