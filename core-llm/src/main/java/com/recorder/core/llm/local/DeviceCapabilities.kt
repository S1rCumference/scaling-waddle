package com.recorder.core.llm.local

import android.app.ActivityManager
import android.content.Context

enum class RamTier { LOW_8GB, MID_12GB, HIGH_16GB_PLUS }

/**
 * Everything model-size related is decided from the RAM actually in the phone, never
 * hardcoded to the device this was first written for. Moving to a 12 GB or 16 GB phone
 * lights up bigger models from the same APK.
 */
object DeviceCapabilities {

    fun ramTier(context: Context): RamTier {
        val totalGb = totalRamGb(context)
        return when {
            totalGb >= 15 -> RamTier.HIGH_16GB_PLUS
            totalGb >= 11 -> RamTier.MID_12GB
            else -> RamTier.LOW_8GB
        }
    }

    fun totalRamGb(context: Context): Double = memoryInfo(context).totalMem / GIB

    fun availableRamMb(context: Context): Long = memoryInfo(context).availMem / (1024 * 1024)

    /** True when the system is already under memory pressure — never load a model into that. */
    fun isLowMemory(context: Context): Boolean = memoryInfo(context).lowMemory

    private fun memoryInfo(context: Context): ActivityManager.MemoryInfo {
        val info = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
        return info
    }

    private const val GIB = 1024.0 * 1024.0 * 1024.0
}
