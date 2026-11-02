package com.kashif.cameraK.controller

import android.content.Context
import android.os.Looper
import androidx.camera.view.PreviewView
import androidx.test.core.app.ApplicationProvider
import com.kashif.cameraK.enums.CameraLens
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import kotlin.test.assertEquals

/** With mirroring on, a front-camera photo is mirrored like the preview (#112); a back-camera one never is. */
@RunWith(RobolectricTestRunner::class)
class FrontMirrorTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val hostScope = CoroutineScope(Job() + Dispatchers.Unconfined)

    @After
    fun tearDown() {
        hostScope.cancel()
    }

    @Test
    fun mirroring_reversesFrontCapturesOnly() {
        assertEquals(true, captureIsReversed(mirror = true, front = true), "a mirrored front capture was not reversed")
        assertEquals(false, captureIsReversed(mirror = true, front = false), "a back capture was reversed")
        assertEquals(false, captureIsReversed(mirror = false, front = true), "an unmirrored front capture was reversed")
    }

    private fun captureIsReversed(mirror: Boolean, front: Boolean): Boolean {
        val backend = FakeCameraXBackend()
        val controller = testController(context, backend, mirrorFrontCamera = mirror)
        if (front) controller.toggleCameraLens()
        assertEquals(if (front) CameraLens.FRONT else CameraLens.BACK, controller.getCameraLens())
        controller.bindCamera(PreviewView(context))
        idleMainLooper()

        hostScope.launch(start = CoroutineStart.UNDISPATCHED) { controller.takePictureToFile() }
        val reversed = backend.captures.single().options.metadata.isReversedHorizontal
        controller.cleanup()
        return reversed
    }
}

private fun idleMainLooper() = shadowOf(Looper.getMainLooper()).idle()
