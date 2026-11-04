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

/**
 * The 1.x facade's captures over the engine. The limit of three in flight is the holder's alone since
 * A3' (PlatformCounterGoneTest); a batch that finishes leaves room for another, and a capture whose
 * caller has already left never reaches CameraX.
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
