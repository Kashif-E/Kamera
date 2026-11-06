@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.engine.FakeAndroidPlatform
import com.kashif.cameraK.engine.open
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Up to three captures can be in flight at once, so output names must be unique by construction and
 * claimed atomically. With one-second names, two taps in the same second wrote the same file: the
 * second overwrote the first and both reported the same path.
 */
@RunWith(RobolectricTestRunner::class)
class CaptureFileNameTest {
    @get:Rule
    val folder = TemporaryFolder()

    /** Photos and videos are both created this way; a burst stays well inside one second. */
    @Test
    fun captureFiles_createdInABurst_areAllDistinctAndNoneOverwritesAnother() {
        val dir = folder.newFolder("CameraK")

        val files = (1..20).map { index ->
            newCaptureFile(dir, "IMG", "jpg").also { it.writeText("capture $index") }
        }

        assertEquals(20, files.toSet().size, "a burst shared files: ${files.map { it.name }}")
        files.forEachIndexed { index, file -> assertEquals("capture ${index + 1}", file.readText()) }
    }

    @Test
    fun captureFile_isClaimedWhenItsNameIsChosen() {
        val file = newCaptureFile(folder.newFolder("CameraK"), "VID", "mp4")

        assertTrue(file.exists(), "the name was chosen but not claimed, so a concurrent capture can take it")
    }

    @Test
    fun twoFileCapturesInFlight_writeToDifferentFiles() = runTest {
        val platform = FakeAndroidPlatform()
        val engine = platform.engine(testScheduler)
        open(platform, engine)
        repeat(2) { backgroundScope.launch { engine.capture() } }
        platform.settle(testScheduler)
        val backend = platform.backend
        assertEquals(2, backend.captures.size, "both captures should be in flight")

        assertNotEquals(backend.captures[0].outputFile, backend.captures[1].outputFile)
        engine.close()
    }
}
