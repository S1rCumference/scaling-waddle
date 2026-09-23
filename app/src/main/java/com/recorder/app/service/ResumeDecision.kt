package com.recorder.app.service

/** What to do about recording not currently running. */
enum class ResumeAction {
    /** Leave it alone — either it is running, or the user turned it off on purpose. */
    NOTHING,

    /** Start the service now. Only legal when an exemption applies. */
    START_DIRECTLY,

    /** Post the resume notification; the user's tap is itself the exemption. */
    ASK_WITH_NOTIFICATION,
}

/**
 * The rules for getting recording back, kept as pure functions so they can be tested
 * without a phone.
 *
 * The constraint being encoded: `RECORD_AUDIO` is a while-in-use permission, so a
 * `microphone` foreground service started while the app is in the background throws
 * SecurityException. Android's exemption list is short, and only two entries are reachable
 * from here — device owner mode, and a start caused by interacting with a notification.
 * Notably, battery-optimisation exemption and SYSTEM_ALERT_WINDOW do *not* help.
 */
object ResumeDecision {

    /** After BOOT_COMPLETED or MY_PACKAGE_REPLACED. */
    fun afterBoot(recordingEnabled: Boolean, isDeviceOwner: Boolean): ResumeAction = when {
        !recordingEnabled -> ResumeAction.NOTHING
        isDeviceOwner -> ResumeAction.START_DIRECTLY
        else -> ResumeAction.ASK_WITH_NOTIFICATION
    }

    /** From the periodic watchdog, which also knows whether the service is alive. */
    fun onWatchdogCheck(
        recordingEnabled: Boolean,
        serviceRunning: Boolean,
        isDeviceOwner: Boolean,
    ): ResumeAction = when {
        !recordingEnabled -> ResumeAction.NOTHING
        serviceRunning -> ResumeAction.NOTHING
        isDeviceOwner -> ResumeAction.START_DIRECTLY
        else -> ResumeAction.ASK_WITH_NOTIFICATION
    }
}
