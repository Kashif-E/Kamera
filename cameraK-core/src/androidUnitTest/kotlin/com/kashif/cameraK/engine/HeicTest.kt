@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.engine

import android.os.Looper
import android.provider.MediaStore
import androidx.camera.view.PreviewView
import com.kashif.cameraK.controller.FakeCameraXBackend
import com.kashif.cameraK.controller.FakeMediaStore
import com.kashif.cameraK.controller.appCaptureDirectory
import com.kashif.cameraK.controller.testController
import com.kashif.cameraK.enums.ImageFormat
import com.kashif.cameraK.error.CameraFeature
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.result.ImageCaptureResult
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Android cannot write HEIC, and `start` never accepts a format it would ignore (§6.1): a HEIC
 * configuration fails the start with `Unsupported(IMAGE_FORMAT)` before anything is bound. The 1.x
 * facade over such an engine captures nothing.
 */
@RunWith(RobolectricTestRunner::class)
class HeicTest {
    private val hostScope = CoroutineScope(Job() + Dispatchers.Unconfined)

    @After
    fun tearDown() = hostScope.cancel()

    @Test
    fun heicConfiguredStartIsUnsupportedImageFormat() = runTest {
        val platform = FakeAndroidPlatform()
        val engine = platform.engine(testScheduler, CameraConfiguration(imageFormat = ImageFormat.HEIC))
        platform.settle(testScheduler)

        val result = engine.start(FakeAndroidPlatform.desired)

        val error = assertIs<CameraKError.Unsupported>(assertIs<CameraKResult.Failure>(result).error)
        assertEquals(CameraFeature.IMAGE_FORMAT, error.feature)
        val published = assertIs<CameraKError.Unsupported>(assertIs<EngineState.Failed>(engine.state.value).error)
        assertEquals(CameraFeature.IMAGE_FORMAT, published.feature)
        assertEquals(0, platform.backend.binding.bindCalls, "a HEIC start bound the camera")
        engine.closeAndJoin()
    }

    @Test
    fun heicConfiguredFacadeWritesNothing() {
        val mediaStore = Robolectric.setupContentProvider(FakeMediaStore::class.java, MediaStore.AUTHORITY)
        val backend = FakeCameraXBackend()
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val controller = testController(context, backend, imageFormat = ImageFormat.HEIC)
        controller.bindCamera(PreviewView(context))
        idleMainLooper()

        val result = hostScope.async(start = CoroutineStart.UNDISPATCHED) { controller.takePictureToFile() }
        idleMainLooper()

        assertTrue(result.isCompleted, "takePictureToFile did not return")
        assertIs<ImageCaptureResult.Error>(runBlocking { result.await() })
        assertTrue(backend.captures.isEmpty(), "a HEIC capture reached CameraX")
        assertEquals(emptyList(), mediaStore.rows.keys.toList(), "a HEIC capture created a gallery row")
        assertEquals(
            emptyList(),
            appCaptureDirectory(context).listFiles().orEmpty().toList(),
            "a HEIC capture wrote a file",
        )
        controller.cleanup()
        idleMainLooper()
    }

    private fun idleMainLooper() = shadowOf(Looper.getMainLooper()).idle()
}
