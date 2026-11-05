@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.engine

import android.os.Looper
import com.kashif.cameraK.capture.RecordedVideo
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.utils.CameraKEngineApi
import com.kashif.cameraK.video.VideoConfiguration
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * §4.1's playable probe reads and parses the finished recording's file, so it runs on the engine's io
 * dispatcher, not on the main thread CameraX reports the Finalize on; the recording's outcome and its
 * Finalized still follow from it.
 */
@RunWith(RobolectricTestRunner::class)
class RecordingProbeThreadTest {
    private val platform = FakeAndroidPlatform()
    private val background: ExecutorCoroutineDispatcher =
        Executors.newSingleThreadExecutor { Thread(it, "camerak-test-background") }.asCoroutineDispatcher()

    @After
    fun closeBackground() = background.close()

    @Test
    fun thePlayableProbeRunsOffTheMainThread() = runTest {
        val main = Looper.getMainLooper().thread
        val engine = platform.engine(testScheduler, background = background)
        val events = recordingEventsOf(engine)
        assertEquals(CameraKResult.Success(Unit), engine.start(FakeAndroidPlatform.desired))
        settleUntil { platform.backend.qualityQueryThreads.isNotEmpty() }
        val start = async { engine.startRecording(VideoConfiguration()) }
        settleUntil { start.isCompleted }
        val path = assertIs<CameraKResult.Success<String>>(start.await(), "setup: the start").value

        val stop = async { engine.stopRecording() }
        settleUntil { platform.backend.recordings.single().stopCalls == 1 }
        platform.backend.recordings.single().finalize()
        settleUntil { stop.isCompleted }

        assertEquals(path, assertIs<CameraKResult.Success<RecordedVideo>>(stop.await(), "the stop").value.path)
        val probes = platform.backend.probeThreads.toList()
        assertEquals(1, probes.size, "playable probes: $probes")
        assertTrue(probes.none { it == main }, "the playable probe ran on the main thread")
        assertEquals(1, events.count { it is EngineRecordingEvent.Finalized }, "Finalized events: $events")
        engine.closeAndJoin()
    }

    // The background dispatcher runs on its own thread; settle until it has done its part.
    private fun TestScope.settleUntil(done: () -> Boolean) {
        repeat(200) {
            platform.settle(testScheduler)
            if (done()) return
            Thread.sleep(5)
        }
        platform.settle(testScheduler)
    }
}
