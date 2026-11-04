package com.kashif.cameraK.controller

import android.content.Context
import android.os.Looper
import androidx.camera.view.PreviewView
import androidx.test.core.app.ApplicationProvider
import com.kashif.cameraK.enums.Directory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
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

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val backend = FakeCameraXBackend()
    private val hostScope = CoroutineScope(Job() + Dispatchers.Unconfined)

    @After
    fun tearDown() {
        hostScope.cancel()
    }

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
    fun twoFileCapturesInFlight_writeToDifferentFiles() {
        captureTwice(Directory.DOCUMENTS)

        assertNotEquals(backend.captures[0].outputFile, backend.captures[1].outputFile)
    }

    private fun captureTwice(directory: Directory) {
        val controller = testController(context, backend, directory = directory)
        controller.bindCamera(PreviewView(context))
        shadowOf(Looper.getMainLooper()).idle()
        repeat(2) { hostScope.async(start = CoroutineStart.UNDISPATCHED) { controller.takePictureToFile() } }
        assertEquals(2, backend.captures.size, "both captures should be in flight")
    }
}
