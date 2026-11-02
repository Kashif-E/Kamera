package com.kashif.cameraK.controller

import android.content.Context
import android.os.Looper
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.view.PreviewView
import androidx.test.core.app.ApplicationProvider
import com.kashif.cameraK.video.VideoConfiguration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Plugins come and go (a QR scanner pauses, OCR starts) while the camera runs. Changing who receives
 * frames must not rebind the camera, which blanks the preview, and must leave exactly one
 * ImageAnalysis bound, feeding exactly the registered consumers.
 */
@RunWith(RobolectricTestRunner::class)
class AnalyzerRegistrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val backend = FakeCameraXBackend()
    private val controller = testController(context, backend)
    private val hostScope = CoroutineScope(Job() + Dispatchers.Unconfined)
    private val first = RecordingAnalyzer()
    private val second = RecordingAnalyzer()

    @After
    fun tearDown() {
        hostScope.cancel()
    }

    @Test
    fun changingConsumersOnARunningCamera_doesNotRebind() {
        controller.registerImageAnalyzer(first)
        controller.bindCamera(PreviewView(context))
        idleMainLooper()
        val bindsBefore = backend.binding.bindCalls

        controller.registerImageAnalyzer(second)
        idleMainLooper()
        controller.unregisterImageAnalyzer(first)
        idleMainLooper()

        assertEquals(bindsBefore, backend.binding.bindCalls, "changing frame consumers rebound the camera")
        deliverFrameToBoundAnalysis()
        assertEquals(0, first.frames, "an unregistered analyzer still receives frames")
        assertEquals(1, second.frames, "a registered analyzer does not receive frames")
    }

    @Test
    fun changingConsumersWhileRecording_keepsOneImageAnalysisFeedingExactlyTheRegisteredOnes() {
        controller.registerImageAnalyzer(first)
        controller.bindCamera(PreviewView(context))
        idleMainLooper()
        val start = hostScope.async(start = CoroutineStart.UNDISPATCHED) {
            controller.startRecording(VideoConfiguration(enableAudio = false))
        }
        idleMainLooper()
        assertTrue(start.isCompleted, "setup: the recording did not start")

        controller.registerImageAnalyzer(second)
        idleMainLooper()
        controller.unregisterImageAnalyzer(first)
        idleMainLooper()

        deliverFrameToBoundAnalysis()
        assertEquals(0, first.frames, "an unregistered analyzer still receives frames during a recording")
        assertEquals(1, second.frames, "a registered analyzer does not receive frames during a recording")
    }

    /** One frame from the camera, through every ImageAnalysis that is bound; there must be exactly one. */
    private fun deliverFrameToBoundAnalysis() {
        val bound = backend.binding.bound.filterIsInstance<ImageAnalysis>()
        assertEquals(1, bound.size, "expected exactly one bound ImageAnalysis, found ${bound.size}")
        val consumer = backend.analyzers[bound.single()]
        consumer?.analyze(frame())
    }

    private class RecordingAnalyzer : ImageAnalysis.Analyzer {
        var frames = 0
            private set

        override fun analyze(image: ImageProxy) {
            frames++
            image.close()
        }
    }
}

private fun frame(): ImageProxy =
    Proxy.newProxyInstance(ImageProxy::class.java.classLoader, arrayOf(ImageProxy::class.java)) { self, method, args ->
        when (method.name) {
            "getWidth", "getHeight", "getFormat" -> 0
            "hashCode" -> System.identityHashCode(self)
            "equals" -> self === args?.firstOrNull()
            "toString" -> "frame"
            else -> null
        }
    } as ImageProxy

private fun idleMainLooper() = shadowOf(Looper.getMainLooper()).idle()
