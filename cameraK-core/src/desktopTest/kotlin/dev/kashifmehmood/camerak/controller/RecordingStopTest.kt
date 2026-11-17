package dev.kashifmehmood.camerak.controller

import dev.kashifmehmood.camerak.capture.RecordedVideo
import dev.kashifmehmood.camerak.engine.newDedicatedWorker
import dev.kashifmehmood.camerak.recording.RecordingEndReason
import dev.kashifmehmood.camerak.result.CameraKResult
import dev.kashifmehmood.camerak.video.VideoConfiguration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.bytedeco.javacv.Frame
import org.bytedeco.javacv.FrameRecorder
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * A stop ends the recording at the next frame, even when the recording is behind the camera: its
 * feed then always has a frame waiting, so taking one never suspends, where a stop's cancellation
 * would otherwise be seen, and every frame captured after the stop was written until it caught up.
 */
class RecordingStopTest {
    /** Holds the first frame's `record()` until [release], as an encoder that falls behind. */
    private class HeldRecorder : FrameRecorder() {
        init {
            frameRate = 30.0
        }

        val inFirstFrame = CountDownLatch(1)
        val release = CountDownLatch(1)
        val timestamps: MutableList<Long> = CopyOnWriteArrayList()

        override fun start() = Unit

        override fun flush() = Unit

        override fun stop() = Unit

        override fun release() = Unit

        override fun record(frame: Frame) {
            timestamps += timestamp
            if (timestamps.size == 1) {
                inFirstFrame.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
        }
    }

    private fun frameAt(millis: Long) = LeasedFrame(width = 4, height = 4).apply { begin(nanos = millis * 1_000_000) }

    @Test
    fun aFrameCapturedAfterTheStopIsNotWrittenThoughOneIsAlwaysWaiting() = runBlocking {
        val clock = AtomicLong(1_000L * 1_000_000)
        val recorder = HeldRecorder()
        val file = File.createTempFile("camerak-stop", ".mp4").apply { deleteOnExit() }
        val finalized = CompletableDeferred<CameraKResult<RecordedVideo>>()
        val recording = DesktopRecording(
            file.path,
            VideoConfiguration { enableAudio = false },
            recorder,
            clock::get,
            { false },
            object : RecordingListener {
                override fun onStarted(recording: DesktopRecording) = true

                override fun onFinalized(
                    recording: DesktopRecording,
                    result: CameraKResult<RecordedVideo>,
                    reason: RecordingEndReason,
                ) {
                    finalized.complete(result)
                }
            },
        )
        val feed = FrameFeed()
        val scope = CoroutineScope(SupervisorJob())
        try {
            // The camera's frame 40 ms after the start is waiting when the recording opens its feed.
            feed.offer(frameAt(1_040))
            recording.prepare(scope, newDedicatedWorker("recording-stop-test"), { feed }, 4 to 4)
            assertTrue(recording.start() is CameraKResult.Success, "the start")
            assertTrue(recorder.inFirstFrame.await(5, TimeUnit.SECONDS), "the first frame was not written")

            // Stopped while that frame encodes; the camera's next frame, captured after the stop, waits.
            recording.requestStop(RecordingEndReason.USER)
            feed.offer(frameAt(1_080))
            recorder.release.countDown()

            withTimeout(5.seconds) { finalized.await() }
            assertEquals(listOf(40_000L), recorder.timestamps.toList(), "the frames written, by timestamp")
        } finally {
            recorder.release.countDown()
            scope.cancel()
        }
    }
}
