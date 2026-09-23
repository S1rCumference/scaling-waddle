package com.recorder.app.admin

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.util.Log

/**
 * Device owner mode, which this app uses for exactly one thing that nothing else can do:
 * start the microphone foreground service after a reboot without a human tapping anything.
 *
 * Android grants that because a "device policy controller running in device owner mode" is
 * one of the few documented exemptions from while-in-use permission restrictions. Without
 * it, a `microphone` service started from `BOOT_COMPLETED` throws SecurityException.
 *
 * Off by default, and deliberately easy to leave: [clear] hands the device back with no
 * factory reset. Device owner can only be *set* on a phone with no accounts added.
 */
class DeviceOwner(private val context: Context) {

    private val dpm: DevicePolicyManager? =
        context.getSystemService(DevicePolicyManager::class.java)

    private val admin = ComponentName(context, RecorderDeviceAdminReceiver::class.java)

    val isActive: Boolean
        get() = dpm?.isDeviceOwnerApp(context.packageName) == true

    /**
     * Gives up device owner. Reversible by design: this is the button that stops the app
     * being privileged, without wiping the phone.
     */
    fun clear(): Result<Unit> = runCatching {
        val manager = dpm ?: error("No device policy manager")
        check(isActive) { "Not device owner" }
        manager.clearDeviceOwnerApp(context.packageName)
    }.onFailure { Log.w(TAG, "clearDeviceOwnerApp failed", it) }

    /**
     * Stops other apps running in the background without uninstalling them. Suspension is
     * preferred over hiding: a suspended app is still visible to the user and tells them
     * why it will not open, whereas a hidden one simply vanishes.
     *
     * Returns the packages that could not be suspended.
     */
    fun suspend(packages: List<String>, suspended: Boolean): List<String> {
        val manager = dpm ?: return packages
        if (!isActive) return packages
        return runCatching {
            manager.setPackagesSuspended(admin, packages.toTypedArray(), suspended).toList()
        }.onFailure { Log.w(TAG, "setPackagesSuspended failed", it) }
            .getOrDefault(packages)
    }

    /** Stops the recorder being uninstalled or force-stopped by a stray tap. */
    fun protectSelf(enabled: Boolean) {
        val manager = dpm ?: return
        if (!isActive) return
        runCatching { manager.setUninstallBlocked(admin, context.packageName, enabled) }
            .onFailure { Log.w(TAG, "setUninstallBlocked failed", it) }
    }

    /** Suppresses the system update prompts that would otherwise reboot the phone unasked. */
    fun deferSystemUpdates(enabled: Boolean) {
        val manager = dpm ?: return
        if (!isActive || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        runCatching {
            manager.setSystemUpdatePolicy(
                admin,
                if (enabled) {
                    android.app.admin.SystemUpdatePolicy.createPostponeInstallPolicy()
                } else {
                    null
                },
            )
        }.onFailure { Log.w(TAG, "setSystemUpdatePolicy failed", it) }
    }

    /** One line for the Settings screen. */
    fun statusText(): String = when {
        dpm == null -> "Unavailable on this device"
        isActive -> "Active — recording resumes automatically after a reboot"
        else -> "Not active — after a reboot you tap a notification to resume"
    }

    companion object {
        private const val TAG = "DeviceOwner"

        /**
         * The command that makes this app device owner. It must run as the shell user (via
         * Shizuku or adb) on a phone with no accounts added; there is no in-app API for it.
         */
        fun setupCommand(packageName: String): String =
            "dpm set-device-owner $packageName/com.recorder.app.admin.RecorderDeviceAdminReceiver"
    }
}
