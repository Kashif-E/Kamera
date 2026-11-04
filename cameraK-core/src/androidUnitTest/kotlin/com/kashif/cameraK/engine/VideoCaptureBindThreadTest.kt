@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.engine

import android.os.Looper
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.utils.CameraKEngineApi
import com.kashif.cameraK.video.VideoConfiguration
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The Pixel 7 rule (§2.4, w6-design-decisions): CameraX requires the bind call itself on Main, so only
 * bindToLifecycle runs there. The Recorder and its VideoCapture are built, and the bound camera's video
 * qualities read, on the engine's default dispatcher; FakeCameraBinding throws off Main as CameraX
 * does. The main-thread part is one `CameraK.videoBind` interval, the device marker B1 records.
 */
@RunWith(RobolectricTestRunner::class)
class VideoCaptureBindThreadTest {
    private val platform = FakeAndroidPlatform()
    private val background: ExecutorCoroutineDispatcher =
        Executors.newSingleThreadExecutor { Thread(it, "camerak-test-background") }.asCoroutineDispatcher()

    @After
    fun closeBackground() = background.close()

    @Test
    fun recorderBuildAndQualityQueryRunOffMainAndOnlyTheBindRunsOnMain() = runTest {
        val main = Looper.getMainLooper().thread
        val tracer = RecordingTracer(main)
        val engine = platform.engine(testScheduler, background = background, runtime = EngineRuntime(tracer = tracer))
        assertEquals(CameraKResult.Success(Unit), engine.start(FakeAndroidPlatform.desired))
        settleUntil { platform.backend.qualityQueryThreads.isNotEmpty() }

        val start = async { engine.startRecording(VideoConfiguration()) }
        settleUntil { start.isCompleted }

        assertIs<CameraKResult.Success<String>>(start.await())
        val builds = platform.backend.videoCaptureBuilds
        assertEquals(1, builds.size, "VideoCapture builds")
        assertFalse(builds.single().thread == main, "the Recorder and VideoCapture were built on the main thread")
        val queries = platform.backend.qualityQueryThreads
        assertTrue(queries.isNotEmpty() && queries.none { it == main }, "the quality query ran on Main: $queries")
        assertTrue(platform.backend.binding.bindThreads.all { it == main }, "a bind ran off Main")
        assertEquals(
            listOf("begin $VIDEO_BIND on main", "end $VIDEO_BIND on main"),
            tracer.events.filter { VIDEO_BIND in it },
            "the main-thread part of the VideoCapture bind is not one interval",
        )
        platform.backend.recordings.single().finalize()
        settleUntil { true }
        engine.closeAndJoin()
    }

    // The background dispatcher runs on its own thread; settle until it has done its part.
    private fun kotlinx.coroutines.test.TestScope.settleUntil(done: () -> Boolean) {
        repeat(200) {
            platform.settle(testScheduler)
            if (done()) return
            Thread.sleep(5)
        }
        platform.settle(testScheduler)
    }

    private class RecordingTracer(private val main: Thread) : Tracer {
        val events = mutableListOf<String>()

        override fun beginInterval(name: String): AutoCloseable {
            events += "begin $name on ${where()}"
            return AutoCloseable { events += "end $name on ${where()}" }
        }

        override fun marker(name: String) {
            events += "marker $name"
        }

        private fun where() = if (Thread.currentThread() == main) "main" else Thread.currentThread().name
    }

    private companion object {
        const val VIDEO_BIND = "CameraK.videoBind"
    }
}
