package com.recorder.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The net under a crash on start-up. It has to catch a genuine loop and, just as importantly,
 * not mistake an ordinary process being killed by the system for one — a phone that stopped
 * recording because it decided it had crashed would be a worse bug than the one this fixes.
 */
class StartupGuardTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var marks: File

    private fun marksFile(): File {
        if (!::marks.isInitialized) marks = File(temp.root, "startup-marks")
        return marks
    }

    @Test
    fun `two starts that never prove themselves put the third in safe mode`() {
        StartupGuard.begin(marksFile())
        assertFalse(StartupGuard.safeMode)
        StartupGuard.begin(marksFile())
        assertFalse("one kill by the system is not a crash loop", StartupGuard.safeMode)

        StartupGuard.begin(marksFile())
        assertTrue(StartupGuard.safeMode)
    }

    @Test
    fun `a start that proves itself clears the count`() {
        StartupGuard.begin(marksFile())
        StartupGuard.begin(marksFile())
        StartupGuard.markHealthy(marksFile())
        assertEquals(0, StartupGuard.unhealthyStarts)

        // Normal use, however many times: never safe mode.
        repeat(5) {
            StartupGuard.begin(marksFile())
            assertFalse(StartupGuard.safeMode)
            StartupGuard.markHealthy(marksFile())
        }
    }

    @Test
    fun `an unreadable or absent mark file counts as a clean first start`() {
        marksFile().writeText("not a number")

        StartupGuard.begin(marksFile())
        assertFalse(StartupGuard.safeMode)
        assertEquals(1, StartupGuard.unhealthyStarts)
    }

    @Test
    fun `the count survives the process, which is the whole point`() {
        StartupGuard.begin(marksFile())
        assertEquals("1", marksFile().readText().trim())
        StartupGuard.begin(marksFile())
        assertEquals("2", marksFile().readText().trim())
    }
}
