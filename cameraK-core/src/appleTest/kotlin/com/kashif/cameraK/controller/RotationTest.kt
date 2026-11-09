@file:OptIn(CameraKEngineApi::class, ExperimentalForeignApi::class, InternalCameraKApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.capture.CapturedImage
import com.kashif.cameraK.engine.CameraEngine
import com.kashif.cameraK.engine.CameraSelection
import com.kashif.cameraK.engine.contract.AppleEngineDriver
import com.kashif.cameraK.engine.layOutPreviewLayer
import com.kashif.cameraK.enums.DeviceOrientation
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.utils.CameraKEngineApi
import com.kashif.cameraK.utils.InternalCameraKApi
import kotlinx.atomicfu.atomic
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.test.TestScope
import platform.AVFoundation.AVCaptureVideoOrientationLandscapeRight
import platform.AVFoundation.AVCaptureVideoPreviewLayer
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSThread
import platform.UIKit.UIDeviceOrientation
import platform.UIKit.UIInterfaceOrientationLandscapeRight
import platform.UIKit.UIInterfaceOrientationPortrait
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Rotation from iOS 17 is `AVCaptureDevice.RotationCoordinator`'s (design §2.4, §12.3 I3'), over the
 * engine's [RotationSourceFactory] seam. Photos and movies take the engine's target orientation when
 * one is set, otherwise the coordinator's horizon-level capture angle. The preview layer takes the
 * coordinator's preview angle as the coordinator reports it, so a 180° turn of the device, which
 * brings no layout pass, still turns the layer. Below iOS 17 photos and movies keep the device's
 * orientation and the preview the interface's.
 */
class RotationTest {
    private val written = mutableListOf<String>()

    @AfterTest
    fun tearDown() = removeFiles(written)

    private suspend fun TestScope.captureRotation(driver: AppleEngineDriver, engine: CameraEngine): VideoRotation {
        val (capture, request) = captureReachingThePhotoOutput(driver, engine)
        val result = deliverPhoto(driver, request, capture, orientedJpeg(width = 4, height = 3, orientation = 1))
        written += assertIs<CameraKResult.Success<CapturedImage>>(result, "the capture failed: $result").value.path
        return request.rotation
    }

    private suspend fun TestScope.movieRotation(driver: AppleEngineDriver, engine: CameraEngine): VideoRotation {
        val recording = startEngineRecording(driver, engine).also { written += it.path }
        finishRecording(driver, recording)
        return recording.start.rotation
    }

    private fun AppleEngineDriver.lastTurnOf(layer: AVCaptureVideoPreviewLayer): VideoRotation? =
        previewTurns.lastOrNull { it.first === layer }?.second

    @Test
    fun captureTakesTheTargetElseTheCoordinatorAngleFromIos17() {
        val rotation = FakeRotationSources(captureAngle = 180.0)
        val driver = AppleEngineDriver(osVersion = 17, rotationSources = rotation)
        driver.runCase {
            val engine = driver.createEngine()
            driver.settle()
            assertEquals(CameraKResult.Success(Unit), engine.start(backCamera()))
            driver.settle()

            assertEquals(VideoRotation.Angle(180.0), captureRotation(driver, engine), "a photo without a target")
            assertEquals(VideoRotation.Angle(180.0), movieRotation(driver, engine), "a movie without a target")
            assertSame(driver.back, rotation.made.last().device, "the coordinator's camera")

            assertEquals(CameraKResult.Success(Unit), engine.setTargetOrientation(DeviceOrientation.LANDSCAPE_LEFT))
            val target = VideoRotation.Orientation(AVCaptureVideoOrientationLandscapeRight)
            assertEquals(target, captureRotation(driver, engine), "a photo with a LANDSCAPE_LEFT target")
            assertEquals(target, movieRotation(driver, engine), "a movie with a LANDSCAPE_LEFT target")

            assertEquals(CameraKResult.Success(Unit), engine.setTargetOrientation(null))
            rotation.captureAngle = 0.0
            assertEquals(
                VideoRotation.Angle(0.0),
                captureRotation(driver, engine),
                "a photo once the target is cleared",
            )
            engine.close()
        }
    }

    @Test
    fun previewTakesTheCoordinatorPreviewAngle() {
        val rotation = FakeRotationSources(previewAngle = 90.0)
        val driver = AppleEngineDriver(osVersion = 17, rotationSources = rotation)
        driver.runCase {
            val engine = driver.createEngine()
            val layer = engine.preview.createPreviewLayer()
            driver.settle()
            assertEquals(CameraKResult.Success(Unit), engine.start(backCamera()))
            driver.settle()

            assertTrue(
                rotation.made.any { it.previewLayer === layer && !it.closed },
                "no open coordinator of the camera and the preview layer",
            )
            assertEquals(VideoRotation.Angle(90.0), driver.lastTurnOf(layer), "the layer once the camera is bound")

            // Portrait to upside down: the interface turns 180° and UIKit gives the host no layout pass.
            rotation.turnPreview(270.0)
            driver.settle()
            assertEquals(VideoRotation.Angle(270.0), driver.lastTurnOf(layer), "the layer after a 180° turn")

            engine.preview.layOutPreviewLayer(layer, CGRectMake(0.0, 0.0, 360.0, 800.0), UIInterfaceOrientationPortrait)
            assertEquals(
                VideoRotation.Angle(270.0),
                driver.lastTurnOf(layer),
                "a layout pass turned the layer away from the coordinator's angle",
            )
            engine.close()
        }
    }

    /**
     * AVCaptureDeviceRotationCoordinator's init waits for the main thread. Made on the session queue it
     * deadlocked an iPhone 14 Pro (iOS 27.3, watchdog 0x8BADF00D): main was making a preview layer,
     * which waits on a session lock, while the session queue waited for main. AVCam makes it on main.
     */
    @Test
    fun theCoordinatorIsMadeOnTheMainThread() {
        val rotation = FakeRotationSources()
        val driver = AppleEngineDriver(osVersion = 17, rotationSources = rotation)
        driver.runCase {
            val engine = driver.createEngine()
            engine.preview.createPreviewLayer()
            driver.settle()
            assertEquals(CameraKResult.Success(Unit), engine.start(backCamera()))
            driver.settle()
            assertIs<CameraKResult.Success<*>>(engine.selectCamera(CameraSelection.ById(AppleEngineDriver.FRONT_ID)))
            driver.settle()
            val layer = engine.preview.createPreviewLayer()
            driver.settle()

            val made = rotation.made
            assertTrue(made.size >= 3, "a coordinator for the start, the switch and the new layer: ${made.size}")
            assertEquals(
                0,
                made.count { !it.onMainThread },
                "coordinators made off the main thread, of ${made.size}",
            )
            val open = made.filterNot { it.closed }
            assertEquals(1, open.size, "open coordinators")
            assertSame(driver.front, open.single().device, "the open coordinator's camera")
            assertSame(layer, open.single().previewLayer, "the open coordinator's layer")
            engine.close()
        }
    }

    /** Waits for [condition] without servicing main, so blocks queued on main stay queued. */
    private fun holdingMainUntil(condition: () -> Boolean) {
        var waited = 0
        while (!condition()) {
            check(waited < 1_000) { "timed out holding the main thread" }
            NSThread.sleepForTimeInterval(0.01)
            waited++
        }
    }

    @Test
    fun aSwitchAwayAndBackBeforeMainRunsLeavesTheSessionsCameraCoordinated() {
        val rotation = FakeRotationSources()
        val driver = AppleEngineDriver(osVersion = 17, rotationSources = rotation)
        driver.runCase {
            driver.createEngine()
            val engine = driver.engine
            engine.preview.createPreviewLayer()
            driver.settle()
            assertEquals(CameraKResult.Success(Unit), engine.start(backCamera()))
            driver.settle()

            val switched = atomic(0)
            engine.switchCamera(CameraSelection.ById(AppleEngineDriver.FRONT_ID)) { switched.incrementAndGet() }
            engine.switchCamera(CameraSelection.ById(AppleEngineDriver.BACK_ID)) { switched.incrementAndGet() }
            holdingMainUntil { switched.value == 2 }
            driver.settle()

            val open = rotation.made.filterNot { it.closed }
            assertEquals(1, open.size, "open coordinators")
            assertSame(driver.back, open.single().device, "the coordinator's camera, with the session on the back camera")
            engine.close()
        }
    }

    @Test
    fun aSwitchClosesThePreviousCamerasCoordinatorBeforeMainRuns() {
        val rotation = FakeRotationSources()
        val driver = AppleEngineDriver(osVersion = 17, rotationSources = rotation)
        driver.runCase {
            driver.createEngine()
            val engine = driver.engine
            engine.preview.createPreviewLayer()
            driver.settle()
            assertEquals(CameraKResult.Success(Unit), engine.start(backCamera()))
            driver.settle()
            val backCoordinator = rotation.made.single { !it.closed }

            val switched = atomic(false)
            engine.switchCamera(CameraSelection.ById(AppleEngineDriver.FRONT_ID)) { switched.value = true }
            holdingMainUntil { switched.value }
            assertTrue(
                backCoordinator.closed,
                "captures until main makes the front camera's coordinator would take the back camera's angle",
            )

            driver.settle()
            assertSame(driver.front, rotation.made.single { !it.closed }.device, "the coordinator after the switch")
            engine.close()
        }
    }

    @Test
    fun belowIos17KeepsTheDeviceAndInterfaceOrientations() {
        val rotation = FakeRotationSources()
        val driver = AppleEngineDriver(osVersion = 16, rotationSources = rotation)
        driver.deviceOrientation = UIDeviceOrientation.UIDeviceOrientationLandscapeLeft
        driver.runCase {
            val engine = driver.createEngine()
            val layer = engine.preview.createPreviewLayer()
            driver.settle()
            assertEquals(CameraKResult.Success(Unit), engine.start(backCamera()))
            driver.settle()

            // A device turned to its left shows the interface, and records, in landscape right.
            val device = VideoRotation.Orientation(AVCaptureVideoOrientationLandscapeRight)
            assertEquals(device, captureRotation(driver, engine), "a photo below iOS 17")
            assertEquals(device, movieRotation(driver, engine), "a movie below iOS 17")

            engine.preview.layOutPreviewLayer(
                layer,
                CGRectMake(0.0, 0.0, 800.0, 360.0),
                UIInterfaceOrientationLandscapeRight,
            )
            assertEquals(
                VideoRotation.Orientation(AVCaptureVideoOrientationLandscapeRight),
                driver.lastTurnOf(layer),
                "the preview below iOS 17",
            )
            assertEquals(emptyList(), rotation.made.map { it.device.uniqueId }, "coordinators made below iOS 17")
            engine.close()
        }
    }
}
