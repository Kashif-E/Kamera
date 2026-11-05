@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import android.content.Context
import android.os.Looper
import androidx.camera.view.PreviewView
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import com.kashif.cameraK.engine.EngineState
import com.kashif.cameraK.utils.CameraKEngineApi
import com.kashif.cameraK.utils.CameraKLogger
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
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Closing the engine releases everything once, even when one step of the release throws: the
 * failure is logged with the step named, no exception reaches the main looper, and the steps after
 * it still run, down to the executor shutdowns, so the engine ends Closed.
 */
@RunWith(RobolectricTestRunner::class)
class ReleaseFailureTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val backend = FakeCameraXBackend()
    private val imageExecutor = Executors.newFixedThreadPool(2)
    private val analyzerExecutor = Executors.newSingleThreadExecutor()
    private val controller = testController(
        context,
        backend,
        imageProcessingExecutor = imageExecutor,
        analyzerExecutor = analyzerExecutor,
    )
    private val hostScope = CoroutineScope(Job() + Dispatchers.Unconfined)
    private val errors = mutableListOf<String>()
    private val escaped = mutableListOf<Throwable>()
    private val mainThread = Looper.getMainLooper().thread
    private val previousHandler = mainThread.uncaughtExceptionHandler

    @After
    fun tearDown() {
        mainThread.uncaughtExceptionHandler = previousHandler
        hostScope.cancel()
        CameraKLogger.sink = null
        CameraKLogger.enabled = false
        imageExecutor.shutdownNow()
        analyzerExecutor.shutdownNow()
    }

    @Test
    fun aThrowingReleaseStepIsLoggedAndTheRemainingStepsStillRun() {
        controller.bindCamera(PreviewView(context))
        idleMainLooper()
        val start = hostScope.async(start = CoroutineStart.UNDISPATCHED) {
            controller.startRecording(VideoConfiguration { enableAudio = false })
        }
        idleMainLooper()
        assertTrue(start.isCompleted, "setup: the recording did not start")
        backend.recordings.single().stopFailure = IllegalStateException("scripted recording stop failure")
        CameraKLogger.enabled = true
        CameraKLogger.sink = { level, _, message, _ -> if (level == CameraKLogger.Level.ERROR) errors += message }
        mainThread.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, e -> escaped += e }

        controller.cleanup()
        runCatching { idleMainLooper() }.exceptionOrNull()?.let { escaped += it }

        assertTrue(escaped.isEmpty(), "a release failure reached the main looper: $escaped")
        assertTrue(imageExecutor.isShutdown, "the image-processing executor was not shut down")
        assertTrue(analyzerExecutor.isShutdown, "the analyzer executor was not shut down")
        assertEquals(EngineState.Closed, controller.engine.state.value)
        assertEquals(Lifecycle.State.DESTROYED, controller.engine.lifecycle.currentState)
        assertTrue(
            errors.any { "recording" in it.lowercase() && "release" in it.lowercase() },
            "the failed release step was not logged by name: $errors",
        )
    }
}

private fun idleMainLooper() = shadowOf(Looper.getMainLooper()).idle()
