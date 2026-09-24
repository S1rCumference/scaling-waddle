package com.recorder.app.models

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.StatFs
import com.recorder.core.storage.Diagnostics
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream

/**
 * Downloads models onto the phone, once, over Wi-Fi.
 *
 * Called from [ModelDownloadService] rather than from a ViewModel: several gigabytes over
 * phone Wi-Fi takes long enough that the screen will turn off and the wizard will be
 * backgrounded part-way through, and work owned by a screen dies with it.
 *
 * Partial downloads are kept as `.part` files and resumed with a Range request, because
 * 460 MB over phone Wi-Fi does fail, and starting from zero each time is how people give
 * up on setup. A `.part` that cannot be resumed is discarded rather than retried forever.
 */
class ModelInstaller(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    /** True when the active network is unmetered. The wizard refuses to download otherwise. */
    fun onUnmeteredNetwork(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    fun hasNetwork(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    fun freeBytes(): Long {
        val stat = StatFs(context.filesDir.absolutePath)
        return stat.availableBlocksLong * stat.blockSizeLong
    }

    /**
     * Space needed for [entries]: the download itself, plus headroom for an archive that
     * has to be unpacked alongside its own compressed copy.
     */
    fun requiredBytes(entries: List<ModelEntry>): Long =
        entries.sumOf { if (it.archive != null) it.sizeBytes * 3 else it.sizeBytes } + SLACK_BYTES

    /**
     * Whether a whole queue fits, checked before the first byte is fetched. The per-model
     * check alone passed happily for each of four models and then ran the phone out of space
     * on the last one, which looked like a mysterious failure rather than a full disk.
     */
    fun spaceProblem(entries: List<ModelEntry>): String? {
        val pending = entries.filterNot { it.isInstalled(context) }
        if (pending.isEmpty()) return null
        val needed = requiredBytes(pending)
        val free = freeBytes()
        if (free >= needed) return null
        return "Not enough free storage for ${pending.size} model(s): " +
            "needs about ${needed / (1024 * 1024)} MB, ${free / (1024 * 1024)} MB free."
    }

    suspend fun install(
        entry: ModelEntry,
        allowMetered: Boolean = false,
        onProgress: (InstallProgress) -> Unit,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (!hasNetwork()) {
                return@withContext fail(onProgress, "No network connection.", retryable = true)
            }
            if (!allowMetered && !onUnmeteredNetwork()) {
                return@withContext fail(
                    onProgress,
                    "Waiting for Wi-Fi. This download is ${entry.approxMb} MB.",
                    retryable = true,
                )
            }
            if (freeBytes() < requiredBytes(listOf(entry))) {
                return@withContext fail(
                    onProgress,
                    "Not enough free storage: needs about " +
                        "${requiredBytes(listOf(entry)) / (1024 * 1024)} MB.",
                    retryable = false,
                )
            }

            val dir = entry.destinationDir(context).apply { mkdirs() }
            val part = entry.partFile(context)

            download(entry, part, onProgress)

            onProgress(InstallProgress.Verifying)
            verify(entry, part)?.let { problem ->
                // A bad digest means the bytes are wrong; keeping them would make every
                // retry fail identically.
                part.delete()
                return@withContext fail(onProgress, problem, retryable = true)
            }

            if (entry.archive != null) {
                onProgress(InstallProgress.Extracting)
                extract(entry, part, dir)
                part.delete()
            } else {
                val target = File(dir, entry.fileName)
                if (!part.renameTo(target)) {
                    part.copyTo(target, overwrite = true)
                    part.delete()
                }
            }

            // The last thing checked is the thing that actually matters: that the file is
            // now where the model selector will look for it. Everything upstream can appear
            // to succeed — a rename that silently fails, an archive that unpacks the wrong
            // members — and the old code reported Done regardless, which is how a download
            // could "finish" and leave the model reported as not installed with no error.
            if (!entry.isInstalled(context)) {
                return@withContext fail(
                    onProgress,
                    "Downloaded, but the file is not where it should be " +
                        "(${entry.destinationDir(context).absolutePath}/${entry.fileName}). " +
                        "Try again; if it repeats, remove the model and re-download.",
                    retryable = true,
                )
            }

            onProgress(InstallProgress.Done)
            Result.success(Unit)
        } catch (cancelled: CancellationException) {
            // Stopping is not a failure, and swallowing this would break cancellation.
            onProgress(InstallProgress.Idle)
            throw cancelled
        } catch (io: IOException) {
            Diagnostics.w(TAG, "model download failed for ${entry.id}", io)
            fail(onProgress, io.message ?: "Download failed.", retryable = true)
        } catch (t: Throwable) {
            Diagnostics.e(TAG, "model install failed for ${entry.id}", t)
            fail(onProgress, t.message ?: "Install failed.", retryable = false)
        }
    }

    private fun download(entry: ModelEntry, part: File, onProgress: (InstallProgress) -> Unit) {
        // A .part at or beyond the expected size is not resumable: the server answers a Range
        // request past the end with 416, which used to fail every retry identically and leave
        // the model permanently stuck. Start it again instead.
        if (part.isFile && entry.sizeBytes > 0 && part.length() > entry.sizeBytes) {
            Diagnostics.w(
                TAG,
                "discarding oversized partial download for ${entry.id} " +
                    "(${part.length()} bytes, expected ${entry.sizeBytes})",
            )
            part.delete()
        }

        val already = if (part.isFile) part.length() else 0L
        if (already > 0 && already == entry.sizeBytes) {
            onProgress(InstallProgress.Downloading(already, entry.sizeBytes))
            return
        }

        val request = Request.Builder()
            .url(entry.url)
            .apply { if (already > 0) header("Range", "bytes=$already-") }
            .build()

        client.newCall(request).execute().use { response ->
            if (response.code == 416 && already > 0) {
                // Range unsatisfiable: whatever is on disk does not match the file any more.
                part.delete()
                throw IOException("Resume failed; the partial file was discarded. Try again.")
            }
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code} from ${entry.url}")
            }
            // A server that ignores Range returns 200 and the whole file; appending then
            // would corrupt the result, so only append on an explicit 206.
            val resuming = response.code == 206 && already > 0
            val body = response.body ?: throw IOException("Empty response body")
            val total = entry.sizeBytes

            // append=false truncates, which is exactly right when not resuming and exactly
            // wrong when we are, so the flag decides the stream rather than a later branch.
            java.io.FileOutputStream(part, resuming).use { out ->
                var written = if (resuming) already else 0L
                var lastReported = 0L
                val buffer = ByteArray(1 shl 16)
                body.byteStream().use { input ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                        written += read
                        // Reporting every 64 KB chunk would post ~7000 UI updates for one
                        // model; once per megabyte is enough to look alive.
                        if (written - lastReported >= PROGRESS_STEP_BYTES) {
                            lastReported = written
                            onProgress(InstallProgress.Downloading(written, total))
                        }
                    }
                }
                out.flush()
                onProgress(InstallProgress.Downloading(written, total))
            }
        }
    }

    /** Returns null when the file is acceptable, or a human-readable problem. */
    private fun verify(entry: ModelEntry, file: File): String? {
        if (entry.sizeBytes > 0 && file.length() != entry.sizeBytes) {
            return "Downloaded ${file.length()} bytes, expected ${entry.sizeBytes}."
        }
        val expected = entry.sha256 ?: return null

        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        return if (actual.equals(expected, ignoreCase = true)) {
            null
        } else {
            "Checksum did not match. Expected $expected, got $actual."
        }
    }

    /**
     * Unpacks a tar.bz2 into [dir], flattening directories and skipping the sample audio
     * the sherpa model archives bundle. Entry names are sanitised so a malicious or
     * malformed archive cannot write outside the target directory.
     */
    private fun extract(entry: ModelEntry, archive: File, dir: File) {
        require(entry.archive == "tar.bz2") { "Unsupported archive type: ${entry.archive}" }

        TarArchiveInputStream(BZip2CompressorInputStream(archive.inputStream().buffered()))
            .use { tar ->
                while (true) {
                    val item = tar.nextEntry ?: break
                    if (item.isDirectory) continue

                    val name = File(item.name).name
                    if (name.isBlank() || name.startsWith(".")) continue
                    // Only the model files matter; test_wavs and READMEs are dead weight.
                    val wanted = name.endsWith(".onnx") || name == "tokens.txt"
                    if (!wanted) continue

                    val target = File(dir, name)
                    if (!target.canonicalPath.startsWith(dir.canonicalPath + File.separator)) {
                        throw IOException("Archive entry escapes target directory: ${item.name}")
                    }
                    target.outputStream().use { out -> tar.copyTo(out) }
                }
            }

        if (dir.listFiles().isNullOrEmpty()) {
            throw IOException("Archive contained no model files")
        }
    }

    private fun fail(
        onProgress: (InstallProgress) -> Unit,
        reason: String,
        retryable: Boolean,
    ): Result<Unit> {
        onProgress(InstallProgress.Failed(reason, retryable))
        return Result.failure(IOException(reason))
    }

    /** Removes an installed model, freeing its space. */
    fun uninstall(entry: ModelEntry) {
        val dir = entry.destinationDir(context)
        if (entry.archive != null) {
            // Archive-sourced models own their whole directory.
            dir.listFiles()?.forEach { it.delete() }
        } else {
            File(dir, entry.fileName).delete()
        }
    }

    companion object {
        private const val TAG = "ModelInstaller"

        /** Never fill the last 200 MB of a phone; Android misbehaves when nearly full. */
        const val SLACK_BYTES = 200L * 1024 * 1024

        const val PROGRESS_STEP_BYTES = 1L * 1024 * 1024
    }
}
