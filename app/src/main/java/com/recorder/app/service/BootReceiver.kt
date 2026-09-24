package com.recorder.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.recorder.core.storage.Diagnostics
import com.recorder.app.R
import com.recorder.app.ServiceLocator
import com.recorder.app.admin.DeviceOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Brings recording back after a reboot or an app update.
 *
 * The obvious implementation — call startForegroundService here — does not work on Android
 * 14 and later. `RECORD_AUDIO` is a while-in-use permission, so a `microphone` foreground
 * service started from this receiver throws SecurityException: the app is in the background,
 * so the system treats it as not holding the microphone permission at all. Being exempt from
 * background-start restrictions does not help, and neither does disabling battery
 * optimisation.
 *
 * [ResumeDecision] holds the resulting rules; this class only carries them out.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> resume(context, intent.action.orEmpty())

            else -> Unit
        }
    }

    private fun resume(context: Context, action: String) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                // Reading the toggle means a phone the user deliberately switched off stays
                // off. Bounded, because a receiver that never finishes is an ANR.
                val enabled = withTimeoutOrNull(SETTINGS_TIMEOUT_MS) {
                    ServiceLocator.settings.recordingEnabled.first()
                } ?: true

                when (ResumeDecision.afterBoot(enabled, DeviceOwner(context).isActive)) {
                    ResumeAction.NOTHING ->
                        Diagnostics.i(TAG, "recording is switched off; leaving it alone after $action")

                    ResumeAction.START_DIRECTLY -> {
                        Diagnostics.i(TAG, "device owner; starting directly after $action")
                        RecordingService.start(context).onFailure { error ->
                            Diagnostics.w(TAG, "direct start refused after $action", error)
                            ResumeNotifier.show(context, context.getString(R.string.resume_reason_boot))
                        }
                    }

                    ResumeAction.ASK_WITH_NOTIFICATION -> {
                        Diagnostics.i(TAG, "asking for a tap to resume after $action")
                        ResumeNotifier.show(context, context.getString(R.string.resume_reason_boot))
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "BootReceiver"
        const val SETTINGS_TIMEOUT_MS = 5_000L
    }
}
