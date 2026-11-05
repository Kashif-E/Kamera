@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.utils.CameraKEngineApi
import com.kashif.cameraK.video.VideoCaptureResult
import com.kashif.cameraK.video.VideoConfiguration
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The 1.x facade's stop over the desktop engine keeps 1.x's rule, as Android's and iOS's facades
 * do: a recording the facade started that ended by itself (a limit, an error, a camera cut) is the
 * next facade stop's result, claimed once. With nothing to claim the stop is "No active recording".
 */
class DesktopFacadeRecordingOutcomeTest {
    @Test
    fun aRecordingTheEngineEndedAtItsLimitIsTheNextFacadeStopsResult() = runTest {
        val rig = DesktopEngineRig(testScheduler)
        val steps = DesktopRecordingSteps(this, rig)
        val sut = rig.engine()
        val facade = sut.legacyController
        try {
            steps.open(sut)
            steps.at(1_000)
            val path = facadeStart(facade, rig, steps.video(maxDurationMillis = 100))
            steps.frameAt(1_040)
            steps.frameAt(1_080)
            // 120 ms recorded: past the limit, so the engine ends the recording before any stop.
            steps.frameAt(1_120)
            assertEquals(1, rig.recorders.single().releaseCount.get(), "the limit released the recorder")

            assertEquals(VideoCaptureResult.Success(path, 80), facadeStop(facade, rig), "the first stop")
            assertTrue(File(path).exists(), "the file of a recording that reached its limit")
            val again = assertIs<VideoCaptureResult.Error>(facadeStop(facade, rig), "a second stop")
            assertEquals("No active recording", again.exception.message)
        } finally {
            facade.cleanup()
            rig.settle()
        }
    }

    @Test
    fun aFacadeStopWithNothingRecordedIsNoActiveRecording() = runTest {
        val rig = DesktopEngineRig(testScheduler)
        val steps = DesktopRecordingSteps(this, rig)
        val sut = rig.engine()
        val facade = sut.legacyController
        try {
            steps.open(sut)
            val stop = assertIs<VideoCaptureResult.Error>(facadeStop(facade, rig))
            assertEquals("No active recording", stop.exception.message)
        } finally {
            facade.cleanup()
            rig.settle()
        }
    }

    // Characterization: passes before the fix, which claims nothing. The mutation "a new start keeps
    // the earlier unclaimed outcome" fails it.
    @Test
    fun aNewFacadeStartDropsTheUnclaimedOutcome() = runTest {
        val rig = DesktopEngineRig(testScheduler)
        val steps = DesktopRecordingSteps(this, rig)
        val sut = rig.engine()
        val facade = sut.legacyController
        try {
            steps.open(sut)
            steps.at(1_000)
            facadeStart(facade, rig, steps.video(maxDurationMillis = 100))
            steps.frameAt(1_040)
            steps.frameAt(1_120)
            steps.at(2_000)
            val second = facadeStart(facade, rig, steps.video())
            steps.frameAt(2_040)
            steps.frameAt(2_080)

            assertEquals(VideoCaptureResult.Success(second, 80), facadeStop(facade, rig), "the second recording")
            assertIs<VideoCaptureResult.Error>(
                facadeStop(facade, rig),
                "the first recording's outcome after a new start",
            )
        } finally {
            facade.cleanup()
            rig.settle()
        }
    }

    // A recording the holder started through the engine is the holder's to report.
    @Test
    fun anEngineRecordingTheFacadeDidNotStartIsNotTheFacadeStopsResult() = runTest {
        val rig = DesktopEngineRig(testScheduler)
        val steps = DesktopRecordingSteps(this, rig)
        val sut = rig.engine()
        val facade = sut.legacyController
        try {
            steps.open(sut)
            steps.at(1_000)
            steps.startRecording(sut, steps.video(maxDurationMillis = 100))
            steps.frameAt(1_040)
            steps.frameAt(1_120)
            assertEquals(1, rig.recorders.single().releaseCount.get(), "the limit released the recorder")

            val stop = assertIs<VideoCaptureResult.Error>(facadeStop(facade, rig), "the facade's stop")
            assertEquals("No active recording", stop.exception.message)
        } finally {
            facade.cleanup()
            rig.settle()
        }
    }

    private suspend fun TestScope.facadeStart(
        facade: CameraController,
        rig: DesktopEngineRig,
        video: VideoConfiguration,
    ): String {
        val start = async { facade.startRecording(video) }
        rig.settle()
        return start.await()
    }

    private suspend fun TestScope.facadeStop(facade: CameraController, rig: DesktopEngineRig): VideoCaptureResult {
        val stop = async { facade.stopRecording() }
        rig.settle()
        return stop.await()
    }
}
