package com.recorder.app.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.recorder.app.BuildConfig
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

data class AvailableUpdate(
    val versionName: String,
    val versionCode: Int,
    val apkUrl: String,
    val sizeBytes: Long,
    val sha256: String?,
    val notes: String,
)

/**
 * Checks GitHub Releases for a newer build and hands the APK to the system installer.
 *
 * The phone is the only computer in this story, so updating cannot depend on adb. Releases
 * are signed with the same key as the installed build, which is what allows an update to
 * install over the top instead of demanding an uninstall.
 *
 * Nothing is downloaded automatically, and never over mobile data by default: a 35 MB
 * surprise on a metered connection is not an improvement.
 */
class UpdateChecker(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * Returns the newest release that carries this build's APK, or null when this build is
     * current. Every recent release is looked at, not just "latest": the stable app and 2.1
     * publish to the same repository under different asset names, so the release GitHub
     * calls latest may not contain this app at all.
     */
    suspend fun check(): Result<AvailableUpdate?> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(RELEASES_API)
                .header("Accept", "application/vnd.github+json")
                .build()

            val releases = client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) error("GitHub returned HTTP ${response.code}")
                JSONArray(text)
            }

            var best: AvailableUpdate? = null
            for (r in 0 until releases.length()) {
                val release = releases.optJSONObject(r) ?: continue
                if (release.optBoolean("draft") || release.optBoolean("prerelease")) continue
                val tag = release.optString("tag_name").removePrefix("v")
                if (tag.isBlank()) continue
                val code = versionCodeOf(tag)
                if (code <= BuildConfig.VERSION_CODE || code <= (best?.versionCode ?: 0)) continue

                val assets = release.optJSONArray("assets") ?: continue
                for (i in 0 until assets.length()) {
                    val asset = assets.optJSONObject(i) ?: continue
                    if (asset.optString("name") == apkAssetName()) {
                        best = AvailableUpdate(
                            versionName = tag,
                            versionCode = code,
                            apkUrl = asset.optString("browser_download_url"),
                            sizeBytes = asset.optLong("size"),
                            sha256 = null,
                            notes = release.optString("body").take(500),
                        )
                    }
                }
            }
            if (best == null) Log.i(TAG, "no newer release carries ${apkAssetName()}")
            best
        }
    }

    /**
     * Downloads the APK and opens the system installer. Verification is by size, plus
     * Android's own signature check at install time — a differently signed APK is rejected
     * by the platform, which is the guarantee that matters here.
     */
    suspend fun download(update: AvailableUpdate, onProgress: (Long, Long) -> Unit): Result<File> =
        withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(context.cacheDir, "updates").apply { mkdirs() }
                // Keep one file around, not a growing pile of old APKs.
                dir.listFiles()?.forEach { it.delete() }
                val target = File(dir, "recorder-${update.versionName}.apk")

                client.newCall(Request.Builder().url(update.apkUrl).build()).execute().use { response ->
                    if (!response.isSuccessful) error("HTTP ${response.code}")
                    val body = response.body ?: error("empty body")
                    val total = if (update.sizeBytes > 0) update.sizeBytes else body.contentLength()

                    target.outputStream().use { out ->
                        var written = 0L
                        val buffer = ByteArray(1 shl 16)
                        body.byteStream().use { input ->
                            while (true) {
                                val read = input.read(buffer)
                                if (read <= 0) break
                                out.write(buffer, 0, read)
                                written += read
                                onProgress(written, total)
                            }
                        }
                    }
                }

                if (update.sizeBytes > 0 && target.length() != update.sizeBytes) {
                    target.delete()
                    error("Downloaded ${target.length()} bytes, expected ${update.sizeBytes}")
                }
                update.sha256?.let { expected ->
                    val actual = target.sha256()
                    if (!actual.equals(expected, ignoreCase = true)) {
                        target.delete()
                        error("Checksum mismatch")
                    }
                }
                target
            }
        }

    /** Hands the file to the package installer. The user still confirms the install. */
    fun install(apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /**
     * versionCode is derived from the tag the same way the release workflow derives it, so
     * the comparison is meaningful rather than a string sort.
     */
    private fun versionCodeOf(tag: String): Int {
        val parts = tag.substringBefore('-').split('.')
        val major = parts.getOrNull(0)?.toIntOrNull() ?: 0
        val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val patch = parts.getOrNull(2)?.toIntOrNull() ?: 0
        return major * 10_000 + minor * 100 + patch
    }

    /** The razr build and the standard build are separate assets and separate app ids. */
    private fun apkAssetName(): String =
        "${BuildConfig.RELEASE_ASSET_PREFIX}-${if (BuildConfig.COVER_UI_ENABLED) "razr" else "standard"}-release.apk"

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val TAG = "UpdateChecker"
        const val RELEASES_API =
            "https://api.github.com/repos/S1rCumference/scaling-waddle/releases?per_page=20"
    }
}
