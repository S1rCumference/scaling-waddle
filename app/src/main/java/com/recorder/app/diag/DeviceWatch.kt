package com.recorder.app.diag

import android.content.Context
import android.os.Build
import android.os.PowerManager
import com.recorder.app.correction.batteryPercent
import com.recorder.app.correction.isCharging
import com.recorder.core.storage.Clocks
import com.recorder.core.storage.Diagnostics

/**
 * Charging, heat and battery saver, and when each of them changed.
 *
 * Every one of the three decides whether the AI is allowed to run (see CorrectionGate), so
 * "why did nothing get corrected last night" is usually answered by one of these having
 * moved. The recorder's heartbeat calls [noteChanges] once a minute, which is cheap — three
 * system reads — and turns a state nobody can see afterwards into a short list of
 * transitions with times on them.
 *
 * Only transitions are kept. A line a minute saying "still charging" is what made the first
 * version of this useless.
 */
object DeviceWatch {

    private const val TAG = "DeviceWatch"
    private const val KEEP = 30

    data class Snapshot(
        val batteryPercent: Int,
        val charging: Boolean,
        /** "none", "light", "moderate", "severe", "critical", … or "unknown" below API 29. */
        val thermal: String,
        val powerSave: Boolean,
    ) {
        /** One line for the report's header. */
        fun describe(): String = buildString {
            append(if (batteryPercent >= 0) "battery $batteryPercent%" else "battery unknown")
            append(if (charging) ", charging" else ", on battery")
            append(", thermal $thermal")
            if (powerSave) append(", battery saver ON") else append(", battery saver off")
        }
    }

    data class Event(val atTs: Long, val what: String) {
        fun render(): String = "${Clocks.shortTime(atTs)}  $what"
    }

    private val lock = Any()

    /** Newest first. */
    private var events: List<Event> = emptyList()
    private var last: Snapshot? = null

    fun read(context: Context): Snapshot {
        val power = context.getSystemService(PowerManager::class.java)
        return Snapshot(
            batteryPercent = context.batteryPercent(),
            charging = context.isCharging(),
            thermal = thermalName(power),
            powerSave = power?.isPowerSaveMode ?: false,
        )
    }

    /**
     * Records anything that changed since the last call. Battery percentage on its own is
     * not a change worth a line — it moves every few minutes by design — so it is only
     * reported as context alongside a real transition.
     */
    fun noteChanges(context: Context) {
        val now = read(context)
        val before = synchronized(lock) { last.also { last = now } }
        if (before == null) {
            add("started: ${now.describe()}")
            return
        }
        if (now.charging != before.charging) {
            add(if (now.charging) "charging started at ${now.batteryPercent}%" else "unplugged at ${now.batteryPercent}%")
        }
        if (now.thermal != before.thermal) {
            add("thermal ${before.thermal} -> ${now.thermal}")
        }
        if (now.powerSave != before.powerSave) {
            add(if (now.powerSave) "battery saver on" else "battery saver off")
        }
    }

    /** Newest first. */
    fun recent(): List<Event> = synchronized(lock) { events }

    private fun add(what: String) {
        synchronized(lock) { events = (listOf(Event(System.currentTimeMillis(), what)) + events).take(KEEP) }
        // Also to Diagnostics: these are exactly the entries that explain a quiet night, and
        // Diagnostics is what gets shared when something looks wrong.
        Diagnostics.i(TAG, what)
    }

    private fun thermalName(power: PowerManager?): String {
        if (power == null) return "unknown"
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return "unknown"
        val status = runCatching { power.currentThermalStatus }.getOrNull() ?: return "unknown"
        return when (status) {
            PowerManager.THERMAL_STATUS_NONE -> "none"
            PowerManager.THERMAL_STATUS_LIGHT -> "light"
            PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
            PowerManager.THERMAL_STATUS_SEVERE -> "severe"
            PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"
            else -> "status $status"
        }
    }
}
