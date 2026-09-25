package com.recorder.app.correction

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager

/**
 * Whether the phone is in a state where the overnight pass may run.
 *
 * 3.0 has exactly two things that start a correction: this, once a night, and the button in a
 * group. Everything else is gone — the quarter-hour timer, the three-minute charging timer,
 * the drain-the-backlog button. Two minutes of saturated CPU on a quarter-hour cycle is a
 * thirteen percent full-load duty cycle, which flattens the battery and heats the phone until
 * the governor slows the very work that is causing the heat.
 *
 * The conditions are all four of: charging, screen off, battery above [MIN_BATTERY_PERCENT],
 * and not already hot. The first three are what "overnight" means in practice; the fourth is a
 * refusal rather than a trigger, and it stays because a phone that is already throttling will
 * take longer and get hotter doing the same work.
 */
object CorrectionGate {

    /** Below this the pass waits for another night, charger or not. */
    const val MIN_BATTERY_PERCENT = 30

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

        val battery = context.batteryPercent()
        if (battery in 0 until MIN_BATTERY_PERCENT) {
            return Verdict.Blocked("battery is at $battery%, below $MIN_BATTERY_PERCENT%")
        }
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

internal fun Context.isCharging(): Boolean = batteryIntent()?.let {
    val status = it.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
    status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
} ?: false

internal fun Context.batteryPercent(): Int = batteryIntent()?.let {
    val level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
    if (level >= 0 && scale > 0) level * 100 / scale else -1
} ?: -1

private fun Context.batteryIntent(): Intent? =
    runCatching { registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull()
