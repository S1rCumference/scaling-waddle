package com.recorder.app.admin

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.recorder.app.service.RecordingService

/**
 * Present so the app can be made device owner. It holds no policy of its own; policies are
 * applied through [DeviceOwner] when the user opts in.
 */
class RecorderDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        Log.i(TAG, "device admin enabled")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        Log.i(TAG, "device admin disabled; recording will need a tap after each reboot")
    }

    /**
     * Fired when the app is provisioned as device owner, including through QR-code setup on
     * a fresh phone. Recording can start immediately here: device owner mode is exempt from
     * the while-in-use restriction that otherwise blocks a microphone service.
     */
    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        Log.i(TAG, "provisioned as device owner")
        DeviceOwner(context).apply {
            protectSelf(true)
            deferSystemUpdates(true)
        }
        RecordingService.start(context)
    }

    private companion object {
        const val TAG = "RecorderDeviceAdmin"
    }
}
