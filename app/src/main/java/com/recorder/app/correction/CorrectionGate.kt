package com.recorder.app.correction

import android.content.Context
import android.os.Build
import android.os.PowerManager

/**
 * Whether the phone is in a state where it should be running the AI on its own.
 *
 * The old rule was a timer: every fifteen minutes, whatever else was happening. Two minutes
 * of saturated CPU on a quarter-hour cycle is a thirteen percent full-load duty cycle, which
 * flattens the battery and heats the phone until the thermal governor slows the very work
 * that is causing it — each pass making the next one longer.
 *
 * So automatic work now needs the phone to be charging and not in use, and backs off
 * entirely when it is hot or saving power. Everything else is on demand, where the cost is
 * visible and chosen.
 */
object CorrectionGate {

    sealed interface Verdict {
        data object Ready : Verdict
        data class Blocked(val reason: String) : Verdict
    }

    fun check(context: Context): Verdict {
        val power = context.getSystemService(PowerManager::class.java)
            ?: return Verdict.Blocked("no power manager")

        if (power.isPowerSaveMode) return Verdict.Blocked("battery saver is on")

        thermalReason(power)?.let { return Verdict.Blocked(it) }

        if (!context.isCharging()) return Verdict.Blocked("not charging")
        // isInteractive is the screen being on, not the phone being unlocked. That is the
        // right test: work should wait while the screen is being looked at.
        if (power.isInteractive) return Verdict.Blocked("the screen is on")

        return Verdict.Ready
    }

    /** A short sentence for Settings, whatever the state. */
    fun describe(context: Context): String = when (val verdict = check(context)) {
        is Verdict.Ready -> "Ready to run"
        is Verdict.Blocked -> "Waiting: ${verdict.reason}"
    }

    /**
     * Why the phone is too hot to be asked, or null. Anything at or above MODERATE means the
     * governor has already started slowing the CPU down, so a pass started now would take
     * longer and make the heat worse.
     */
    fun thermalReason(power: PowerManager): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val status = runCatching { power.currentThermalStatus }.getOrDefault(PowerManager.THERMAL_STATUS_NONE)
        return when {
            status >= PowerManager.THERMAL_STATUS_SEVERE -> "the phone is hot"
            status >= PowerManager.THERMAL_STATUS_MODERATE -> "the phone is warming up"
            else -> null
        }
    }

    /** True when it is too hot even for work the user asked for by hand. */
    fun tooHotForAnything(context: Context): Boolean {
        val power = context.getSystemService(PowerManager::class.java) ?: return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val status = runCatching { power.currentThermalStatus }.getOrDefault(PowerManager.THERMAL_STATUS_NONE)
        return status >= PowerManager.THERMAL_STATUS_SEVERE
    }
}
