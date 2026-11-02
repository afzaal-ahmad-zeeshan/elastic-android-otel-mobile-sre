package dev.edotdemo

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build

/** Reports ANRs that killed a previous run (API 30+).  */
object ExitInfoReporter {
    fun report(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val prefs = ctx.getSharedPreferences("exit_info", Context.MODE_PRIVATE)
        val last = prefs.getLong("last_ts", 0)
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val exits = am.getHistoricalProcessExitReasons(ctx.packageName, 0, 10).filter { it.timestamp > last }
        exits.filter { it.reason == ApplicationExitInfo.REASON_ANR }.forEach {
            AppTelemetry.event(
                "app.anr_previous_run",
                mapOf("exit.timestamp" to it.timestamp.toString(), "exit.description" to (it.description ?: ""))
            )
        }
        exits.maxOfOrNull { it.timestamp }?.let { prefs.edit().putLong("last_ts", it).apply() }
    }
}
