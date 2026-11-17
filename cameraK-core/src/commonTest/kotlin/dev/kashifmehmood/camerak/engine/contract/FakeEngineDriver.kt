@file:OptIn(CameraKEngineApi::class)

package dev.kashifmehmood.camerak.engine.contract

import dev.kashifmehmood.camerak.capabilities.CameraCapabilities
import dev.kashifmehmood.camerak.engine.CameraEngine
import dev.kashifmehmood.camerak.enums.ImageFormat
import dev.kashifmehmood.camerak.error.CameraFeature
import dev.kashifmehmood.camerak.error.CameraKError
import dev.kashifmehmood.camerak.error.InterruptionReason
import dev.kashifmehmood.camerak.state.CameraConfiguration
import dev.kashifmehmood.camerak.testing.FakeCameraEngine
import dev.kashifmehmood.camerak.testing.FakeCapabilities
import dev.kashifmehmood.camerak.utils.CameraKEngineApi
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
    PreOpenInterruptionDriver,
    RecordingDriver,
    CaptureDriver,
    UnsupportedConfigurationDriver {
    /** The engine [createEngine] built last. */
    protected lateinit var fake: FakeCameraEngine

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
        change.flashMode?.let(fake::reportFlash)
        change.exposureCompensationIndex?.let(fake::reportExposureIndex)
    }

    override fun boundSettings(): BoundSettings = BoundSettings(
        fake.appliedFlashMode,
        fake.appliedTorchMode,
        fake.appliedZoomRatio,
        fake.appliedCameraId,
        fake.appliedExposureCompensationIndex,
    )

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
        UnsupportedCase(CameraConfiguration { imageFormat = ImageFormat.HEIC }, CameraFeature.IMAGE_FORMAT)
}
