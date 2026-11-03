@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.engine

import androidx.camera.core.CameraState
import androidx.camera.core.TorchState
import com.kashif.cameraK.controller.AndroidCameraEngine
import com.kashif.cameraK.engine.contract.BoundSettings
import com.kashif.cameraK.engine.contract.HardwareChange
import com.kashif.cameraK.engine.contract.PreOpenInterruptionDriver
import com.kashif.cameraK.engine.contract.ThrowingCallbackDriver
import com.kashif.cameraK.enums.TorchMode
import com.kashif.cameraK.error.InterruptionReason
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration.Companion.milliseconds

/**
 * The contract kit's driver for [AndroidCameraEngine], over [FakeAndroidPlatform]: two cameras with
 * no flash unit and a fixed zoom until A2. Every report is a CameraX camera state on the bound
 * camera: an open is OPEN, an interruption a recoverable error, a failed open a critical one, and a
 * runtime failure a recoverable error CameraX does not recover from within the engine's
 * [AndroidCameraEngine.RECOVERY_TIMEOUT].
 */
internal class AndroidEngineDriver :
    ThrowingCallbackDriver,
    PreOpenInterruptionDriver {
    private var platform = FakeAndroidPlatform()
    private var scheduler: TestCoroutineScheduler? = null
    private var throwNext = false

    override fun runCase(body: suspend TestScope.() -> Unit): TestResult = runTest {
        scheduler = testScheduler
        body()
    }

    override fun createEngine(config: CameraConfiguration): AndroidCameraEngine {
        platform = FakeAndroidPlatform()
        return platform.engine(checkNotNull(scheduler) { "createEngine outside runCase" }, config)
    }

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
        change.torchMode?.let { camera.torchState.value = if (it == TorchMode.OFF) TorchState.OFF else TorchState.ON }
        change.zoomRatio?.let { error("the fake cameras have a fixed zoom until A2") }
        // A flash report is the ImageCapture's own mode, which the engine reads back itself.
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
