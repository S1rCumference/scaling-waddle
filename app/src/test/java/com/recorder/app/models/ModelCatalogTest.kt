package com.recorder.app.models

import com.recorder.core.llm.local.LocalModelSelector
import com.recorder.core.llm.local.RamTier
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

    @Test
    fun `an 8GB phone still gets everything required`() {
        val picked = ModelCatalog.recommended(shipped, RamTier.LOW_8GB)
        assertTrue(
            "required models must be recommended on the lowest tier",
            picked.containsAll(shipped.filter { it.required }),
        )
    }

    @Test
    fun `a bigger phone is never offered less than a smaller one`() {
        val low = ModelCatalog.recommended(shipped, RamTier.LOW_8GB).size
        val high = ModelCatalog.recommended(shipped, RamTier.HIGH_16GB_PLUS).size
        assertTrue("16GB tier offered $high, 8GB tier offered $low", high >= low)
    }

    /**
     * The quiet failure this guards against: a manifest filename that the selector does not
     * look for. The download succeeds, the file lands on disk, and the assistant stays
     * unavailable forever with no error anywhere.
     */
    @Test
    fun `chat model filenames match what the selector looks for`() {
        val manifestSmall = shipped.filter { it.role == ModelRole.SMALL_CHAT }.map { it.fileName }
        val manifestHeavy = shipped.filter { it.role == ModelRole.HEAVY }.map { it.fileName }

        assertEquals(
            LocalModelSelector.SMALL_MODEL_FILES.sorted(),
            manifestSmall.sorted(),
        )
        assertEquals(
            LocalModelSelector.HEAVY_MODEL_FILES.sorted(),
            manifestHeavy.sorted(),
        )
    }

    @Test
    fun `an 8GB phone is offered a chat model but no heavy one`() {
        val picked = ModelCatalog.recommended(shipped, RamTier.LOW_8GB)
        assertTrue(
            "an 8GB phone should still get a small chat model",
            picked.any { it.role == ModelRole.SMALL_CHAT },
        )
        assertTrue(
            "nothing heavy fits in 8GB beside ASR and a chat model",
            picked.none { it.role == ModelRole.HEAVY },
        )
    }

    @Test
    fun `a 12GB phone gets the medium all-day model and the charging-only one`() {
        val picked = ModelCatalog.recommended(shipped, RamTier.MID_12GB)
        assertEquals(
            "qwen3-1.7b-q4.gguf",
            picked.first { it.role == ModelRole.SMALL_CHAT }.fileName,
        )
        assertEquals(
            "qwen3-4b-q4.gguf",
            picked.first { it.role == ModelRole.HEAVY }.fileName,
        )
    }

    /**
     * Nothing in this build needs more than 12 GB, so a 16 GB phone is offered exactly the
     * same models rather than something larger that would not load reliably anyway.
     */
    @Test
    fun `a 16GB phone is offered the same models as a 12GB one`() {
        assertEquals(
            ModelCatalog.recommended(shipped, RamTier.MID_12GB).map { it.id },
            ModelCatalog.recommended(shipped, RamTier.HIGH_16GB_PLUS).map { it.id },
        )
    }

    /** Three tiers: two all-day models and one charging-only model, and nothing else. */
    @Test
    fun `the manifest holds exactly the three tier models`() {
        assertEquals(
            listOf("gemma-3-1b-q4.gguf", "qwen3-1.7b-q4.gguf"),
            shipped.filter { it.role == ModelRole.SMALL_CHAT }.map { it.fileName }.sorted(),
        )
        assertEquals(
            listOf("qwen3-4b-q4.gguf"),
            shipped.filter { it.role == ModelRole.HEAVY }.map { it.fileName },
        )
    }

    /** Nothing above 12 GB: no entry may be gated on a tier bigger than MID_12GB. */
    @Test
    fun `no model requires more than a 12GB phone`() {
        shipped.forEach { entry ->
            assertTrue(
                "${entry.id} is gated on ${entry.minRamTier}, above the 12 GB ceiling",
                entry.minRamTier == RamTier.LOW_8GB || entry.minRamTier == RamTier.MID_12GB,
            )
        }
    }

    /** The download that actually matters on a 12 GB phone, so a regression is visible. */
    @Test
    fun `the 12GB download stays under four gigabytes`() {
        val bytes = ModelCatalog.recommended(shipped, RamTier.MID_12GB).sumOf { it.sizeBytes }
        assertTrue("12 GB tier would download $bytes bytes", bytes < 4L * 1024 * 1024 * 1024)
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
        val json = """
            {"version":1,"models":[
              {"id":"n","role":"small_chat","displayName":"y","fileName":"y.gguf",
               "url":"https://example.com/y.gguf","sizeBytes":2,"sha256":""}
            ]}
        """.trimIndent()

        val entry = ModelCatalog.parse(json).single()
        assertEquals(null, entry.sha256)
        assertFalse(entry.hashVerified)
    }
}
