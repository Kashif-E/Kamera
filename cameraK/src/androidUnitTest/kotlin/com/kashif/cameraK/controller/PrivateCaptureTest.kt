package com.kashif.cameraK.controller

import android.content.Context
import android.os.Looper
import android.provider.MediaStore
import androidx.camera.view.PreviewView
import androidx.test.core.app.ApplicationProvider
import com.kashif.cameraK.enums.Directory
import com.kashif.cameraK.result.ImageCaptureResult
import com.kashif.cameraK.state.CameraConfiguration
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
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * With no opt-in, a capture stays in app-private storage: an app using CameraK for ID selfies,
 * document scans or receipts must not leak them into the shared gallery, where any app holding
 * READ_MEDIA_IMAGES can read them. The private option is internal storage (`filesDir`), not
 * app-specific external storage, which can sit on removable or adoptable media.
 */
@RunWith(RobolectricTestRunner::class)
class PrivateCaptureTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val mediaStore = Robolectric.setupContentProvider(FakeMediaStore::class.java, MediaStore.AUTHORITY)
    private val backend = FakeCameraXBackend()
    private val hostScope = CoroutineScope(Job() + Dispatchers.Unconfined)
    private val filesDir = context.filesDir.canonicalFile

    @After
    fun tearDown() {
        hostScope.cancel()
    }

    @Test
    fun defaultConfiguration_keepsTheCaptureOutOfTheGallery() {
        val result = capture(CameraConfiguration().directory)

        assertEquals(
            emptyList(),
            mediaStore.rows.keys.toList(),
            "a capture with the default configuration was published",
        )
        val path = assertIs<ImageCaptureResult.SuccessWithFile>(result).filePath
        assertTrue(File(path).canonicalPath.startsWith(filesDir.path + File.separator), "$path is not in $filesDir")
    }

    @Test
    fun documentsCapture_isInInternalStorage() {
        val path = assertIs<ImageCaptureResult.SuccessWithFile>(capture(Directory.DOCUMENTS)).filePath

        assertEquals(File(filesDir, "CameraK"), File(path).canonicalFile.parentFile)
    }

    /** Photos and videos that stay private are both written here. */
    @Test
    fun privateCaptureFolder_isInInternalStorage() {
        assertEquals(File(filesDir, "CameraK"), appCaptureDirectory(context).canonicalFile)
    }

    private fun capture(directory: Directory): ImageCaptureResult {
        val controller = testController(context, backend, directory = directory)
        controller.bindCamera(PreviewView(context))
        shadowOf(Looper.getMainLooper()).idle()
        val result = hostScope.async(start = CoroutineStart.UNDISPATCHED) { controller.takePictureToFile() }
        backend.captures.last().deliverSaved(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(result.isCompleted, "takePictureToFile did not return")
        return runBlocking { result.await() }
    }
}
