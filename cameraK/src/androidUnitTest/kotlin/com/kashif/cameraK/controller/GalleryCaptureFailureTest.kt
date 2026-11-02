package com.kashif.cameraK.controller

import android.content.Context
import android.os.Environment
import android.os.Looper
import android.provider.MediaStore
import androidx.camera.view.PreviewView
import androidx.test.core.app.ApplicationProvider
import com.kashif.cameraK.enums.Directory
import com.kashif.cameraK.result.ImageCaptureResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * When CameraX fails to write a gallery capture, for example on a full disk, it has already
 * inserted the MediaStore row and does not delete it, so the controller deletes it. Otherwise the
 * gallery shows an empty or truncated image for a capture that reported an error.
 */
@RunWith(RobolectricTestRunner::class)
class GalleryCaptureFailureTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val mediaStore = Robolectric.setupContentProvider(FakeMediaStore::class.java, MediaStore.AUTHORITY)
    private val backend = FakeCameraXBackend()
    private val processingExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val hostScope = CoroutineScope(Job() + Dispatchers.Unconfined)
    private val partialJpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())

    @After
    fun tearDown() {
        hostScope.cancel()
        processingExecutor.shutdownNow()
    }

    @Test
    fun galleryCaptureWhoseWriteFails_leavesNoGalleryEntry() {
        val result = captureFailingWrite(Directory.PICTURES)

        assertIs<ImageCaptureResult.Error>(result)
        assertEquals(emptyList(), mediaStore.rows.keys.toList(), "a failed capture left a gallery entry")
    }

    @Test
    @Config(sdk = [28])
    fun galleryCaptureWhoseWriteFails_belowApi29_leavesNoGalleryEntryOrFile() {
        val result = captureFailingWrite(Directory.DCIM)

        assertIs<ImageCaptureResult.Error>(result)
        assertEquals(emptyList(), mediaStore.rows.keys.toList(), "a failed capture left a gallery entry")
        val folder = File(publicDirectory(Environment.DIRECTORY_DCIM), "CameraK")
        assertEquals(emptyList(), folder.list().orEmpty().toList(), "a failed capture left a file in public DCIM")
    }

    private fun captureFailingWrite(directory: Directory): ImageCaptureResult {
        val controller =
            testController(context, backend, imageProcessingExecutor = processingExecutor, directory = directory)
        controller.bindCamera(PreviewView(context))
        idleMainLooper()
        val result = hostScope.async(start = CoroutineStart.UNDISPATCHED) { controller.takePictureToFile() }
        backend.captures.last().deliverWriteFailure(partialJpeg)
        idleMainLooper()
        return runBlocking { withTimeout(5_000) { result.await() } }
    }

    @Suppress("DEPRECATION") // Below API 29 the gallery row names a file in the public directory.
    private fun publicDirectory(type: String): File = Environment.getExternalStoragePublicDirectory(type)
}

private fun idleMainLooper() = shadowOf(Looper.getMainLooper()).idle()
