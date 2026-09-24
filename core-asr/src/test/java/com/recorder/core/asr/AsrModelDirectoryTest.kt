package com.recorder.core.asr

import java.io.File
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The guard that stands between a part-written model file and onnxruntime, which aborts the
 * whole process rather than throwing when it is handed one. These are the shapes a directory
 * took on a real phone while the app was force-closing on every launch.
 */
class AsrModelDirectoryTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun file(name: String, bytes: Int): File =
        temp.newFile(name).apply { writeBytes(ByteArray(bytes)) }

    private fun completeModel() {
        file("encoder.int8.onnx", 200_000)
        file("decoder.int8.onnx", 200_000)
        file("joiner.int8.onnx", 200_000)
        temp.newFile("tokens.txt").writeText("a 1\nb 2\n")
    }

    @Test
    fun `a complete directory passes`() {
        completeModel()
        assertNull(AsrModels.asrProblem(temp.root))
    }

    @Test
    fun `a directory holding only a partial download is refused`() {
        file("sherpa-onnx-nemo-parakeet-tdt-0_6b-v2-int8.tar.bz2.part", 5_000_000)

        val problem = requireNotNull(AsrModels.asrProblem(temp.root))
        assertTrue(problem, "in progress" in problem)
    }

    @Test
    fun `a truncated model file is refused rather than loaded`() {
        file("encoder.int8.onnx", 900)
        file("decoder.int8.onnx", 200_000)
        file("joiner.int8.onnx", 200_000)
        temp.newFile("tokens.txt").writeText("a 1\n")

        val problem = requireNotNull(AsrModels.asrProblem(temp.root))
        assertTrue(problem, "incomplete" in problem)
    }

    @Test
    fun `a missing piece of the transducer is named`() {
        file("encoder.int8.onnx", 200_000)
        file("joiner.int8.onnx", 200_000)
        temp.newFile("tokens.txt").writeText("a 1\n")

        assertTrue(AsrModels.asrProblem(temp.root)!!.startsWith("decoder"))
    }

    @Test
    fun `missing tokens are named, and an empty directory is not a model`() {
        file("encoder.int8.onnx", 200_000)
        file("decoder.int8.onnx", 200_000)
        file("joiner.int8.onnx", 200_000)
        assertTrue("tokens" in AsrModels.asrProblem(temp.root)!!)

        val empty = temp.newFolder("empty")
        assertNotNull(AsrModels.asrProblem(empty))
    }
}
