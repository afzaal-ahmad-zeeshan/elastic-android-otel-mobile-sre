package dev.edotdemo

import android.app.Application

class DemoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Each flavor provides its own AppTelemetry (src/bare, src/otel, src/edot).
        AppTelemetry.start(this)
        HangWatchdog.start()
    }
}
