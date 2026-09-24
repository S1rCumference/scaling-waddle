package com.recorder.app.models

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * Written only once a model is completely installed, and checked before anything loads it.
 *
 * "The file is there" is not the same claim as "the install finished", and the difference is
 * what broke the app. A speech model arrives as a 460 MB archive that is unpacked into the
 * directory the recogniser reads, so an install interrupted part-way — the process killed,
 * setup continued past it, the phone rebooted — left a real, readable, truncated .onnx file
 * sitting exactly where a finished one belongs. Every check in the app said the model was
 * installed. The recogniser then handed it to onnxruntime, which aborts the process from
 * native code on a malformed graph: not an exception anything in Kotlin can catch, so the
 * app force-closed on every launch, including before the diagnostics screen could be opened.
 *
 * So the end of an install is now an explicit fact on disk: this record, listing every file
 * that was written and the exact size it was written at. A directory with no record, or with
 * a file missing or the wrong length, is an unfinished install, whatever it looks like.
 *
 * Deliberately a dotfile in the model's own directory: it is removed by the same uninstall
 * that removes the model, and it cannot be separated from what it describes.
 */
object InstallRecord {

    private const val VERSION = 1

    data class Installed(val revision: String, val completedTs: Long, val files: List<Item>)

    data class Item(val name: String, val size: Long)

    fun file(dir: File, id: String): File = File(dir, ".$id.install")

    /**
     * Records [files] as the complete contents of this install. Sizes are read here, after
     * the files are in their final place, so the record describes what is actually on disk
     * rather than what was intended.
     */
    fun write(dir: File, id: String, revision: String, files: List<File>) {
        val items = JSONArray()
        files.forEach { f ->
            items.put(JSONObject().put("name", f.name).put("size", f.length()))
        }
        val json = JSONObject()
            .put("version", VERSION)
            .put("id", id)
            .put("revision", revision)
            .put("completedTs", System.currentTimeMillis())
            .put("files", items)
        file(dir, id).writeText(json.toString())
    }

    fun read(dir: File, id: String): Installed? {
        val f = file(dir, id)
        if (!f.isFile) return null
        return runCatching {
            val json = JSONObject(f.readText())
            val array = json.getJSONArray("files")
            Installed(
                revision = json.optString("revision"),
                completedTs = json.optLong("completedTs"),
                files = (0 until array.length()).map { i ->
                    val item = array.getJSONObject(i)
                    Item(item.getString("name"), item.getLong("size"))
                },
            )
        }.getOrNull()
    }

    /**
     * Null when every recorded file is present at its recorded size, otherwise the first
     * thing found wrong, phrased for someone reading it on their phone.
     */
    fun problem(dir: File, id: String): String? {
        val record = read(dir, id)
            ?: return if (file(dir, id).isFile) {
                "the install record is unreadable"
            } else {
                "no record of a finished install"
            }
        if (record.files.isEmpty()) return "the install record lists no files"
        record.files.forEach { item ->
            val f = File(dir, item.name)
            if (!f.isFile) return "${item.name} is missing"
            if (f.length() != item.size) {
                return "${item.name} is ${f.length()} bytes, not the ${item.size} it was installed at"
            }
        }
        return null
    }

    fun delete(dir: File, id: String) {
        file(dir, id).delete()
    }
}
