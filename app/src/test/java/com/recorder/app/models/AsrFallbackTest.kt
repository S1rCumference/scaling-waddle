package com.recorder.app.models

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The fallback that keeps an updated phone transcribing while the new speech model downloads.
 *
 * 4.1 replaces Parakeet v2 with v3. Without the fallback, every phone that updated would stop
 * writing anything down until someone pressed Download and waited for 465 MB. These run on real
 * files and real install records, because the fallback is only as good as the integrity check it
 * leans on — and a fallback that accepted half-overwritten files would load a mismatched model.
 */
class AsrFallbackTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val v3 = "parakeet-tdt-0.6b-v3-int8"
    private val v2 = "parakeet-tdt-0.6b-v2-int8"
    private val names = listOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt")

    private fun install(dir: File, id: String, size: Int) {
        val files = names.map { File(dir, it).apply { writeBytes(ByteArray(size)) } }
        InstallRecord.write(dir, id, "asr-models", files)
    }

    @Test
    fun `a complete current model is the one used`() {
        val dir = temp.newFolder()
        install(dir, v3, 30)
        assertEquals(v3, ModelHealth.verifiedAsrId(dir, v3, listOf(v2)))
    }

    @Test
    fun `an updated phone keeps using v2 until v3 has downloaded`() {
        val dir = temp.newFolder()
        install(dir, v2, 20)
        assertEquals(v2, ModelHealth.verifiedAsrId(dir, v3, listOf(v2)))
    }

    @Test
    fun `once v3 has moved over v2's files, v2 no longer verifies`() {
        // The staged install overwrites the same four file names. v2's record still exists, but
        // its sizes no longer match, so it must not be offered — loading v2's tokens against v3's
        // weights, or the reverse, would produce confident garbage.
        val dir = temp.newFolder()
        install(dir, v2, 20)
        names.forEach { File(dir, it).writeBytes(ByteArray(30)) }
        assertNull(ModelHealth.verifiedAsrId(dir, v3, listOf(v2)))
    }

    @Test
    fun `half way through the move, neither verifies`() {
        val dir = temp.newFolder()
        install(dir, v2, 20)
        File(dir, "encoder.int8.onnx").writeBytes(ByteArray(30))
        assertNull(ModelHealth.verifiedAsrId(dir, v3, listOf(v2)))
    }

    @Test
    fun `when both records verify, the current one wins`() {
        // Cannot happen with same-named files, but the order must not depend on that.
        val dir = temp.newFolder()
        install(dir, v2, 20)
        install(dir, v3, 20)
        assertEquals(v3, ModelHealth.verifiedAsrId(dir, v3, listOf(v2)))
    }

    @Test
    fun `nothing installed is nothing to use`() {
        assertNull(ModelHealth.verifiedAsrId(temp.newFolder(), v3, listOf(v2)))
    }

    @Test
    fun `v2 is the legacy id the fallback knows about`() {
        // Pinned, because this string has to match the id v2 was installed under on real phones.
        assertEquals(listOf(v2), ModelHealth.LEGACY_ASR_IDS)
    }
}
