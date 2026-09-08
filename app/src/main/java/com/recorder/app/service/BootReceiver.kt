package com.recorder.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Brings recording back after a reboot or an app update. Registered for
 * LOCKED_BOOT_COMPLETED as well so the service starts before the first unlock — the phone
 * is meant to be recording whether or not anyone has touched it since it powered on.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> {
                Log.i(TAG, "restarting recorder after ${intent.action}")
                RecordingService.start(context)
            }
        }
    }

    private companion object {
        const val TAG = "BootReceiver"
    }
}
