@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.capture.RecordedVideo
import com.kashif.cameraK.coroutines.CameraKDispatchers
import com.kashif.cameraK.engine.CameraKEnvironment
import com.kashif.cameraK.engine.CameraSelection
import com.kashif.cameraK.engine.DesiredHardware
import com.kashif.cameraK.enums.CameraDeviceType
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.enums.FlashMode
import com.kashif.cameraK.enums.TorchMode
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.error.CameraOperation
import com.kashif.cameraK.permissions.PermissionStatus
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.utils.CameraKEngineApi
import com.kashif.cameraK.video.VideoConfiguration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.bytedeco.javacv.FrameRecorder
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Native handles (the frame grabber, the recorder) are freed only after the coroutine using them
 * has left them, and exactly once, however teardown is reached. The engine runs on real threads
 * here, because the fakes' overlap checks need them.
 */
class DesktopNativeTeardownTest {
    private val home: File = Files.createTempDirectory("camerak-teardown").toFile()

    private val desired = DesiredHardware(
        selection = CameraSelection.ByLens(CameraLens.BACK, CameraDeviceType.DEFAULT),
        flashMode = FlashMode.OFF,
        torchMode = TorchMode.OFF,
        zoomRatio = 1f,
        targetOrientation = null,
    )

    private fun engine(grabber: FakeFrameGrabber, recorder: FakeFrameRecorder = FakeFrameRecorder()) =
        engine(grabber) { recorder }

    private fun engine(grabber: FakeFrameGrabber, nextRecorder: () -> FakeFrameRecorder) = DesktopCameraEngine(
        CameraConfiguration(),
        CameraKEnvironment(CameraKDispatchers(main = Dispatchers.Default, default = Dispatchers.Default)),
        CustomGrabberSource(grabber),
        authorization = { PermissionStatus.Granted },
        media = DesktopMedia(userHome = home, recorderFactory = { _, _ -> nextRecorder() }),
    )

    private suspend fun open(sut: DesktopCameraEngine) {
        assertEquals(CameraKResult.Success(Unit), withTimeout(10_000) { sut.start(desired) }, "start")
    }

    private fun recordingConfig() = VideoConfiguration {
        enableAudio = false
        outputDirectory = Files.createTempDirectory("camerak-teardown").toFile().absolutePath
    }

    @Test
    fun stop_whileGrabIsInFlight_releasesTheGrabberOnlyAfterGrabReturns() {
        val fake = FakeFrameGrabber()
        val errors = CopyOnWriteArrayList<Throwable>()
        val frames = MutableSharedFlow<BufferedImage>(
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
        val scope = CoroutineScope(SupervisorJob())
        val sut = CameraGrabber(frames, { errors += it })
        try {
            sut.start(scope, fake)
            assertTrue(fake.firstGrabEntered.await(5, TimeUnit.SECONDS), "the grab loop never called grab()")

            // The loop is now blocked inside grab(), as it is between two camera frames.
            sut.stop()
            fake.openGate()

            assertTrue(fake.releasedLatch.await(5, TimeUnit.SECONDS), "the grabber was never released")
            assertEquals(emptyList(), fake.violations.toList())
            assertEquals(1, fake.releaseCount.get())
            assertEquals(emptyList(), errors.toList())
        } finally {
            fake.openGate()
            scope.cancel()
        }
    }

    @Test
    fun stopThenClose_releasesTheGrabberExactlyOnce() = runBlocking {
        val fake = FakeFrameGrabber().apply { openGate() }
        val sut = engine(fake)
        try {
            open(sut)
            assertTrue(fake.firstGrabEntered.await(5, TimeUnit.SECONDS), "the grab loop never called grab()")

            withTimeout(10_000) { sut.stop() }
            withTimeout(10_000) { sut.closeAndJoin() }

            assertTrue(fake.releasedLatch.await(5, TimeUnit.SECONDS), "the grabber was never released")
            assertEquals(1, fake.releaseCount.get(), "release() count")
            assertEquals(1, fake.stopCount.get(), "stop() count")
            assertEquals(emptyList(), fake.violations.toList())
        } finally {
            home.deleteRecursively()
        }
    }

    @Test
    fun stopRecording_whileRecordIsInFlight_stopsTheRecorderOnlyAfterRecordReturns() = runBlocking {
        val fake = FakeFrameGrabber().apply { openGate() }
        val recorder = FakeFrameRecorder()
        val sut = engine(fake, recorder)
        try {
            open(sut)
            assertIs<CameraKResult.Success<String>>(sut.startRecording(recordingConfig()), "startRecording")
            assertTrue(recorder.firstRecordEntered.await(5, TimeUnit.SECONDS), "nothing was recorded")

            // The encoder is busy with a frame. Stop the recording, and give a premature
            // recorder.stop() the chance to happen before the frame finishes.
            val stop = async(Dispatchers.IO) { sut.stopRecording() }
            recorder.stopCalled.await(1, TimeUnit.SECONDS)
            recorder.finishFirstRecord()

            assertIs<CameraKResult.Success<RecordedVideo>>(withTimeout(10_000) { stop.await() })
            assertEquals(emptyList(), recorder.violations.toList())
            assertEquals(1, recorder.stopCount.get(), "recorder stop() count")
            assertEquals(1, recorder.releaseCount.get(), "recorder release() count")
        } finally {
            recorder.finishFirstRecord()
            withTimeout(10_000) { sut.closeAndJoin() }
            home.deleteRecursively()
        }
    }

    @Test
    fun close_whileRecordIsInFlight_returnsAtOnceAndStopsTheRecorderOnlyAfterRecordReturns() = runBlocking {
        val fake = FakeFrameGrabber().apply { openGate() }
        val recorder = FakeFrameRecorder()
        val sut = engine(fake, recorder)
        try {
            open(sut)
            assertIs<CameraKResult.Success<String>>(sut.startRecording(recordingConfig()), "startRecording")
            assertTrue(recorder.firstRecordEntered.await(5, TimeUnit.SECONDS), "nothing was recorded")

            // close() is called from Compose's onDispose, so it must not wait for the encoder.
            sut.close()
            assertTrue(recorder.isRecordInFlight, "close() blocked until the in-flight record() returned")
            recorder.finishFirstRecord()

            assertTrue(recorder.releasedLatch.await(5, TimeUnit.SECONDS), "the recorder was never released")
            assertEquals(emptyList(), recorder.violations.toList())
            assertEquals(1, recorder.stopCount.get(), "recorder stop() count")
            assertEquals(1, recorder.releaseCount.get(), "recorder release() count")
            assertTrue(fake.releasedLatch.await(5, TimeUnit.SECONDS), "the grabber was never released")
        } finally {
            recorder.finishFirstRecord()
            withTimeout(10_000) { sut.closeAndJoin() }
            home.deleteRecursively()
        }
    }

    @Test
    fun stopThenStart_runsOneGrabLoopAtATimeOnTheSameGrabber() = runBlocking {
        val fake = FakeFrameGrabber()
        val sut = engine(fake)
        try {
            fake.allowGrabs(1)
            open(sut)
            assertTrue(fake.firstGrabEntered.await(5, TimeUnit.SECONDS), "the grab loop never called grab()")

            // The first loop is inside grab(). A restart must wait for it to leave and release the
            // grabber before the next loop opens it again.
            val restart = async(Dispatchers.IO) {
                sut.stop()
                sut.start(desired)
            }
            fake.concurrentGrabEntered.await(1, TimeUnit.SECONDS)
            fake.openGate()

            assertEquals(CameraKResult.Success(Unit), withTimeout(10_000) { restart.await() }, "the restart")
            assertTrue(fake.grabbedAfterRestart.await(5, TimeUnit.SECONDS), "the restarted session never grabbed")
            withTimeout(10_000) { sut.closeAndJoin() }
            assertTrue(fake.awaitReleases(2, 5), "each run should release the grabber once")
            assertEquals(1, fake.maxConcurrentGrabs.get(), "grab() calls that overlapped")
            assertEquals(emptyList(), fake.violations.toList())
        } finally {
            fake.openGate()
            withTimeout(10_000) { sut.closeAndJoin() }
            home.deleteRecursively()
        }
    }

    @Test
    fun startRecording_whenTheRecorderFailsToStart_releasesTheRecorderAndFails() = runBlocking {
        val fake = FakeFrameGrabber().apply { openGate() }
        val recorder = FakeFrameRecorder(startFailure = FrameRecorder.Exception("could not open the codec"))
        val sut = engine(fake, recorder)
        try {
            open(sut)

            assertIs<CameraKResult.Failure>(sut.startRecording(recordingConfig()), "a start whose recorder failed")
            assertEquals(1, recorder.releaseCount.get(), "recorder release() count")
            assertEquals(0, recorder.recordCount.get(), "frames recorded by a recorder that never started")
        } finally {
            withTimeout(10_000) { sut.closeAndJoin() }
            home.deleteRecursively()
        }
    }

    @Test
    fun startRecording_whileRecording_isBusyAndTheFirstRecordingStillStopsCleanly() = runBlocking {
        val fake = FakeFrameGrabber().apply { openGate() }
        val first = FakeFrameRecorder().apply { finishFirstRecord() }
        val second = FakeFrameRecorder().apply { finishFirstRecord() }
        val recorders = ArrayDeque(listOf(first, second))
        val sut = engine(fake) { recorders.removeFirst() }
        try {
            open(sut)
            assertIs<CameraKResult.Success<String>>(sut.startRecording(recordingConfig()), "startRecording")
            assertTrue(first.firstRecordEntered.await(5, TimeUnit.SECONDS), "nothing was recorded")

            val busy =
                assertIs<CameraKError.Busy>(
                    assertIs<CameraKResult.Failure>(sut.startRecording(recordingConfig())).error,
                )
            assertEquals(CameraOperation.RECORD_START, busy.operation)
            assertEquals(1, recorders.size, "a second recorder was opened for the refused start")

            assertIs<CameraKResult.Success<RecordedVideo>>(withTimeout(10_000) { sut.stopRecording() })
            assertEquals(1, first.stopCount.get(), "first recorder stop() count")
            assertEquals(1, first.releaseCount.get(), "first recorder release() count")
            assertEquals(emptyList(), first.violations.toList())
        } finally {
            withTimeout(10_000) { sut.closeAndJoin() }
            home.deleteRecursively()
        }
    }
}
