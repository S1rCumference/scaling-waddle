package com.recorder.app.cover

import android.content.Context
import android.hardware.display.DisplayManager
import android.util.Log
import android.view.Display

/**
 * Finds the cover screen at runtime.
 *
 * Deliberately never hardcodes display id 1. On this Razr the cover display happens to be
 * id 1, but display ids are assigned by the platform and an external screen, a cast target
 * or a future model can change that. The cover display is identified by what it is: a
 * non-default, physically present display, and on a flip phone it is the smaller one.
 */
object CoverDisplays {

    private const val TAG = "CoverDisplays"

    fun find(context: Context): Display? {
        val dm = context.getSystemService(DisplayManager::class.java) ?: return null
        return dm.displays
            .filter { it.displayId != Display.DEFAULT_DISPLAY }
            .filter { it.isPresentationSafe() }
            .minByOrNull { it.area() }
    }

    fun default(context: Context): Display? =
        context.getSystemService(DisplayManager::class.java)
            ?.getDisplay(Display.DEFAULT_DISPLAY)

    /**
     * Human-readable dump of every display, for the diagnostics screen.
     *
     * This exists because the Razr's fold-state mapping is not documented and cannot be
     * verified without the phone. Reading this with the phone open and then closed is how
     * the real values get pinned down.
     */
    fun describeAll(context: Context): String {
        val dm = context.getSystemService(DisplayManager::class.java)
            ?: return "No display manager"

        return dm.displays.joinToString("\n") { display ->
            val metrics = android.util.DisplayMetrics().also {
                @Suppress("DEPRECATION")
                display.getRealMetrics(it)
            }
            buildString {
                append("id=").append(display.displayId)
                append(" name=").append(display.name)
                append(" ").append(metrics.widthPixels).append("x").append(metrics.heightPixels)
                append(" state=").append(stateName(display.state))
                append(if (display.displayId == Display.DEFAULT_DISPLAY) " [default]" else "")
            }
        }
    }

    fun stateName(state: Int): String = when (state) {
        Display.STATE_OFF -> "off"
        Display.STATE_ON -> "on"
        Display.STATE_DOZE -> "doze"
        Display.STATE_DOZE_SUSPEND -> "doze-suspend"
        Display.STATE_ON_SUSPEND -> "on-suspend"
        Display.STATE_VR -> "vr"
        else -> "unknown($state)"
    }

    // Note: there is deliberately no DeviceStateManager reading here. That class is a
    // system API, not visible to ordinary apps, so fold-state integers are simply not
    // available to us — which is the other reason display power is the signal this uses.

    private fun Display.area(): Long {
        val metrics = android.util.DisplayMetrics().also {
            @Suppress("DEPRECATION")
            getRealMetrics(it)
        }
        return metrics.widthPixels.toLong() * metrics.heightPixels
    }

    /**
     * Excludes overlays and virtual displays that would happily accept an activity and then
     * show it to nobody.
     */
    private fun Display.isPresentationSafe(): Boolean {
        val presentation = flags and Display.FLAG_PRESENTATION != 0
        val private = flags and Display.FLAG_PRIVATE != 0
        if (private && !presentation) {
            Log.d(TAG, "ignoring private display $displayId ($name)")
            return false
        }
        return true
    }
}
