package com.recorder.app.cover

/**
 * Motorola's hidden `climanager` shell service, which decides what may appear on the cover
 * ("CLI") display.
 *
 * None of this is documented by Motorola. The command names come from community
 * reverse-engineering of the Razr line and are **unverified on the Razr+ 2025** — there is
 * no way to check them without the phone. They are kept here as data rather than being
 * executed blindly, so the wizard can show them, a Shizuku session can try them, and a
 * failure can be reported honestly instead of silently doing nothing.
 *
 * Reports also say the allow flags set this way are cleared by a reboot, which is why the
 * persistent route remains Motorola's own Settings page. That page is the primary
 * mechanism; this is the shortcut.
 */
object CliManager {

    /** Allows the whole app on the cover display. */
    fun allowPackage(packageName: String): String =
        "cmd climanager set-pkg-allowed-oncli $packageName true"

    /** Allows one specific activity, which is the narrower and safer grant. */
    fun allowComponent(packageName: String, activity: String): String =
        "cmd climanager set-cn-allowed-oncli $packageName/$activity true"

    /** Every command the cover-screen step would run, in order. */
    fun setupCommands(packageName: String): List<String> = listOf(
        allowPackage(packageName),
        allowComponent(packageName, "com.recorder.app.ui.CoverActivity"),
    )

    /** What the user should do instead, and which survives a reboot. */
    const val PERSISTENT_PATH =
        "Settings → Display → External display → App settings → Recorder → " +
            "Allow on external display → Auto transition"
}
