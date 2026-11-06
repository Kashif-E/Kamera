@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import android.os.Looper
import androidx.lifecycle.Lifecycle
import com.kashif.cameraK.engine.EngineState
import com.kashif.cameraK.engine.FakeAndroidPlatform
import com.kashif.cameraK.engine.open
import com.kashif.cameraK.engine.startRecording
import com.kashif.cameraK.utils.CameraKEngineApi
import com.kashif.cameraK.utils.CameraKLogger
import com.kashif.cameraK.video.VideoConfiguration
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
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
    private val platform = FakeAndroidPlatform()
    private val imageExecutor = Executors.newFixedThreadPool(2)
    private val analyzerExecutor = Executors.newSingleThreadExecutor()
    private val errors = mutableListOf<String>()
    private val escaped = mutableListOf<Throwable>()
    private val mainThread = Looper.getMainLooper().thread
    private val previousHandler = mainThread.uncaughtExceptionHandler

    @After
    fun tearDown() {
        mainThread.uncaughtExceptionHandler = previousHandler
        CameraKLogger.sink = null
        CameraKLogger.enabled = false
        imageExecutor.shutdownNow()
        analyzerExecutor.shutdownNow()
    }

    @Test
    fun aThrowingReleaseStepIsLoggedAndTheRemainingStepsStillRun() = runTest {
        val engine = platform.engine(
            testScheduler,
            imageProcessing = imageExecutor,
            analyzerExecutor = analyzerExecutor,
        )
        open(platform, engine)
        val (recording, _) = startRecording(platform, engine, VideoConfiguration { enableAudio = false })
        recording.stopFailure = IllegalStateException("scripted recording stop failure")
        CameraKLogger.enabled = true
        CameraKLogger.sink = { level, _, message, _ -> if (level == CameraKLogger.Level.ERROR) errors += message }
        mainThread.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, e -> escaped += e }

        engine.close()
        runCatching { platform.settle(testScheduler) }.exceptionOrNull()?.let { escaped += it }

        assertTrue(escaped.isEmpty(), "a release failure reached the main looper: $escaped")
        assertTrue(imageExecutor.isShutdown, "the image-processing executor was not shut down")
        assertTrue(analyzerExecutor.isShutdown, "the analyzer executor was not shut down")
        assertEquals(EngineState.Closed, engine.state.value)
        assertEquals(Lifecycle.State.DESTROYED, engine.lifecycle.currentState)
        assertTrue(
            errors.any { "recording" in it.lowercase() && "release" in it.lowercase() },
            "the failed release step was not logged by name: $errors",
        )
    }
}
