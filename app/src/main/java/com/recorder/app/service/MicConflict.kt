package com.recorder.app.service

import android.content.Context
import android.content.Intent
import android.media.AudioManager

/**
 * Two versions of Recorder can be installed side by side, but only one can have the
 * microphone. Android does not fail the second one — from Android 10 it quietly feeds one of
 * them silence — so without this check a version could "record" all day and keep nothing.
 */
object MicConflict {

    /** Every package id any build of this app has shipped under. */
    private val KNOWN_IDS = listOf(
        "com.recorder.app",
        "com.recorder.app.standard",
        "com.recorder.app.debug",
        "com.recorder.app.v21",
        "com.recorder.app.v21.standard",
        "com.recorder.app.v21.debug",
    )

    /** Other installed versions of this app, as package ids. */
    fun otherVersions(context: Context): List<String> = KNOWN_IDS
        .filter { it != context.packageName }
        .filter { id ->
            runCatching { context.packageManager.getPackageInfo(id, 0) }.isSuccess
        }

    /** True when something is recording from the microphone and it is not this app. */
    fun someoneElseRecording(context: Context): Boolean {
        val configs = context.getSystemService(AudioManager::class.java)
            ?.activeRecordingConfigurations.orEmpty()
        val mine = if (RecordingService.state.value == RecordingService.RecorderState.RECORDING) 1 else 0
        return configs.size > mine
    }

    /**
     * What to tell the user before starting, or null when there is nothing to warn about.
     * Only speaks up when another version is installed *and* the microphone is busy, so a
     * phone call or a voice note does not trigger it.
     */
    fun warning(context: Context): String? {
        val others = otherVersions(context)
        if (others.isEmpty() || !someoneElseRecording(context)) return null
        return "Another version of Recorder looks like it is recording. Android gives the " +
            "microphone to one app at a time, so one of the two would record silence all day. " +
            "Open the other Recorder, switch its recording off, then come back here."
    }

    /** Opens the other installed version, so its recording can be switched off. */
    fun openOther(context: Context): Boolean {
        val target = otherVersions(context).firstOrNull() ?: return false
        val launch = context.packageManager.getLaunchIntentForPackage(target) ?: return false
        return runCatching {
            context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
    }
}
