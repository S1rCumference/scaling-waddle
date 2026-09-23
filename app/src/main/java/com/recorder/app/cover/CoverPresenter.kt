package com.recorder.app.cover

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import com.recorder.app.admin.DeviceOwner
import com.recorder.app.ui.CoverActivity

/**
 * Puts the recorder on the cover screen when the phone is closed.
 *
 * How "closed" is detected: not by a fold-state constant. The Razr's device-state integers
 * are OEM-specific and undocumented, so relying on them would be guessing. Instead this
 * watches display power, which is stable public API and behaviourally what matters — on a
 * flip phone, closing the hinge turns the inner display off, and waking the cover turns the
 * cover display on. When the cover display is on and the inner one is not, the phone is
 * closed and being looked at.
 *
 * This is attached to the recording service only because that is the one component alive
 * all day. The recording pipeline never reads it: nothing here can pause, restart or
 * otherwise reach the audio path, which is the rule the whole design rests on.
 *
 * Launching an activity from here is subject to background-activity-launch restrictions.
 * Device owner mode is exempt; otherwise the launch may be refused, and the fallback is
 * Motorola's own "auto transition" setting, which moves the running app across for us. Both
 * outcomes are logged rather than assumed.
 */
class CoverPresenter(private val context: Context) {

    private val handler = Handler(Looper.getMainLooper())
    private var registered = false
    private var lastLaunchedAt = 0L

    private val listener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = evaluate("display $displayId added")
        override fun onDisplayRemoved(displayId: Int) = evaluate("display $displayId removed")
        override fun onDisplayChanged(displayId: Int) = evaluate("display $displayId changed")
    }

    fun start() {
        if (registered) return
        val dm = context.getSystemService(DisplayManager::class.java) ?: return
        dm.registerDisplayListener(listener, handler)
        registered = true
        Log.i(TAG, "watching displays:\n${CoverDisplays.describeAll(context)}")
    }

    fun stop() {
        if (!registered) return
        context.getSystemService(DisplayManager::class.java)?.unregisterDisplayListener(listener)
        registered = false
    }

    private fun evaluate(reason: String) {
        val cover = CoverDisplays.find(context) ?: return
        val inner = CoverDisplays.default(context) ?: return

        val coverAwake = cover.state == Display.STATE_ON
        val innerAwake = inner.state == Display.STATE_ON
        if (!coverAwake || innerAwake) return

        // Display changes arrive in bursts; one launch per burst is enough.
        val now = System.currentTimeMillis()
        if (now - lastLaunchedAt < LAUNCH_DEBOUNCE_MS) return
        lastLaunchedAt = now

        Log.i(TAG, "cover is awake and inner is not ($reason); presenting on ${cover.displayId}")
        launchOnCover(cover.displayId)
    }

    private fun launchOnCover(displayId: Int) {
        val intent = Intent(context, CoverActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)

        val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId)

        runCatching { context.startActivity(intent, options.toBundle()) }
            .onSuccess { Log.i(TAG, "cover activity launched on display $displayId") }
            .onFailure { error ->
                // Expected when not device owner: background activity launches are blocked.
                // Motorola's auto-transition setting covers this case instead.
                val privileged = DeviceOwner(context).isActive
                Log.w(
                    TAG,
                    "could not launch on cover display (device owner=$privileged); " +
                        "relying on the system's own transition",
                    error,
                )
            }
    }

    private companion object {
        const val TAG = "CoverPresenter"
        const val LAUNCH_DEBOUNCE_MS = 1_500L
    }
}
