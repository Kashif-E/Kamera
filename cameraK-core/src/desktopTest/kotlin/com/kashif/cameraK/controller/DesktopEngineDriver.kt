@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.capabilities.LensInfo
import com.kashif.cameraK.capabilities.capabilitiesOf
import com.kashif.cameraK.engine.CameraEngine
import com.kashif.cameraK.engine.contract.BoundSettings
import com.kashif.cameraK.engine.contract.CaptureDriver
import com.kashif.cameraK.engine.contract.HardwareChange
import com.kashif.cameraK.engine.contract.RecordingDriver
import com.kashif.cameraK.engine.contract.ThrowingCallbackDriver
import com.kashif.cameraK.engine.contract.UnsupportedCase
import com.kashif.cameraK.engine.contract.UnsupportedConfigurationDriver
import com.kashif.cameraK.enums.CameraDeviceType
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.enums.FlashMode
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
import org.bytedeco.javacv.FrameGrabber
import java.io.IOException

/**
 * The contract kit's driver over [DesktopCameraEngine] and a [ScriptedFrameGrabber]: the engine's
 * coroutines on the case's scheduler, its producer on real threads ([DesktopEngineRig]).
 *
 * The platform's events are the camera's: an open is the first frame, an interruption a failed
 * `grab()` (the engine reopens; the camera then holds its frames until [endInterruption]), a runtime
 * failure a missing native, which no reopen fixes. An event for a camera no grab loop owns (after
 * stop, close or a failure) goes to the last grab loop's listener, as a late callback of a dead
 * generation would arrive; the engine must ignore it.
 *
 * The engine enumerates two OFF-only webcams with a fixed zoom, both opening the one scripted
 * camera, so the kit's camera-switch steps run on desktop too.
 *
 * Recording: the platform's start is the recorder's `start()`, which returns on its own, so the
 * kit's start event lets one camera frame through to give the file a frame; the platform's
 * finalize is the recorder's `stop()`, which waits for the kit's finalize event. A capture's
 * platform callback is the camera's next frame, which the camera holds after its first. The
 * configuration desktop cannot honour is HEIC (§6.1). The engine's clock moves one frame interval
 * per read, so every frame it stamps is a frame of its own.
 *
 * Not a `PreOpenInterruptionDriver`: desktop has no interruption before Open (w3 decisions §6).
 */
internal class DesktopEngineDriver :
    ThrowingCallbackDriver,
    RecordingDriver,
    CaptureDriver,
    UnsupportedConfigurationDriver {
    private lateinit var rig: DesktopEngineRig
    private var lastListener: GrabLoopListener? = null
    private var throwOnNextFrame = false

    // The engine's coroutines get a scheduler of their own, which only settle() runs. On the case's
    // scheduler, runTest would skip virtual time whenever a case suspends on the producer's real
    // threads (a selectCamera waiting for the new device's first frame), and the engine's no-frame
    // watchdog would fire in the middle of the case.
    override fun runCase(body: suspend TestScope.() -> Unit): TestResult = runTest {
        rig = DesktopEngineRig(TestCoroutineScheduler(), caseScheduler = testScheduler)
        body()
    }

    override fun createEngine(config: CameraConfiguration): CameraEngine {
        rig.recorderScript = { path -> ScriptedFrameRecorder(path, rig.workers).apply { holdStop = true } }
        return rig.engine(
            config,
            enumerate = { capabilitiesOf(listOf(webcam("cam-a"), webcam("cam-b"))) },
            wrap = { listener -> ScriptedListener(listener).also { lastListener = it } },
            nanoClock = { rig.clock.addAndGet(FRAME_NANOS) },
        )
    }

    override fun deliverRecordingStarted() = rig.camera.deliverFrame()

    override fun deliverRecordingFinalized() {
        rig.recorders.lastOrNull()?.finishStop()
    }

    // The camera holds every frame after its first until the kit lets one through.
    override fun holdNextCapture() = Unit

    override fun releaseCapture() = deliverOpen()

    override fun unsupportedConfiguration() =
        UnsupportedCase(CameraConfiguration { imageFormat = ImageFormat.HEIC }, CameraFeature.IMAGE_FORMAT)

    override fun holdOpen() {
        rig.camera.holdOpen = true
    }

    override fun deliverOpen() = deliver(live = { rig.camera.deliverFrame() }, dead = { it.onFrame(lateFrame()) })

    override fun failOpen() = fail(FrameGrabber.Exception("Could not grab a frame: Input/output error"))

    override fun interrupt(reason: InterruptionReason) {
        if (rig.camera.isOpen) rig.camera.holdOpen = true
        fail(IOException("the camera stopped delivering frames"))
    }

    override fun endInterruption() {
        rig.camera.holdOpen = false
        deliverOpen()
    }

    override fun fail() = fail(NoClassDefFoundError("Could not initialize class org.bytedeco.ffmpeg.global.avutil"))

    // Desktop reports no flash, torch or zoom of its own: one OFF-only camera with a fixed zoom.
    override fun changeHardware(change: HardwareChange) = Unit

    override fun boundSettings(): BoundSettings = BoundSettings(FlashMode.OFF, TorchMode.OFF, 1f, rig.openedDeviceId)

    override fun releaseCount(): Int = rig.camera.releaseCount

    override suspend fun settle() = rig.settle()

    override fun throwFromNextCallback() {
        throwOnNextFrame = true
    }

    private fun fail(failure: Throwable) = deliver(
        live = { rig.camera.failGrab(failure) },
        dead = { it.onFailure(failure, opened = true) },
    )

    private fun deliver(live: () -> Unit, dead: (GrabLoopListener) -> Unit) {
        if (rig.camera.isOpen) live() else lastListener?.let(dead)
    }

    // A stale run's frame: the engine drops it without taking a lease.
    private fun lateFrame() = LeasedFrame(64, 48)

    private fun webcam(id: String) =
        LensInfo(id, CameraDeviceType.DEFAULT, CameraLens.BACK, 1f, 1f, hasFlash = false, isLogical = false)

    private companion object {
        // 25 fps: more than the recorder's 30 fps frame interval, so no two frames share a frame number.
        const val FRAME_NANOS = 40_000_000L
    }

    /** The engine's own listener; its next frame callback throws when the kit asks. */
    private inner class ScriptedListener(private val engine: GrabLoopListener) : GrabLoopListener by engine {
        override fun onFrame(frame: LeasedFrame) {
            if (throwOnNextFrame) {
                throwOnNextFrame = false
                throw IllegalStateException("scripted failure in the grab loop's frame listener")
            }
            engine.onFrame(frame)
        }
    }
}
