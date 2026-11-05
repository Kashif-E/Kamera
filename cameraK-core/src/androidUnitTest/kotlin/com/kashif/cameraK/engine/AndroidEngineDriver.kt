@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.engine

import androidx.camera.core.CameraState
import com.kashif.cameraK.controller.AndroidCameraEngine
import com.kashif.cameraK.engine.contract.BoundSettings
import com.kashif.cameraK.engine.contract.CaptureDriver
import com.kashif.cameraK.engine.contract.HardwareChange
import com.kashif.cameraK.engine.contract.PreOpenInterruptionDriver
import com.kashif.cameraK.engine.contract.ReadBackFlashDriver
import com.kashif.cameraK.engine.contract.RecordingDriver
import com.kashif.cameraK.engine.contract.ThrowingCallbackDriver
import com.kashif.cameraK.engine.contract.UnsupportedCase
import com.kashif.cameraK.engine.contract.UnsupportedConfigurationDriver
import com.kashif.cameraK.enums.ImageFormat
import com.kashif.cameraK.enums.TorchMode
import com.kashif.cameraK.error.CameraFeature
import com.kashif.cameraK.error.InterruptionReason
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration.Companion.milliseconds

/**
 * The contract kit's driver for [AndroidCameraEngine], over [FakeAndroidPlatform]: a back and a front
 * camera, each with a flash unit and a zoom range, whose torch and zoom reports wait for
 * [changeHardware]. Every state report is a CameraX camera state on the bound camera: an open is
 * OPEN, an interruption a recoverable error, a failed open a critical one, and a runtime failure a
 * recoverable error CameraX does not recover from within the engine's
 * [AndroidCameraEngine.RECOVERY_TIMEOUT]. Flash has no CameraX observer: the engine reads the bound
 * ImageCapture's mode back after each set ([ReadBackFlashDriver]). A recording's Start and Finalize
 * are CameraX's events on the newest recording, a capture waits until its saved callback is
 * delivered, and HEIC is the configuration Android cannot honour.
 */
internal class AndroidEngineDriver :
    ThrowingCallbackDriver,
    PreOpenInterruptionDriver,
    ReadBackFlashDriver,
    RecordingDriver,
    CaptureDriver,
    UnsupportedConfigurationDriver {
    private var platform = newPlatform()
    private var scheduler: TestCoroutineScheduler? = null
    private var throwNext = false

    override fun runCase(body: suspend TestScope.() -> Unit): TestResult = runTest {
        scheduler = testScheduler
        body()
    }

    override fun createEngine(config: CameraConfiguration): AndroidCameraEngine {
        platform = newPlatform()
        platform.backend.holdRecordingStart = true
        return platform.engine(checkNotNull(scheduler) { "createEngine outside runCase" }, config)
    }

    override fun deliverRecordingStarted() = platform.backend.recordings.last().deliverStarted()

    override fun deliverRecordingFinalized() = platform.backend.recordings.last().finalize()

    // Every capture of the fake waits for its saved callback; the hold is that wait.
    override fun holdNextCapture() = Unit

    override fun releaseCapture() = platform.backend.captures.last().deliverSaved(byteArrayOf(1, 2, 3))

    override fun unsupportedConfiguration() =
        UnsupportedCase(CameraConfiguration { imageFormat = ImageFormat.HEIC }, CameraFeature.IMAGE_FORMAT)

    override fun holdOpen() {
        platform.backend.binding.holdNextOpen = true
    }

    override fun deliverOpen() = report(CameraState.create(CameraState.Type.OPEN))

    override fun failOpen() = report(
        CameraState.create(
            CameraState.Type.CLOSING,
            CameraState.StateError.create(CameraState.ERROR_CAMERA_FATAL_ERROR),
        ),
    )

    override fun interrupt(reason: InterruptionReason) {
        val code = if (reason == InterruptionReason.IN_USE_BY_ANOTHER_CLIENT) {
            CameraState.ERROR_CAMERA_IN_USE
        } else {
            CameraState.ERROR_OTHER_RECOVERABLE_ERROR
        }
        report(CameraState.create(CameraState.Type.OPENING, CameraState.StateError.create(code)))
    }

    override fun endInterruption() = deliverOpen()

    override fun fail() {
        report(
            CameraState.create(
                CameraState.Type.OPENING,
                CameraState.StateError.create(CameraState.ERROR_OTHER_RECOVERABLE_ERROR),
            ),
        )
        checkNotNull(scheduler).advanceTimeBy(AndroidCameraEngine.RECOVERY_TIMEOUT + 1.milliseconds)
    }

    override fun changeHardware(change: HardwareChange) {
        val camera = platform.camera ?: return
        change.torchMode?.let { camera.reportTorch(it != TorchMode.OFF) }
        change.zoomRatio?.let { camera.reportZoom(it) }
        // Flash is never reported: the engine reads the ImageCapture's mode back after each set.
    }

    override fun boundSettings(): BoundSettings = BoundSettings(
        flashMode = platform.boundFlashMode(),
        torchMode = platform.boundTorchMode(),
        zoomRatio = platform.boundZoomRatio(),
        cameraId = platform.camera?.id,
    )

    override fun releaseCount(): Int = platform.backend.binding.releaseCount

    override suspend fun settle() = platform.settle(checkNotNull(scheduler))

    override fun throwFromNextCallback() {
        throwNext = true
    }

    private fun newPlatform() = FakeAndroidPlatform(FakeAndroidPlatform.CONTRACT_CAMERAS, holdReports = true)

    private fun report(state: CameraState) {
        val camera = platform.camera ?: return
        if (throwNext) {
            throwNext = false
            camera.cameraState.value = ThrowingCameraState
        } else {
            camera.report(state)
        }
    }

    // A camera state whose every read throws, so the engine's observer throws inside its callback boundary.
    private object ThrowingCameraState : CameraState() {
        override fun getType(): Type = throw IllegalStateException("scripted camera state failure")

        override fun getError(): StateError = throw IllegalStateException("scripted camera state failure")
    }
}
