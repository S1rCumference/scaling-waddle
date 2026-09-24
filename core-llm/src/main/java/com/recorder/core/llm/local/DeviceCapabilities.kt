package com.recorder.core.llm.local

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import java.io.File

enum class RamTier { LOW_8GB, MID_12GB, HIGH_16GB_PLUS }

/**
 * Everything model-size related is decided from the RAM actually in the phone, never
 * hardcoded to the device this was first written for.
 *
 * The subtlety that matters: no Android API reports the RAM printed on the box. The kernel
 * and firmware reserve a slice before Linux ever sees it, so a 12 GB phone typically reports
 * somewhere between 10.5 and 11.6 GiB. Comparing that raw number against a 11 GB threshold —
 * which this code used to do — puts some 12 GB phones in the 8 GB tier and silently takes
 * the heavy model away from them. So the measurement is snapped to the nearest size a phone
 * is actually sold as.
 */
object DeviceCapabilities {

    private const val TAG = "DeviceCapabilities"
    private const val GIB = 1024.0 * 1024.0 * 1024.0

    /** Sizes phones are actually sold as, in GB. */
    private val MARKETED_SIZES = intArrayOf(2, 3, 4, 6, 8, 12, 16, 24, 32)

    /**
     * Allows a measurement to sit slightly *above* a marketed size without being rounded up
     * to the next one, which would be a much worse error than rounding down.
     */
    private const val OVERSHOOT_TOLERANCE = 0.97

    fun ramTier(context: Context): RamTier = when (marketedRamGb(context)) {
        in 0..8 -> RamTier.LOW_8GB
        in 9..12 -> RamTier.MID_12GB
        else -> RamTier.HIGH_16GB_PLUS
    }

    /** The RAM this phone is sold as, derived from what the kernel reports. */
    fun marketedRamGb(context: Context): Int = snapToMarketedGb(measuredRamGib(context))

    /** What the kernel actually reports, for diagnostics and the Settings screen. */
    fun totalRamGb(context: Context): Double = measuredRamGib(context)

    fun availableRamMb(context: Context): Long = memoryInfo(context).availMem / (1024 * 1024)

    /** True when the system is already under memory pressure — never load a model into that. */
    fun isLowMemory(context: Context): Boolean = memoryInfo(context).lowMemory

    /**
     * True while the phone is plugged in. The HIGH tier model is only ever loaded when this
     * is true: 2.5 GB of weights plus a decode burst is not something to spend battery on.
     */
    fun isCharging(context: Context): Boolean = runCatching {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        when (intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
            BatteryManager.BATTERY_STATUS_CHARGING, BatteryManager.BATTERY_STATUS_FULL -> true
            else -> false
        }
    }.getOrDefault(false)

    /**
     * Rounds a measured size to the nearest size phones are sold as.
     *
     * Pure and internal so the real-world values can be unit tested: this is the function
     * that decides whether a phone gets a local model at all.
     */
    internal fun snapToMarketedGb(measuredGib: Double): Int {
        if (measuredGib <= 0) return 0
        return MARKETED_SIZES.firstOrNull { it >= measuredGib * OVERSHOOT_TOLERANCE }
            ?: MARKETED_SIZES.last()
    }

    /**
     * Prefers /proc/meminfo's MemTotal over ActivityManager, which reports the same figure
     * but has historically been capped on some devices.
     *
     * Deliberately reads MemTotal only. Motorola's "RAM Boost" is storage-backed swap and
     * appears as SwapTotal; counting it would promise a model the phone cannot actually hold
     * in memory, and paging model weights to UFS is far worse than not loading them.
     */
    private fun measuredRamGib(context: Context): Double {
        readMemTotalKb()?.let { return it * 1024.0 / GIB }
        return memoryInfo(context).totalMem / GIB
    }

    private fun readMemTotalKb(): Long? = runCatching {
        File("/proc/meminfo").useLines { lines ->
            lines.firstOrNull { it.startsWith("MemTotal:") }
                ?.filter { it.isDigit() }
                ?.takeIf { it.isNotEmpty() }
                ?.toLong()
        }
    }.onFailure { Log.d(TAG, "could not read /proc/meminfo: ${it.message}") }.getOrNull()

    private fun memoryInfo(context: Context): ActivityManager.MemoryInfo {
        val info = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
        return info
    }
}
