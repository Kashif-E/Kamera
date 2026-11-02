package com.kashif.cameraK.controller

import android.content.Context
import android.os.Looper
import androidx.camera.view.PreviewView
import androidx.test.core.app.ApplicationProvider
import com.kashif.cameraK.result.ImageCaptureResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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
 * At most three stills are in flight at once; a fourth is rejected. Each capture holds one of those
 * slots until it is settled, by its result, its error or its cancellation, and gives it back once.
 */
@RunWith(RobolectricTestRunner::class)
class PendingCapturesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val backend = FakeCameraXBackend()
    private val controller = testController(context, backend)
    private val hostScope = CoroutineScope(Job() + Dispatchers.Unconfined)

    @After
    fun tearDown() {
        hostScope.cancel()
    }

    @Test
    fun captureCancelledBeforeCameraXFinishes_givesItsSlotBackOnce() {
        controller.bindCamera(PreviewView(context))
        idleMainLooper()

        val cancelled = hostScope.launch(start = CoroutineStart.UNDISPATCHED) { controller.takePictureToFile() }
        cancelled.cancel()
        // CameraX does not know the caller left: it still writes the still and reports it.
        backend.captures.single().deliverSaved(byteArrayOf(1))
        idleMainLooper()

        val captures = List(MAX_IN_FLIGHT + 1) {
            hostScope.async(start = CoroutineStart.UNDISPATCHED) { controller.takePictureToFile() }
        }

        assertEquals(
            1 + MAX_IN_FLIGHT,
            backend.captures.size,
            "more than $MAX_IN_FLIGHT captures reached CameraX at once",
        )
        val rejected = captures.last()
        assertTrue(rejected.isCompleted, "the capture over the limit was not rejected at once")
        assertIs<ImageCaptureResult.Error>(runBlocking { rejected.await() })
    }

    @Test
    fun capturesThatFinish_leaveRoomForAFullBatchAgain() {
        controller.bindCamera(PreviewView(context))
        idleMainLooper()

        repeat(2) { batch ->
            val captures = List(MAX_IN_FLIGHT) {
                hostScope.async(start = CoroutineStart.UNDISPATCHED) { controller.takePictureToFile() }
            }
            backend.captures.takeLast(MAX_IN_FLIGHT).forEach { it.deliverSaved(byteArrayOf(2)) }
            idleMainLooper()
            captures.forEach {
                assertIs<ImageCaptureResult.SuccessWithFile>(runBlocking { it.await() }, "batch $batch")
            }
        }
    }

    @Test
    fun captureWhoseCallerHasAlreadyLeft_neverReachesCameraXAndKeepsNoSlot() {
        controller.bindCamera(PreviewView(context))
        idleMainLooper()

        hostScope.launch(start = CoroutineStart.UNDISPATCHED) {
            cancel()
            controller.takePictureToFile()
        }

        assertEquals(0, backend.captures.size, "a capture whose caller had already left still reached CameraX")
        List(MAX_IN_FLIGHT) { hostScope.async(start = CoroutineStart.UNDISPATCHED) { controller.takePictureToFile() } }
        assertEquals(MAX_IN_FLIGHT, backend.captures.size, "the abandoned capture kept a slot")
    }

    private companion object {
        const val MAX_IN_FLIGHT = 3
    }
}

private fun idleMainLooper() = shadowOf(Looper.getMainLooper()).idle()
