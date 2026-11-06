@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.capabilities.LensInfo
import com.kashif.cameraK.capture.CapturedImage
import com.kashif.cameraK.engine.CameraSelection
import com.kashif.cameraK.engine.EngineState
import com.kashif.cameraK.engine.desktopEngine
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.platform.CameraKPlatformContext
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.bytedeco.javacv.FrameGrabber
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The engine owns its session: a stop or close that follows a start always reaches the camera, a
 * capture right after Open gets a frame, a closed engine never opens the camera again, and an
 * engine on a camera another engine is releasing opens it only once it is released. Over the rig's
 * scripted camera, so nothing waits on real time.
 */
class DesktopSessionOwnershipTest {
    @Test
    fun stop_rightAfterStart_leavesTheCameraClosed() = runTest { assertCameraClosedAfter { it.stop() } }

    @Test
    fun close_rightAfterStart_leavesTheCameraClosed() = runTest { assertCameraClosedAfter { it.close() } }

    private suspend fun TestScope.assertCameraClosedAfter(end: suspend (DesktopCameraEngine) -> Unit) {
        val rig = DesktopEngineRig(testScheduler)
        val steps = DesktopRecordingSteps(this, rig)
        val sut = rig.engine()
        try {
            // The start is under way, as a stop or close right after it finds it.
            val start = async(start = CoroutineStart.UNDISPATCHED) { sut.start(steps.desired) }
            end(sut)
            rig.settle()
            start.await()

            assertEquals(
                rig.camera.startCount,
                rig.camera.releaseCount,
                "the camera is still open after the session ended: started ${rig.camera.startCount} times, " +
                    "released ${rig.camera.releaseCount} times",
            )
        } finally {
            sut.close()
            rig.settle()
        }
    }

    @Test
    fun start_afterClose_neverOpensTheCamera() = runTest {
        val rig = DesktopEngineRig(testScheduler)
        val steps = DesktopRecordingSteps(this, rig)
        val sut = rig.engine()
        try {
            steps.open(sut)
            sut.close()
            rig.settle()
            assertEquals(1, rig.camera.releaseCount, "close did not release the camera")

            val again = async { sut.start(steps.desired) }
            rig.settle()

            assertEquals(CameraKResult.Failure(CameraKError.Closed), again.await(), "a start after close")
            assertEquals(1, rig.camera.startCount, "a closed engine opened the camera again")
            assertEquals(EngineState.Closed, sut.state.value)
        } finally {
            sut.close()
            rig.settle()
        }
    }

    @Test
    fun aCapture_rightAfterOpen_getsTheCamerasNextFrame() = runTest {
        val rig = DesktopEngineRig(testScheduler)
        val steps = DesktopRecordingSteps(this, rig)
        val sut = rig.engine()
        try {
            steps.open(sut)
            val capture = async { sut.capture() }
            rig.settle()
            rig.camera.deliverFrame()
            rig.settle()

            assertIs<CameraKResult.Success<CapturedImage>>(capture.await(), "capture right after Open")
        } finally {
            sut.close()
            rig.settle()
            rig.home.deleteRecursively()
        }
    }

    @Test
    fun closeAndJoin_returnsOnlyOnceTheCameraIsReleased() = runTest {
        val rig = DesktopEngineRig(testScheduler)
        val steps = DesktopRecordingSteps(this, rig)
        val sut = rig.engine()
        steps.open(sut)
        var releasesWhenItReturned = -1

        val closing = async {
            sut.closeAndJoin()
            releasesWhenItReturned = rig.camera.releaseCount
        }
        rig.settle()
        closing.await()

        assertEquals(1, releasesWhenItReturned, "camera releases when closeAndJoin() returned")
    }

    @Test
    fun aNewEngine_rightAfterClose_opensTheCameraOnlyOnceTheOldOneReleasedIt() = runTest {
        val rig = DesktopEngineRig(testScheduler)
        val steps = DesktopRecordingSteps(this, rig)
        // One device: both engines open the same camera; each open records how often the last one was released.
        val opens = CopyOnWriteArrayList<ScriptedFrameGrabber>()
        val releasesAtOpen = CopyOnWriteArrayList<Int>()
        val devices = object : DesktopDevices {
            override fun list(): List<LensInfo> = listOf(desktopLensInfo())

            override fun open(id: String, size: Pair<Int, Int>?): FrameGrabber = rig.newCamera().also {
                releasesAtOpen += opens.lastOrNull()?.releaseCount ?: 0
                opens += it
            }
        }
        fun engine() =
            desktopEngine(CameraConfiguration(), CameraKPlatformContext(), rig.environment(), devices = devices)
        val old = engine()
        val new = engine()
        val desired = DesktopRecordingSteps.desiredOn(CameraSelection.ById(desktopLensInfo().id))
        try {
            runCurrent()
            steps.open(old, desired)
            old.close()
            val start = async { new.start(desired) }
            rig.settle()

            assertEquals(CameraKResult.Success(Unit), start.await(), "the new engine's start")
            assertEquals(listOf(0, 1), releasesAtOpen.toList(), "the old camera's releases at each open")
        } finally {
            old.close()
            new.close()
            rig.settle()
        }
    }
}
