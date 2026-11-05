@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.capture.RecordedVideo
import com.kashif.cameraK.engine.EngineRecordingEvent
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.error.CameraOperation
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.bytedeco.javacv.FrameRecorder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The abandoned-start rule of `CameraEngine.recordingEvents` (w6 decisions, signed off from H3'),
 * on the desktop engine: the holder stops a start it gave up on, and the stop's answer says whether
 * a Finalized is owed. `Failure(InvalidState(RECORD_STOP))` promises that nothing reached the
 * platform and that no `Started` or `Finalized` follows; any other answer is that recording's one
 * `Finalized`. A stop can land at any point of the start: while the recorder is created, after the
 * recording is listed but before its thread ran, or while the recorder's `start()` runs.
 */
class DesktopAbandonedStartTest {
    @Test
    fun aStopWhileTheStartCreatesItsRecorderIsInvalidStateAndTheStartBeginsNothing() = runTest {
        val rig = DesktopEngineRig(testScheduler)
        val steps = DesktopRecordingSteps(this, rig)
        lateinit var sut: DesktopCameraEngine
        var stop: Deferred<CameraKResult<RecordedVideo>>? = null
        rig.recorderScript = { path ->
            if (stop == null) stop = async(start = CoroutineStart.UNDISPATCHED) { sut.stopRecording() }
            ScriptedFrameRecorder(path, rig.workers)
        }
        sut = rig.engine()
        try {
            steps.open(sut)
            val events = steps.events(sut)
            steps.at(1_000)
            val start = async { sut.startRecording(steps.video()) }
            rig.settle()
            steps.frameAt(1_040)

            assertNothingBegan(assertNotNull(stop, "the stop during the start").await())
            val refused = assertIs<CameraKResult.Failure>(start.await(), "the abandoned start")
            assertEquals(CameraKError.Closed, refused.error, "the abandoned start's error")
            assertEquals(emptyList(), events.toList(), "events of the abandoned start")
            val abandoned = rig.recorders.single()
            assertNull(abandoned.sizeAtStart, "the abandoned start's recorder was started")
            assertEquals(1, abandoned.releaseCount.get(), "releases of the abandoned start's recorder")
            assertFalse(abandoned.file.exists(), "the abandoned start's file was kept")

            nothingIsLeftRecording(rig, steps, sut)
        } finally {
            sut.close()
            rig.settle()
            rig.home.deleteRecursively()
        }
    }

    @Test
    fun aStopBeforeTheRecordingsThreadRanIsInvalidStateAndNoFinalizedFollows() = runTest {
        val rig = DesktopEngineRig(testScheduler)
        val steps = DesktopRecordingSteps(this, rig)
        val sut = rig.engine()
        try {
            steps.open(sut)
            val events = steps.events(sut)
            val gate = rig.workers.holdNextWorker(RECORDER_THREAD)
            steps.at(1_000)
            val start = async { sut.startRecording(steps.video()) }
            testScheduler.runCurrent()
            assertTrue(gate.taken, "the start readied its recording on a held thread")
            val stop = async { sut.stopRecording() }
            testScheduler.runCurrent()
            gate.open()
            rig.settle()

            assertNothingBegan(stop.await())
            assertIs<CameraKResult.Failure>(start.await(), "the start stopped before its thread ran")
            assertEquals(emptyList(), events.toList(), "events of the start stopped before its thread ran")
            val abandoned = rig.recorders.single()
            assertNull(abandoned.sizeAtStart, "the recorder of a recording whose thread never ran was started")
            assertFalse(abandoned.file.exists(), "the file of a recording whose thread never ran was kept")

            nothingIsLeftRecording(rig, steps, sut)
        } finally {
            sut.close()
            rig.settle()
            rig.home.deleteRecursively()
        }
    }

    @Test
    fun aStopDuringARecorderStartThatFailsIsInvalidStateAndNoFinalizedFollows() = runTest {
        val rig = DesktopEngineRig(testScheduler)
        val steps = DesktopRecordingSteps(this, rig)
        val starting = HeldRecorderStart()
        rig.recorderScript = { path ->
            ScriptedFrameRecorder(
                path,
                rig.workers,
                startFailure = FrameRecorder.Exception("encoder refused"),
                onStart = starting::hold,
            )
        }
        val sut = rig.engine()
        try {
            steps.open(sut)
            val events = steps.events(sut)
            steps.at(1_000)
            val start = async { sut.startRecording(steps.video()) }
            testScheduler.runCurrent()
            starting.awaitEntered()
            val stop = async { sut.stopRecording() }
            testScheduler.runCurrent()
            starting.release()
            rig.settle()

            assertNothingBegan(stop.await())
            assertIs<CameraKResult.Failure>(start.await(), "the start whose recorder failed")
            assertEquals(emptyList(), events.toList(), "events of a start whose recorder failed")

            rig.recorderScript = { ScriptedFrameRecorder(it, rig.workers) }
            nothingIsLeftRecording(rig, steps, sut)
        } finally {
            sut.close()
            rig.settle()
            rig.home.deleteRecursively()
        }
    }

    // Characterization: passes before the fix. It keeps the rule from over-reaching: the mutation
    // "a stop that lands while the start is in flight answers InvalidState" fails it.
    @Test
    fun aStopDuringARecorderStartThatSucceedsIsThatRecordingsOneFinalized() = runTest {
        val rig = DesktopEngineRig(testScheduler)
        val steps = DesktopRecordingSteps(this, rig)
        val starting = HeldRecorderStart()
        rig.recorderScript = { path -> ScriptedFrameRecorder(path, rig.workers, onStart = starting::hold) }
        val sut = rig.engine()
        try {
            steps.open(sut)
            val events = steps.events(sut)
            steps.at(1_000)
            val start = async { sut.startRecording(steps.video()) }
            testScheduler.runCurrent()
            starting.awaitEntered()
            val stop = async { sut.stopRecording() }
            testScheduler.runCurrent()
            starting.release()
            rig.settle()

            val path = assertIs<CameraKResult.Success<String>>(start.await(), "the start whose recorder started").value
            val stopped = stop.await()
            val stopError = (stopped as? CameraKResult.Failure)?.error
            assertFalse(stopError is CameraKError.InvalidState, "a stop of a started recording: $stopped")
            assertEquals(
                listOf<Any>(EngineRecordingEvent.Started(path, hasAudio = false)),
                events.filterIsInstance<EngineRecordingEvent.Started>(),
                "Started of the recording the stop met",
            )
            val finalized = events.filterIsInstance<EngineRecordingEvent.Finalized>()
            assertEquals(1, finalized.size, "Finalized of the recording the stop met: $events")
            assertEquals(stopped, finalized.single().result, "the stop's answer is the recording's Finalized")
        } finally {
            sut.close()
            rig.settle()
            rig.home.deleteRecursively()
        }
    }

    private fun assertNothingBegan(stop: CameraKResult<RecordedVideo>) {
        val refused = assertIs<CameraKResult.Failure>(stop, "the stop during the start")
        val error = assertIs<CameraKError.InvalidState>(refused.error, "the stop during the start's error")
        assertEquals(CameraOperation.RECORD_STOP, error.operation)
    }

    // The next start begins and ends as any other: no recording was left running, unowned.
    private suspend fun nothingIsLeftRecording(
        rig: DesktopEngineRig,
        steps: DesktopRecordingSteps,
        sut: DesktopCameraEngine,
    ) {
        steps.at(2_000)
        val next = steps.startRecording(sut)
        steps.frameAt(2_040)
        val video = assertIs<CameraKResult.Success<RecordedVideo>>(steps.stopRecording(sut), "the next recording")
        assertEquals(next, video.value.path)
        assertTrue(rig.recorders.last().file.exists(), "the next recording's file")
    }

    /** Holds a recorder's `start()` on the recording's thread until [release]. */
    private class HeldRecorderStart {
        private val entered = CountDownLatch(1)
        private val released = CountDownLatch(1)

        fun hold() {
            entered.countDown()
            released.await()
        }

        fun awaitEntered() {
            assertTrue(entered.await(WAIT_SECONDS, TimeUnit.SECONDS), "the recorder's start never ran")
        }

        fun release() = released.countDown()
    }

    private companion object {
        // DesktopCameraEngine's name for a recording's thread.
        const val RECORDER_THREAD = "CameraK-recorder"
        const val WAIT_SECONDS = 10L
    }
}
