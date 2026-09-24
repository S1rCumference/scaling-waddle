package com.recorder.app.service

import android.content.Context
import android.content.Intent

/**
 * Two versions of Recorder can be installed side by side, but only one can hold the
 * microphone — Android gives it to one app at a time and quietly feeds the other silence
 * (see [com.recorder.core.audio.AudioCapture]'s own silencing detector, which is what
 * actually tells this app that has happened to *its own* recording).
 *
 * This object used to also guess, before starting, whether the sibling app was already
 * recording — by comparing the *count* of [android.media.AudioManager.getActiveRecordingConfigurations]
 * against this app's own state. That guess was wrong far more often than it was right: Android
 * anonymizes every entry in that list for an app without the privileged `MODIFY_AUDIO_ROUTING`
 * permission (uid and package name are both stripped — confirmed by reading
 * `AudioRecordingConfiguration.anonymizedCopy()` in AOSP), so this app cannot tell "the
 * sibling Recorder has the mic" apart from "a hotword detector, a call, or literally any other
 * app has the mic." On a phone with an assistant hotword enabled — the out-of-box default on
 * most Android phones — that made the guess misfire on ordinary, unrelated microphone use,
 * blocking recording behind a dialog that had nothing to do with the sibling app at all.
 *
 * So this only does what can actually be verified: which other package ids of this app are
 * installed. Whether one of them is *recording* is answered honestly, after the fact, by the
 * silencing banner — which reacts to this app's own capture actually going silent, not to a
 * guess about what caused it.
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

    /** Opens the other installed version, so its recording can be switched off. */
    fun openOther(context: Context): Boolean {
        val target = otherVersions(context).firstOrNull() ?: return false
        val launch = context.packageManager.getLaunchIntentForPackage(target) ?: return false
        return runCatching {
            context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
    }
}
