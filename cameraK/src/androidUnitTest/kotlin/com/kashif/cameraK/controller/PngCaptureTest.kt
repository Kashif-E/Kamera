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
 * CameraX's file output always encodes JPEG, so on Android a capture configured as PNG holds JPEG
 * bytes. Its name and MIME type must say JPEG, and the substitution is logged, rather than a `.png`
 * registered as `image/png` that PNG decoders reject.
 */
@RunWith(RobolectricTestRunner::class)
class PngCaptureTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val mediaStore = Robolectric.setupContentProvider(FakeMediaStore::class.java, MediaStore.AUTHORITY)
    private val backend = FakeCameraXBackend()
    private val hostScope = CoroutineScope(Job() + Dispatchers.Unconfined)
    private val warnings = CopyOnWriteArrayList<String>()
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1)

    @After
    fun tearDown() {
        hostScope.cancel()
        CameraKLogger.sink = null
        CameraKLogger.enabled = false
    }

    @Test
    fun pngFileCapture_isNamedJpg() {
        val result = capture(Directory.DOCUMENTS)

        val path = assertIs<ImageCaptureResult.SuccessWithFile>(result).filePath
        assertTrue(path.endsWith(".jpg"), "a JPEG capture was named $path")
    }

    @Test
    fun pngGalleryCapture_isRegisteredAsJpeg() {
        capture(Directory.PICTURES)

        val values = mediaStore.rows.values.single()
        assertEquals("image/jpeg", values.getAsString(MediaStore.MediaColumns.MIME_TYPE))
        val name = values.getAsString(MediaStore.MediaColumns.DISPLAY_NAME)
        assertTrue(name.endsWith(".jpg"), "a JPEG capture was named $name")
    }

    @Test
    fun pngConfiguration_isReportedAsAWarning() {
        recordWarnings()

        testController(context, backend, imageFormat = ImageFormat.PNG)

        assertTrue(warnings.any { "PNG" in it }, "no warning that PNG captures are JPEG on Android: $warnings")
    }

    private fun capture(directory: Directory): ImageCaptureResult {
        val controller = testController(context, backend, directory = directory, imageFormat = ImageFormat.PNG)
        controller.bindCamera(PreviewView(context))
        idleMainLooper()
        val result = hostScope.async(start = CoroutineStart.UNDISPATCHED) { controller.takePictureToFile() }
        backend.captures.last().deliverSaved(jpeg)
        idleMainLooper()
        assertTrue(result.isCompleted, "takePictureToFile did not return")
        return runBlocking { result.await() }
    }

    private fun recordWarnings() {
        CameraKLogger.enabled = true
        CameraKLogger.sink = { level, _, message, _ -> if (level == CameraKLogger.Level.WARN) warnings += message }
    }
}

private fun idleMainLooper() = shadowOf(Looper.getMainLooper()).idle()
