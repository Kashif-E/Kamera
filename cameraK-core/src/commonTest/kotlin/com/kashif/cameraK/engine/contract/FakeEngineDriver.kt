@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.engine.contract

import com.kashif.cameraK.capabilities.CameraCapabilities
import com.kashif.cameraK.engine.CameraEngine
import com.kashif.cameraK.enums.ImageFormat
import com.kashif.cameraK.error.CameraFeature
import com.kashif.cameraK.error.CameraKError
import com.kashif.cameraK.error.InterruptionReason
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.testing.FakeCameraEngine
import com.kashif.cameraK.testing.FakeCapabilities
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.TestCoroutineScheduler

/**
 * The contract kit's driver over [FakeCameraEngine]: every hook is one public member of the fake.
 * Recording milestones are held, so the kit drives each one as a platform would.
 */
internal open class FakeEngineDriver(
    private val capabilities: CameraCapabilities = FakeCapabilities.backAndFront(
        setOf(ImageFormat.JPEG, ImageFormat.PNG),
    ),
) : ThrowingCallbackDriver,
    RecordingDriver,
    CaptureDriver,
    UnsupportedConfigurationDriver {
    private lateinit var fake: FakeCameraEngine

    override fun createEngine(config: CameraConfiguration): CameraEngine = FakeCameraEngine(capabilities).also {
        it.configuration = config
        it.autoStartRecording = false
        it.autoFinalizeRecording = false
        fake = it
    }

    override fun holdOpen() {
        fake.autoOpen = false
    }

    override fun deliverOpen() = fake.deliverOpen()

    override fun failOpen() = fake.failOpen(CameraKError.CameraInUse())

    override fun interrupt(reason: InterruptionReason) = fake.interrupt(reason)

    override fun endInterruption() = fake.endInterruption()

    override fun fail() = fake.fail(CameraKError.Disconnected())

    override fun changeHardware(change: HardwareChange) {
        change.torchMode?.let(fake::reportTorch)
        change.zoomRatio?.let { fake.reportZoom(it, fake.zoom.value.minRatio, fake.zoom.value.maxRatio) }
    }

    override fun boundSettings(): BoundSettings =
        BoundSettings(fake.appliedFlashMode, fake.appliedTorchMode, fake.appliedZoomRatio, fake.appliedCameraId)

    override fun releaseCount(): Int = fake.releaseCount

    override suspend fun settle() {
        val scheduler =
            checkNotNull(currentCoroutineContext()[TestCoroutineScheduler]) { "settle() runs inside runTest" }
        scheduler.runCurrent()
    }

    override fun throwFromNextCallback() = fake.throwFromNextCallback()

    override fun deliverRecordingStarted() = fake.deliverRecordingStarted()

    override fun deliverRecordingFinalized() = fake.deliverRecordingFinalized()

    override fun holdNextCapture() = fake.holdNextCapture()

    override fun releaseCapture() = fake.releaseCapture()

    override fun unsupportedConfiguration(): UnsupportedCase =
        UnsupportedCase(CameraConfiguration(imageFormat = ImageFormat.HEIC), CameraFeature.IMAGE_FORMAT)
}
