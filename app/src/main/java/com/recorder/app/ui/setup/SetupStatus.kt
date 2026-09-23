package com.recorder.app.ui.setup

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.recorder.app.admin.DeviceOwner
import com.recorder.app.models.ModelCatalog
import com.recorder.app.models.ModelRole
import com.recorder.app.service.RecordingService
import com.recorder.core.asr.AsrEngineFactory
import com.recorder.core.llm.local.LocalModelRuntime

/**
 * One line of the Setup status screen.
 *
 * [fix] is null when the app genuinely cannot do anything about it — a Motorola settings page
 * that cannot be deep-linked, for instance. In that case [detail] has to carry the
 * instructions, because a red row with no explanation is worse than no row.
 */
data class SetupCheck(
    val label: String,
    val ok: Boolean,
    val detail: String,
    val fix: ((Context) -> Unit)? = null,
    val fixLabel: String = "Fix",
)

/**
 * The truthful answer to "is this phone actually set up?".
 *
 * Every row is a live check, not a remembered flag from the wizard: permissions get revoked,
 * models get deleted to free space, and OEM battery managers quietly undo exemptions. A
 * wizard that ran once is not evidence of anything.
 */
object SetupStatus {

    fun check(context: Context): List<SetupCheck> {
        val catalog = runCatching { ModelCatalog.load(context) }.getOrDefault(emptyList())
        val chatModels = catalog.filter {
            it.role == ModelRole.SMALL_CHAT || it.role == ModelRole.HEAVY
        }

        return listOf(
            micCheck(context),
            notificationCheck(context),
            batteryCheck(context),
            recordingCheck(),
            speechModelCheck(context),
            chatModelCheck(context, chatModels.any { it.isInstalled(context) }),
            coverScreenCheck(context),
            deviceOwnerCheck(context),
        )
    }

    private fun micCheck(context: Context) = SetupCheck(
        label = "Microphone",
        ok = context.granted(Manifest.permission.RECORD_AUDIO),
        detail = "Without this the app cannot hear anything at all.",
        fix = { it.openAppDetails() },
        fixLabel = "Open settings",
    )

    private fun notificationCheck(context: Context) = SetupCheck(
        label = "Notifications",
        ok = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.granted(Manifest.permission.POST_NOTIFICATIONS),
        detail = "Android requires a visible notification while an app uses the microphone.",
        fix = { it.openAppDetails() },
        fixLabel = "Open settings",
    )

    /**
     * Worth being precise about: this exemption does *not* let recording restart after a
     * reboot — that needs device owner or a notification tap. It stops Doze interrupting a
     * running recorder, which is a different problem.
     */
    private fun batteryCheck(context: Context): SetupCheck {
        val power = context.getSystemService(PowerManager::class.java)
        val exempt = power?.isIgnoringBatteryOptimizations(context.packageName) == true
        return SetupCheck(
            label = "Battery optimisation off",
            ok = exempt,
            detail = "Stops Android putting the recorder to sleep while it is running.",
            fix = { ctx ->
                runCatching {
                    ctx.startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                            .setData(Uri.parse("package:${ctx.packageName}"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }.onFailure {
                    ctx.startActivity(
                        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            },
            fixLabel = "Allow",
        )
    }

    private fun recordingCheck() = SetupCheck(
        label = "Recording running",
        ok = RecordingService.state.value == RecordingService.RecorderState.RECORDING,
        detail = "The recorder is listening right now.",
        fix = { RecordingService.start(it) },
        fixLabel = "Start",
    )

    private fun speechModelCheck(context: Context) = SetupCheck(
        label = "Speech recognition installed",
        ok = AsrEngineFactory.sherpaBundled && AsrEngineFactory.modelsInstalled(context),
        detail = if (!AsrEngineFactory.sherpaBundled) {
            "This build does not contain the speech engine."
        } else {
            "Without the model the app records but writes nothing down."
        },
    )

    private fun chatModelCheck(context: Context, anyInstalled: Boolean) = SetupCheck(
        label = "On-device assistant",
        ok = LocalModelRuntime.available && anyInstalled,
        detail = LocalModelRuntime.unavailableReason
            ?: if (anyInstalled) {
                "Loaded on demand and unloaded when idle."
            } else {
                "Optional. Re-run setup to download a chat model."
            },
    )

    /**
     * There is no API that reports Motorola's "allow on external display" setting, so this
     * reports what can be seen — whether a second display exists — and leaves the rest to
     * the instructions.
     */
    private fun coverScreenCheck(context: Context) = SetupCheck(
        label = "Cover screen available",
        ok = com.recorder.app.cover.CoverDisplays.find(context) != null,
        detail = "Whether Motorola allows this app on the cover display cannot be read by an " +
            "app. If closing the phone does not show the transcript, re-run setup and follow " +
            "the cover-screen step.",
    )

    private fun deviceOwnerCheck(context: Context) = SetupCheck(
        label = "Resumes after a reboot by itself",
        ok = DeviceOwner(context).isActive,
        detail = "Optional. Without device owner you tap a notification once after each " +
            "restart; everything else works the same.",
    )

    private fun Context.granted(permission: String): Boolean =
        checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun Context.openAppDetails() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.fromParts("package", packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
