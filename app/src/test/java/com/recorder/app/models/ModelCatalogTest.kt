package com.recorder.app.models

import com.recorder.core.llm.local.OnDeviceModel
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCatalogTest {

    /** The manifest that actually ships in the APK, not a fixture. */
    private val shipped: List<ModelEntry> by lazy {
        ModelCatalog.parse(File("src/main/assets/models.json").readText())
    }

    @Test
    fun `shipped manifest parses`() {
        assertTrue("manifest should not be empty", shipped.isNotEmpty())
    }

    @Test
    fun `speech recognition and vad are both required`() {
        val required = shipped.filter { it.required }.map { it.role }
        assertTrue("VAD must be required", ModelRole.VAD in required)
        assertTrue("ASR must be required", ModelRole.ASR in required)
    }

    @Test
    fun `every shipped entry has a plausible size and an https url`() {
        shipped.forEach { entry ->
            assertTrue("${entry.id} size must be positive", entry.sizeBytes > 0)
            assertTrue(
                "${entry.id} must download over https, was ${entry.url}",
                entry.url.startsWith("https://"),
            )
        }
    }

    /**
     * A digest is either absent or a real SHA-256. A placeholder of the wrong length would
     * make every install fail verification on the phone, where it is expensive to diagnose.
     */
    @Test
    fun `digests are well formed and claimed-verified entries have one`() {
        shipped.forEach { entry ->
            entry.sha256?.let { hash ->
                assertEquals("${entry.id} digest length", 64, hash.length)
                assertTrue(
                    "${entry.id} digest must be lowercase hex",
                    hash.all { it in '0'..'9' || it in 'a'..'f' },
                )
            }
            if (entry.hashVerified) {
                assertNotNull("${entry.id} claims a verified hash but has none", entry.sha256)
            }
        }
    }

    @Test
    fun `archive entries declare a supported format`() {
        shipped.mapNotNull { it.archive }.forEach { format ->
            assertEquals("tar.bz2", format)
        }
    }

    /**
     * The quiet failure this guards against: a manifest filename the app does not look for.
     * The download succeeds, the file lands on disk, and the model stays unavailable forever
     * with no error anywhere.
     */
    @Test
    fun `the one language model is the one the app loads`() {
        val chat = shipped.filter { it.role == ModelRole.SMALL_CHAT }.map { it.fileName }
        assertEquals(listOf(OnDeviceModel.FILE_NAME), chat)
    }

    /** Three files and no choices: 3.0 has nothing to pick between. */
    @Test
    fun `the manifest is exactly voice detection, speech recognition and correction`() {
        assertEquals(3, shipped.size)
        assertEquals(
            listOf(ModelRole.VAD, ModelRole.ASR, ModelRole.SMALL_CHAT).sortedBy { it.name },
            shipped.map { it.role }.sortedBy { it.name },
        )
    }

    @Test
    fun `everything in the manifest is required, because none of it is optional now`() {
        shipped.forEach { assertTrue("${it.id} should be required", it.required) }
        assertEquals(shipped, ModelCatalog.recommended(shipped))
    }

    /** The figure the wizard shows, so a regression in it is visible here first. */
    @Test
    fun `the whole download is the sum of the three files and stays under two gigabytes`() {
        val total = ModelCatalog.everythingBytes(shipped)
        assertEquals(shipped.sumOf { it.sizeBytes }, total)
        assertTrue("the whole set would download $total bytes", total < 2L * 1024 * 1024 * 1024)
    }

    @Test
    fun `entries with an unknown role are skipped rather than crashing`() {
        val json = """
            {"version":1,"models":[
              {"id":"weird","role":"telepathy","displayName":"x","fileName":"x.bin",
               "url":"https://example.com/x.bin","sizeBytes":1},
              {"id":"ok","role":"vad","displayName":"y","fileName":"y.onnx",
               "url":"https://example.com/y.onnx","sizeBytes":2}
            ]}
        """.trimIndent()

        val parsed = ModelCatalog.parse(json)
        assertEquals(1, parsed.size)
        assertEquals("ok", parsed.first().id)
    }

    @Test
    fun `a missing digest parses as null rather than an empty string`() {
        assertTrue(shipped.any { it.sha256 == null })
        assertFalse(shipped.any { it.sha256?.isBlank() == true })
    }

    /**
     * The bug this pins down cost the phone every model but one.
     *
     * Android's org.json coerces a JSON null to the string "null", not to "", so
     * `"archive": null` arrived as `archive == "null"`, the VAD was handed to the archive
     * unpacker and failed with "Unsupported archive type: null", and `"sha256": null`
     * arrived as a digest of "null", so every GGUF failed verification and was deleted.
     *
     * A JVM test cannot reproduce that coercion — the reference org.json used here returns
     * "" — so these assert the shape that makes the coercion harmless: the literal string
     * "null" is rejected wherever a value is optional, and archive and digest values are
     * validated rather than merely checked for being non-blank.
     */
    @Test
    fun `a literal null string is treated as absent, whatever the json implementation does`() {
        val json = """
            {"version":1,"models":[
              {"id":"m","role":"vad","displayName":"m","fileName":"m.onnx",
               "url":"https://example.com/m.onnx","sizeBytes":1,
               "archive":"null","sha256":"null","licenseUrl":"null","notes":"null"}
            ]}
        """.trimIndent()
        val entry = ModelCatalog.parse(json).single()
        assertEquals(null, entry.archive)
        assertEquals(null, entry.sha256)
        assertEquals(null, entry.licenseUrl)
        assertEquals(null, entry.notes)
    }

    @Test
    fun `json null and a missing key both parse as absent`() {
        fun entry(body: String) = ModelCatalog.parse(
            """{"version":1,"models":[{"id":"m","role":"vad","displayName":"m",
               "fileName":"m.onnx","url":"https://e.com/m","sizeBytes":1$body}]}"""
        ).single()

        listOf(entry(""), entry(""","archive":null,"sha256":null""")).forEach {
            assertEquals(null, it.archive)
            assertEquals(null, it.sha256)
        }
    }

    /** An archive type the installer cannot unpack must not be treated as an archive. */
    @Test
    fun `only supported archive formats are accepted`() {
        val json = """
            {"version":1,"models":[
              {"id":"a","role":"asr","displayName":"a","fileName":"a.zip",
               "url":"https://example.com/a.zip","sizeBytes":1,"archive":"zip"},
              {"id":"b","role":"asr","displayName":"b","fileName":"b.tar.bz2",
               "url":"https://example.com/b.tar.bz2","sizeBytes":1,"archive":"tar.bz2"}
            ]}
        """.trimIndent()
        val parsed = ModelCatalog.parse(json)
        assertEquals(null, parsed.first { it.id == "a" }.archive)
        assertEquals("tar.bz2", parsed.first { it.id == "b" }.archive)
    }

    /** A digest that is not a SHA-256 is no digest: size-checked only beats failing forever. */
    @Test
    fun `a malformed digest is dropped rather than used`() {
        val json = """
            {"version":1,"models":[
              {"id":"m","role":"vad","displayName":"m","fileName":"m.onnx",
               "url":"https://example.com/m.onnx","sizeBytes":1,"sha256":"not-a-digest"}
            ]}
        """.trimIndent()
        assertEquals(null, ModelCatalog.parse(json).single().sha256)
    }

    /** The shipped manifest must survive the same treatment: the VAD is not an archive. */
    @Test
    fun `the shipped vad is a plain file and parakeet is the only archive`() {
        assertEquals(null, shipped.first { it.role == ModelRole.VAD }.archive)
        assertEquals(
            listOf("tar.bz2"),
            shipped.mapNotNull { it.archive },
        )
    }
}
