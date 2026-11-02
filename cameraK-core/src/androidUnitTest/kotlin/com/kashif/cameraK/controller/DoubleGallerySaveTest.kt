package com.kashif.cameraK.controller

import android.content.ContentValues
import android.content.Context
import android.os.Looper
import android.provider.MediaStore
import androidx.camera.view.PreviewView
import androidx.test.core.app.ApplicationProvider
import com.kashif.cameraK.state.CameraConfiguration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ImageSaverPlugin's auto-save is a capture listener that inserts the delivered bytes into
 * MediaStore. With the default configuration the core must not publish the capture as well, or
 * every photo appears in the gallery twice.
 */
@RunWith(RobolectricTestRunner::class)
class DoubleGallerySaveTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val mediaStore = Robolectric.setupContentProvider(FakeMediaStore::class.java, MediaStore.AUTHORITY)
    private val backend = FakeCameraXBackend()
    private val processingExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val hostScope = CoroutineScope(Job() + Dispatchers.Unconfined)
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 4, 5, 6)

    @After
    fun tearDown() {
        hostScope.cancel()
        processingExecutor.shutdownNow()
    }

    @Test
    fun defaultConfiguration_withAnAutoSavingListener_putsEachCaptureInTheGalleryOnce() {
        val controller = testController(
            context,
            backend,
            imageProcessingExecutor = processingExecutor,
            directory = CameraConfiguration().directory,
        )
        controller.addImageCaptureListener(::saveToGalleryLikeImageSaver)
        controller.bindCamera(PreviewView(context))
        shadowOf(Looper.getMainLooper()).idle()

        val result = hostScope.async(start = CoroutineStart.UNDISPATCHED) { controller.takePictureToFile() }
        backend.captures.last().deliverSaved(jpeg)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(result.isCompleted, "takePictureToFile did not return")
        processingExecutor.shutdown()
        assertTrue(processingExecutor.awaitTermination(5, TimeUnit.SECONDS), "listener work did not finish")

        assertEquals(1, mediaStore.rows.size, "gallery entries for one capture: ${mediaStore.rows.keys}")
        assertContentEquals(jpeg, mediaStore.bytesOf(mediaStore.rows.keys.single()))
    }

    /** What AndroidImageSaverPlugin.save does with the delivered bytes on API 29+. */
    private fun saveToGalleryLikeImageSaver(bytes: ByteArray) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.DISPLAY_NAME, "saved.jpg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/CameraK")
        }
        val row = checkNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
        checkNotNull(resolver.openOutputStream(row)).use { it.write(bytes) }
    }
}
