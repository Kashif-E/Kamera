package com.kashif.cameraK.controller

import android.content.Context
import android.os.Looper
import android.provider.MediaStore
import androidx.camera.view.PreviewView
import androidx.test.core.app.ApplicationProvider
import com.kashif.cameraK.enums.Directory
import com.kashif.cameraK.enums.ImageFormat
import com.kashif.cameraK.result.ImageCaptureResult
import com.kashif.cameraK.utils.CameraKLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Until A3' implements HEIC, a capture configured as HEIC fails rather than writing JPEG bytes the
 * app asked to be HEIC: no capture request reaches CameraX, no file or gallery entry is written, and
 * the refusal is logged once.
 */
@RunWith(RobolectricTestRunner::class)
class HeicCaptureTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val mediaStore = Robolectric.setupContentProvider(FakeMediaStore::class.java, MediaStore.AUTHORITY)
    private val backend = FakeCameraXBackend()
    private val hostScope = CoroutineScope(Job() + Dispatchers.Unconfined)
    private val logged = CopyOnWriteArrayList<String>()

    @After
    fun tearDown() {
        hostScope.cancel()
        CameraKLogger.sink = null
        CameraKLogger.enabled = false
    }

    @Test
    fun heicCapture_failsWithoutWritingAFile() {
        CameraKLogger.enabled = true
        CameraKLogger.sink = { _, _, message, _ -> if ("HEIC" in message) logged += message }
        val controller =
            testController(context, backend, directory = Directory.PICTURES, imageFormat = ImageFormat.HEIC)
        controller.bindCamera(PreviewView(context))
        idleMainLooper()

        val result = hostScope.async(start = CoroutineStart.UNDISPATCHED) { controller.takePictureToFile() }
        idleMainLooper()

        assertTrue(result.isCompleted, "takePictureToFile did not return")
        val error = assertIs<ImageCaptureResult.Error>(runBlocking { result.await() })
        assertEquals("HEIC capture is not supported yet", error.exception.message)
        assertEquals(0, backend.captures.size, "a HEIC capture reached CameraX")
        assertEquals(emptyList(), mediaStore.rows.keys.toList(), "a HEIC capture wrote a gallery entry")
        assertEquals(1, logged.size, "the refusal is logged once: $logged")
    }

    @Test
    fun heicCapture_writesNothingToAppStorage() {
        val controller =
            testController(context, backend, directory = Directory.DOCUMENTS, imageFormat = ImageFormat.HEIC)
        controller.bindCamera(PreviewView(context))
        idleMainLooper()

        val result = hostScope.async(start = CoroutineStart.UNDISPATCHED) { controller.takePictureToFile() }
        idleMainLooper()

        assertTrue(result.isCompleted, "takePictureToFile did not return")
        assertIs<ImageCaptureResult.Error>(runBlocking { result.await() })
        assertEquals(emptyList(), appCaptureDirectory(context).listFiles().orEmpty().map { it.name })
    }
}

private fun idleMainLooper() = shadowOf(Looper.getMainLooper()).idle()
