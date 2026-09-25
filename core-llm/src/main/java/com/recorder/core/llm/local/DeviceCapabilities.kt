package com.recorder.core.llm.local

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import java.io.File

/**
 * Whether this phone has the memory to run the one model, and nothing more than that.
 *
 * It used to sort phones into three tiers and hand each a different model. 3.0 ships one
 * model, so the only question left is whether there is enough RAM at all — a floor, not a
 * ladder. What survives from the tiering is the measurement itself, because it is subtle.
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

    /**
     * The least memory this app will try to run a language model on.
     *
     * Below this the recorder still records and still transcribes — those are the parts that
     * matter — but loading a gigabyte of weights beside the capture pipeline on a 4 GB phone
     * gets one of them killed, so correction is simply not offered.
     */
    const val MIN_RAM_GB = 6

    /** True when this phone has the memory for the correction model at all. */
    fun enoughRamForAModel(context: Context): Boolean = marketedRamGb(context) >= MIN_RAM_GB

    /** The RAM this phone is sold as, derived from what the kernel reports. */
    fun marketedRamGb(context: Context): Int = snapToMarketedGb(measuredRamGib(context))

    /** What the kernel actually reports, for diagnostics and the Settings screen. */
    fun totalRamGb(context: Context): Double = measuredRamGib(context)

    fun availableRamMb(context: Context): Long = memoryInfo(context).availMem / (1024 * 1024)

    /** True when the system is already under memory pressure — never load a model into that. */
    fun isLowMemory(context: Context): Boolean = memoryInfo(context).lowMemory

    /** True while the phone is plugged in, which the overnight correction pass requires. */
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
