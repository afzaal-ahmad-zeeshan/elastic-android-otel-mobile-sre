package dev.edotdemo

import android.content.Intent

enum class FaultProfile(val anrRate: Double, val emojiRate: Double) {
    NONE(0.0, 0.0), MILD(0.03, 0.02), CHAOS(0.15, 0.10);

    companion object {
        fun parse(s: String?) = entries.firstOrNull { it.name.equals(s, ignoreCase = true) } ?: NONE
    }
}

/** Launch arguments, see DESIGN.md. Set from intent extras (adb shell am start --es/--ez/--ei/--ef). */
object AppContext {
    @Volatile var userId = "u-manual"
    @Volatile var persona = "manual"
    @Volatile var faults = FaultProfile.NONE
    @Volatile var autopilot = false
    @Volatile var durationSec = 60
    @Volatile var vusers = 0
    @Volatile var speed = 1.0
    @Volatile var backend: String = BuildConfig.TARGET_BASE_URL

    fun applyIntent(i: Intent) {
        i.getStringExtra("user")?.let { userId = it }
        i.getStringExtra("persona")?.let { persona = it }
        i.getStringExtra("faults")?.let { faults = FaultProfile.parse(it) }
        i.getStringExtra("backend")?.let { backend = it }
        autopilot = i.getBooleanExtra("autopilot", false)
        durationSec = i.getIntExtra("duration", 60)
        vusers = i.getIntExtra("vusers", 0)
        speed = i.getFloatExtra("speed", 1f).toDouble().coerceAtLeast(0.1)
    }

    /** Attributes added to every action and event by the AppTelemetry facade. */
    fun commonAttrs(): Map<String, String> = mapOf(
        "user.id" to userId,
        "app.persona" to persona,
        "app.fault_profile" to faults.name.lowercase(),
        "app.virtual" to "false",
        "telemetry.mode" to BuildConfig.TELEMETRY_MODE,
    )

    /**
     * Identity attributes both OTel-based flavors attach to every span and log record (the equivalent of
     * Firebase setUserId / user properties). Read per signal, so the launch arguments applied after the
     * agent started are still picked up. `telemetry.flavor` ("otel" | "edot") is also a resource attribute.
     */
    fun analyticsAttrs(): Map<String, String> = mapOf(
        "user.id" to userId,
        "app.persona" to persona,
        "app.fault_profile" to faults.name.lowercase(),
    )
}
