package com.kashif.cameraK.controller

import android.content.Context
import android.os.Looper
import androidx.camera.view.PreviewView
import androidx.test.core.app.ApplicationProvider
import com.kashif.cameraK.utils.CameraKLogger
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
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Capture listeners run on the controller's processing executor. Whatever happens there (a listener
 * throws, the saved file cannot be read) must stay there: nothing may reach the thread's uncaught
 * handler, which on Android kills the host process.
 */
@RunWith(RobolectricTestRunner::class)
class CaptureListenerIsolationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val uncaught: MutableList<Throwable> = Collections.synchronizedList(mutableListOf())
    private val processingExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "test-image-processing").apply {
            setUncaughtExceptionHandler { _, error -> uncaught += error }
        }
    }
    private val backend = FakeCameraXBackend()
    private val controller = testController(context, backend, imageProcessingExecutor = processingExecutor)
    private val logged: MutableList<Pair<String, Throwable?>> = Collections.synchronizedList(mutableListOf())
    private val hostScope = CoroutineScope(Job() + Dispatchers.Unconfined)

    @After
    fun tearDown() {
        hostScope.cancel()
        processingExecutor.shutdownNow()
        CameraKLogger.sink = null
        CameraKLogger.enabled = false
    }

    @Test
    fun throwingListener_isContainedAndTheNextListenerStillGetsTheImage() {
        val bytes = byteArrayOf(9, 8, 7)
        val delivered = mutableListOf<ByteArray>()
        controller.addImageCaptureListener { throw IllegalStateException("listener failed") }
        controller.addImageCaptureListener { delivered += it }

        captureAndSave { it.deliverSaved(bytes) }

        assertEquals(emptyList(), uncaught.map { it.toString() }, "a listener's exception reached the uncaught handler")
        assertEquals(1, delivered.size, "one throwing listener starved the next one")
        assertContentEquals(bytes, delivered.single())
    }

    @Test
    fun unreadableCaptureFile_isLoggedAndNeverReachesTheUncaughtHandler() {
        recordLogs()
        val delivered = mutableListOf<ByteArray>()
        controller.addImageCaptureListener { delivered += it }

        captureAndSave { it.deliverSavedWithoutFile() }

        assertEquals(emptyList(), uncaught.map { it.toString() }, "the failed read reached the uncaught handler")
        assertEquals(0, delivered.size, "listeners got bytes for a file that could not be read")
        assertTrue(
            logged.any { (_, error) -> error is java.io.IOException },
            "the failed read was not logged with its throwable: $logged",
        )
    }

    private fun captureAndSave(deliver: (PendingCapture) -> Unit) {
        controller.bindCamera(PreviewView(context))
        idleMainLooper()
        hostScope.async(start = CoroutineStart.UNDISPATCHED) { controller.takePictureToFile() }
        deliver(backend.captures.single())
        idleMainLooper()
        // Wait for the listener work the save posted to the processing executor.
        processingExecutor.shutdown()
        assertTrue(processingExecutor.awaitTermination(5, TimeUnit.SECONDS), "listener work did not finish")
    }

    private fun recordLogs() {
        CameraKLogger.enabled = true
        CameraKLogger.sink = { _, _, message, throwable -> logged += message to throwable }
    }
}

private fun idleMainLooper() = shadowOf(Looper.getMainLooper()).idle()
