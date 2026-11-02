package com.kashif.cameraK.controller

import android.content.Context
import android.os.Looper
import androidx.camera.core.ImageAnalysis
import androidx.camera.view.PreviewView
import androidx.test.core.app.ApplicationProvider
import com.kashif.cameraK.result.ImageCaptureResult
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.state.CameraKPlugin
import com.kashif.cameraK.state.CameraKStateHolder
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
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Shutting the camera down while CameraX work is still queued on the main thread must leave the
 * camera closed: no queued bind may reopen it, and no late capture callback may throw.
 */
@RunWith(RobolectricTestRunner::class)
class CameraControllerReleaseTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val backend = FakeCameraXBackend()
    private val controller = testController(context, backend)
    private val previewView = PreviewView(context)
    private val hostScope = CoroutineScope(Job() + Dispatchers.Unconfined)

    @After
    fun tearDown() {
        hostScope.cancel()
    }

    @Test
    fun shutdownBeforeTheProviderResolves_leavesTheCameraClosed() {
        val holder = initializedHolder()

        // The preview asks for a bind, then leaves composition before ProcessCameraProvider resolves.
        controller.bindCamera(previewView)
        holder.shutdown()
        idleMainLooper()

        assertEquals(emptySet(), backend.binding.bound, "a bind queued before shutdown reopened the camera")
        assertEquals(0, backend.binding.bindCalls, "bindToLifecycle ran after shutdown")
    }

    @Test
    fun shutdownWithTwoAnalyzerPlugins_doesNotRebindWhileDetachingThem() {
        val holder = initializedHolder(AnalyzerPlugin(), AnalyzerPlugin())
        controller.bindCamera(previewView)
        idleMainLooper()
        assertEquals(1, backend.binding.bindCalls, "setup: the preview binds once")

        holder.shutdown()
        idleMainLooper()

        assertEquals(emptySet(), backend.binding.bound, "detaching an analyzer during shutdown reopened the camera")
        assertEquals(1, backend.binding.bindCalls, "detaching an analyzer during shutdown queued a rebind")
    }

    @Test
    fun bindRequestedAfterShutdown_isRefused() {
        val holder = initializedHolder()
        controller.bindCamera(previewView)
        idleMainLooper()

        holder.shutdown()
        // Every late path (recording Finalize, orientation flip, lens switch) re-enters bindCamera.
        controller.bindCamera(previewView)
        idleMainLooper()

        assertEquals(emptySet(), backend.binding.bound, "a bind after shutdown reopened the camera")
        assertEquals(1, backend.binding.bindCalls, "bindToLifecycle ran after shutdown")
    }

    @Test
    fun captureSavedAfterShutdown_resolvesTheCallerAndDoesNotThrowOnMain() {
        val holder = initializedHolder()
        controller.bindCamera(previewView)
        idleMainLooper()
        controller.addImageCaptureListener { }
        val capture = hostScope.async(start = CoroutineStart.UNDISPATCHED) { controller.takePictureToFile() }
        val pending = backend.captures.single()

        holder.shutdown()
        // CameraX finishes writing the file and reports it after the controller was released.
        pending.deliverSaved(byteArrayOf(1, 2, 3))
        idleMainLooper()

        assertTrue(capture.isCompleted, "takePictureToFile is still suspended after shutdown")
        val result = runBlocking { capture.await() }
        assertIs<ImageCaptureResult.Error>(result, "a capture interrupted by shutdown must report an error")
    }

    private fun initializedHolder(vararg plugins: CameraKPlugin): CameraKStateHolder {
        val holder = CameraKStateHolder(
            cameraConfiguration = CameraConfiguration(),
            controllerFactory = { controller },
            coroutineScope = hostScope,
        )
        plugins.forEach(holder::attachPlugin)
        runBlocking { holder.initialize() }
        return holder
    }

    /** Registers a frame analyzer on attach and removes it on detach, as the QR and OCR plugins do. */
    private class AnalyzerPlugin : CameraKPlugin {
        private val analyzer = ImageAnalysis.Analyzer { it.close() }
        private var controller: CameraController? = null

        override fun onAttach(stateHolder: CameraKStateHolder) {
            controller = stateHolder.getController()?.also { it.registerImageAnalyzer(analyzer) }
        }

        override fun onDetach() {
            controller?.unregisterImageAnalyzer(analyzer)
            controller = null
        }
    }
}

private fun idleMainLooper() = shadowOf(Looper.getMainLooper()).idle()
