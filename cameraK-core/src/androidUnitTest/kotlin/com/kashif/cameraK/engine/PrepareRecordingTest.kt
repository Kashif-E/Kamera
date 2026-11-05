@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.engine

import androidx.camera.video.VideoCapture
import com.kashif.cameraK.capture.RecordedVideo
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.utils.CameraKEngineApi
import com.kashif.cameraK.video.VideoConfiguration
import com.kashif.cameraK.video.VideoQuality
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

/**
 * prepareRecording binds VideoCapture ahead of the start, so the start commits no configuration
 * change. It is the app's explicit opt-in to #136's trade: while VideoCapture is bound, stills crop to
 * its field of view. The prepared VideoCapture stays until the engine stops; without prepare the bind
 * stays lazy.
 */
@RunWith(RobolectricTestRunner::class)
class PrepareRecordingTest {
    private val platform = FakeAndroidPlatform()

    @Test
    fun prepareBindsVideoCaptureOnceAndTheStartDoesNotRebind() = runTest {
        val engine = platform.engine(testScheduler)
        open(platform, engine)
        val binds = platform.backend.binding.bindCalls

        assertEquals(CameraKResult.Success(Unit), engine.prepareRecording(VideoConfiguration()))
        platform.settle(testScheduler)
        assertEquals(binds + 1, platform.backend.binding.bindCalls, "prepare did not bind VideoCapture once")
        val prepared = boundVideoCapture()

        startRecording(platform, engine)

        assertEquals(binds + 1, platform.backend.binding.bindCalls, "the prepared start rebound the camera")
        assertSame(prepared, boundVideoCapture(), "the start replaced the prepared VideoCapture")
        platform.backend.recordings.single().finalize()
        platform.settle(testScheduler)
        engine.closeAndJoin()
    }

    @Test
    fun preparedVideoCaptureStaysAfterTheRecordingEndsUntilStop() = runTest {
        val engine = platform.engine(testScheduler)
        open(platform, engine)
        assertEquals(CameraKResult.Success(Unit), engine.prepareRecording(VideoConfiguration()))
        platform.settle(testScheduler)
        val prepared = boundVideoCapture()
        val (recording, _) = startRecording(platform, engine)
        val binds = platform.backend.binding.bindCalls

        val stop = async { engine.stopRecording() }
        platform.settle(testScheduler)
        recording.finalize()
        platform.settle(testScheduler)
        assertIs<CameraKResult.Success<RecordedVideo>>(stop.await())

        assertSame(prepared, boundVideoCapture(), "the prepared VideoCapture went with the recording")
        assertEquals(binds, platform.backend.binding.bindCalls, "the end of a prepared recording rebound")

        engine.stop()
        platform.settle(testScheduler)
        assertEquals(CameraKResult.Success(Unit), engine.start(FakeAndroidPlatform.desired))
        platform.settle(testScheduler)
        assertEquals(
            emptyList(),
            platform.backend.binding.bound.filterIsInstance<VideoCapture<*>>(),
            "the prepared VideoCapture outlived the stop",
        )
        engine.closeAndJoin()
    }

    @Test
    fun startWithAnotherQualityThanPreparedRebindsOnce() = runTest {
        val engine = platform.engine(testScheduler)
        open(platform, engine)
        assertEquals(
            CameraKResult.Success(Unit),
            engine.prepareRecording(VideoConfiguration { quality = VideoQuality.HD }),
        )
        platform.settle(testScheduler)
        val binds = platform.backend.binding.bindCalls

        startRecording(platform, engine, VideoConfiguration { quality = VideoQuality.FHD })

        assertEquals(binds + 1, platform.backend.binding.bindCalls, "a start in another quality did not rebind once")
        val build = platform.backend.videoCaptureBuilds.last()
        assertEquals(VideoQuality.FHD, build.quality)
        assertSame(build.videoCapture, boundVideoCapture(), "the start recorded on the prepared HD VideoCapture")
        platform.backend.recordings.single().finalize()
        platform.settle(testScheduler)
        engine.closeAndJoin()
    }

    private fun boundVideoCapture(): VideoCapture<*> =
        platform.backend.binding.bound.filterIsInstance<VideoCapture<*>>().single()
}
