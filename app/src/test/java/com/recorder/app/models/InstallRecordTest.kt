package com.recorder.app.models

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * "The file is there" against "the install finished". The difference between those two is
 * what left a truncated speech model in the directory the recogniser reads.
 */
class InstallRecordTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val id = "parakeet-tdt-0.6b-v2-int8"

    private fun write(name: String, bytes: Int): File =
        File(temp.root, name).apply { writeBytes(ByteArray(bytes)) }

    @Test
    fun `a recorded install with all its files at the recorded sizes is complete`() {
        val files = listOf(write("encoder.onnx", 1_000), write("tokens.txt", 20))
        InstallRecord.write(temp.root, id, "asr-models", files)

        assertNull(InstallRecord.problem(temp.root, id))
        assertEquals("asr-models", InstallRecord.read(temp.root, id)!!.revision)
    }

    @Test
    fun `files with no record are not an install`() {
        write("encoder.onnx", 1_000)
        write("tokens.txt", 20)

        assertTrue("no record" in InstallRecord.problem(temp.root, id)!!)
    }

    @Test
    fun `a file that shrank since it was installed is caught`() {
        val encoder = write("encoder.onnx", 1_000)
        InstallRecord.write(temp.root, id, "asr-models", listOf(encoder))
        encoder.writeBytes(ByteArray(400))

        val problem = InstallRecord.problem(temp.root, id)!!
        assertTrue(problem, "400 bytes" in problem)
    }

    @Test
    fun `a file deleted since it was installed is named`() {
        val encoder = write("encoder.onnx", 1_000)
        InstallRecord.write(temp.root, id, "asr-models", listOf(encoder))
        encoder.delete()

        assertTrue("encoder.onnx is missing" in InstallRecord.problem(temp.root, id)!!)
    }

    @Test
    fun `an unreadable record is treated as no install, not as a pass`() {
        write("encoder.onnx", 1_000)
        InstallRecord.file(temp.root, id).writeText("{ this is not json")

        assertTrue("unreadable" in InstallRecord.problem(temp.root, id)!!)
    }

    @Test
    fun `deleting the record uninstalls it as far as every check is concerned`() {
        val files = listOf(write("encoder.onnx", 1_000))
        InstallRecord.write(temp.root, id, "asr-models", files)
        InstallRecord.delete(temp.root, id)

        assertTrue("no record" in InstallRecord.problem(temp.root, id)!!)
    }
}
