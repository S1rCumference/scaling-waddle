package com.recorder.app.admin

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.telecom.TelecomManager
import android.util.Log

/**
 * Quiets the rest of the phone so the recorder is the only thing using it.
 *
 * Built on device-owner policy APIs rather than shell commands. `provision.sh` did this over
 * adb with `pm disable-user` and standby buckets; doing the same from the phone needs either
 * device owner or Shizuku. Device owner offers typed APIs — [DevicePolicyManager.setPackagesSuspended]
 * and friends — that report which packages they could not touch, whereas driving `pm` through
 * a shell would be guesswork with no error reporting. Suspension is also gentler than
 * disabling: a suspended app stays visible and tells the user why it will not open.
 *
 * Everything is recorded and reversible. [undo] restores exactly the packages that were
 * changed, which is why the list is persisted rather than recomputed — the set of installed
 * apps changes, and un-suspending something this app never suspended would be overreach.
 */
class Lockdown(private val context: Context) {

    private val owner = DeviceOwner(context)
    private val pm = context.packageManager

    val available: Boolean get() = owner.isActive

    /** Why lockdown cannot run, or null when it can. */
    val unavailableReason: String?
        get() = if (owner.isActive) {
            null
        } else {
            "Needs device owner. Without it, use scripts/provision.sh from a computer, or " +
                "leave the phone as it is — recording works either way."
        }

    /**
     * Packages this would suspend, discovered at runtime.
     *
     * Deliberately not a hardcoded list of Motorola package names: those change between
     * models and Android versions, and a stale guess would either miss the real dialer or
     * name something that does not exist. The dialer and SMS app are asked for by role, and
     * everything else is found by querying what is actually installed.
     */
    fun candidates(): LockdownPlan {
        val keep = buildSet {
            add(context.packageName)
            // Never suspend the launcher or Settings: that is how a phone becomes a brick.
            resolveDefault(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))?.let(::add)
            addAll(ALWAYS_KEEP)
        }

        val telephony = buildSet {
            (context.getSystemService(TelecomManager::class.java))?.defaultDialerPackage?.let(::add)
            resolveDefault(Intent(Intent.ACTION_DIAL))?.let(::add)
            resolveDefault(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")))?.let(::add)
        }.filterNot { it in keep }

        val store = STORE_PACKAGES.filter { it.isInstalled() && it !in keep }

        val otherApps = runCatching {
            pm.getInstalledApplications(0)
                .filter { it.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM == 0 }
                .map { it.packageName }
                .filterNot { it in keep || it in telephony || it in store }
        }.getOrDefault(emptyList())

        return LockdownPlan(
            telephony = telephony.sorted(),
            store = store.sorted(),
            otherApps = otherApps.sorted(),
        )
    }

    /**
     * Applies the plan. Returns the packages that were actually suspended, which is what
     * [undo] later restores — the system refuses some, and pretending otherwise would leave
     * the undo button lying.
     */
    fun apply(plan: LockdownPlan): List<String> {
        if (!owner.isActive) return emptyList()

        val wanted = plan.all()
        val refused = owner.suspend(wanted, suspended = true).toSet()
        val applied = wanted.filterNot { it in refused }

        owner.protectSelf(true)
        owner.deferSystemUpdates(true)

        if (refused.isNotEmpty()) {
            Log.i(TAG, "system refused to suspend: ${refused.joinToString()}")
        }
        return applied
    }

    /** Restores the packages named, and stops protecting this app from being uninstalled. */
    fun undo(packages: Collection<String>): List<String> {
        if (!owner.isActive) return emptyList()
        val refused = owner.suspend(packages.toList(), suspended = false).toSet()
        owner.protectSelf(false)
        owner.deferSystemUpdates(false)
        return packages.filterNot { it in refused }
    }

    private fun resolveDefault(intent: Intent): String? = runCatching {
        pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo
            ?.packageName
            ?.takeUnless { it == "android" }
    }.getOrNull()

    private fun String.isInstalled(): Boolean = runCatching {
        pm.getPackageInfo(this, 0)
        true
    }.getOrDefault(false)

    private companion object {
        const val TAG = "Lockdown"

        /**
         * Suspending any of these would make the phone unusable or unrecoverable, including
         * the tool used to grant privileges in the first place.
         */
        val ALWAYS_KEEP = setOf(
            "android",
            "com.android.settings",
            "com.android.systemui",
            "com.android.shell",
            "moe.shizuku.privileged.api",
            "rikka.shizuku",
        )

        val STORE_PACKAGES = listOf("com.android.vending")
    }
}

data class LockdownPlan(
    val telephony: List<String>,
    val store: List<String>,
    val otherApps: List<String>,
) {
    fun all(): List<String> = telephony + store + otherApps

    val isEmpty: Boolean get() = all().isEmpty()

    fun summary(): String = buildString {
        append("Telephony: ").append(telephony.ifEmpty { listOf("none found") }.joinToString()).append('\n')
        append("Store: ").append(store.ifEmpty { listOf("none found") }.joinToString()).append('\n')
        append("Other apps: ").append(otherApps.size).append(" installed")
    }
}
