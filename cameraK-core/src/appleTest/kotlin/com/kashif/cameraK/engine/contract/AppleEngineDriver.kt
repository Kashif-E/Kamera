@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.engine.contract

import com.kashif.cameraK.capabilities.LensInfo
import com.kashif.cameraK.controller.AppleCameraEngine
import com.kashif.cameraK.controller.CameraDeviceProvider
import com.kashif.cameraK.controller.CaptureDeviceOps
import com.kashif.cameraK.controller.CustomCameraController
import com.kashif.cameraK.controller.FakeDevice
import com.kashif.cameraK.controller.FakePhotoCapturer
import com.kashif.cameraK.controller.FakeSessionOps
import com.kashif.cameraK.controller.spinUntil
import com.kashif.cameraK.coroutines.CameraKDispatchers
import com.kashif.cameraK.engine.CameraEngine
import com.kashif.cameraK.engine.CameraKEnvironment
import com.kashif.cameraK.engine.EngineRuntime
import com.kashif.cameraK.engine.Tracer
import com.kashif.cameraK.enums.CameraDeviceType
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.enums.FlashMode
import com.kashif.cameraK.enums.TorchMode
import com.kashif.cameraK.error.InterruptionReason
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import platform.AVFoundation.AVCaptureFlashMode
import platform.AVFoundation.AVCaptureFlashModeAuto
import platform.AVFoundation.AVCaptureFlashModeOff
import platform.AVFoundation.AVCaptureFlashModeOn
import platform.AVFoundation.AVCaptureSessionInterruptionReasonAudioDeviceInUseByAnotherClient
import platform.AVFoundation.AVCaptureSessionInterruptionReasonVideoDeviceInUseByAnotherClient
import platform.AVFoundation.AVCaptureSessionInterruptionReasonVideoDeviceNotAvailableDueToSystemPressure
import platform.AVFoundation.AVCaptureSessionInterruptionReasonVideoDeviceNotAvailableInBackground
import platform.AVFoundation.AVCaptureSessionInterruptionReasonVideoDeviceNotAvailableWithMultipleForegroundApps
import platform.AVFoundation.AVCaptureTorchMode
import platform.AVFoundation.AVCaptureTorchModeAuto
import platform.AVFoundation.AVCaptureTorchModeOff
import platform.AVFoundation.AVCaptureTorchModeOn
import platform.AVFoundation.AVErrorDeviceAlreadyUsedByAnotherSession
import platform.AVFoundation.AVErrorDeviceWasDisconnected
import platform.UIKit.UIDeviceOrientation
import kotlin.concurrent.Volatile

/**
 * The contract kit's driver over the iOS engine on a [FakeSessionOps]. Its fake platform has two
 * cameras, back and front. With [adjustableBack] the back camera has a flash, a torch (off and on)
 * and a zoom range of 1 to 4, so every hardware step of the kit runs; the front camera has none of
 * them and a fixed zoom of 1. Without it, the back camera is like the front one (the I1' platform).
 *
 * The devices hold their torch and zoom reports until [changeHardware], as the kit's
 * `commandsLeaveHardwareUntilThePlatformReports` needs, and [boundSettings] reads what they were last
 * given. Flash has no observer on iOS: the engine reads the photo output's mode back itself
 * ([ReadBackFlashDriver]), and the fake photo output supports [backPhotoFlashModes] with the back
 * camera behind it and only off with the front one.
 *
 * The engine runs on the case's test scheduler, and AVFoundation's side on the engine's own session
 * queue; [settle] drains the session queue and the main queue, then runs the scheduler's current
 * tasks, three times over, so every hop between them has landed.
 */
internal class AppleEngineDriver(
    private val osVersion: Int = 18,
    private val tracer: Tracer? = null,
    /** The back camera has a flash, a torch and a zoom range; the front camera has none of them. */
    private val adjustableBack: Boolean = false,
    backTorchModes: Set<AVCaptureTorchMode> = setOf(AVCaptureTorchModeOff, AVCaptureTorchModeOn),
) : ThrowingCallbackDriver,
    PreOpenInterruptionDriver,
    ReadBackFlashDriver {
    val back = FakeDevice(
        isFront = false,
        uniqueId = BACK_ID,
        hasTorch = adjustableBack,
        supportedTorchModes = backTorchModes,
        maxVideoZoomFactor = if (adjustableBack) BACK_MAX_ZOOM.toDouble() else 1.0,
        holdReports = true,
    )
    val front = FakeDevice(
        isFront = true,
        uniqueId = FRONT_ID,
        hasTorch = false,
        maxVideoZoomFactor = 1.0,
        holdReports = true,
    )
    val lenses = listOf(
        lensInfo(
            BACK_ID,
            CameraLens.BACK,
            hasFlash = adjustableBack,
            maxZoom = if (adjustableBack) BACK_MAX_ZOOM else 1f,
        ),
        lensInfo(FRONT_ID, CameraLens.FRONT),
    )

    /** The flash modes the photo output supports while the back camera is behind it. */
    @Volatile
    var backPhotoFlashModes: List<AVCaptureFlashMode> =
        listOf(AVCaptureFlashModeOff, AVCaptureFlashModeAuto, AVCaptureFlashModeOn)

    /** How the device is held, as the engine's orientation reader answers. */
    @Volatile
    var deviceOrientation: UIDeviceOrientation = UIDeviceOrientation.UIDeviceOrientationPortrait

    val photoCapturer = FakePhotoCapturer().also { capturer ->
        capturer.flashModes = {
            if (adjustableBack && custom.currentDevice === back) backPhotoFlashModes else listOf(AVCaptureFlashModeOff)
        }
    }

    private val sessions = atomic(0)
    private val enumerations = atomic(0)
    private var dispatcher: TestDispatcher? = null

    lateinit var session: FakeSessionOps
        private set
    lateinit var custom: CustomCameraController
        private set
    lateinit var engine: AppleCameraEngine
        private set

    /** Sessions the engine asked its factory for, and enumerations it ran, across every engine made. */
    val sessionsCreated: Int get() = sessions.value
    val enumerationsRun: Int get() = enumerations.value

    override fun runCase(body: suspend TestScope.() -> Unit): TestResult = runTest {
        dispatcher = StandardTestDispatcher(testScheduler)
        body()
    }

    override fun createEngine(config: CameraConfiguration): CameraEngine {
        val ops = FakeSessionOps()
        session = ops
        custom = CustomCameraController(
            qualityPrioritization = config.qualityPrioritization,
            initialCameraLens = config.cameraLens,
            sessionFactory = {
                sessions.incrementAndGet()
                ops
            },
            cameraDevices = devices,
            photoCapturer = photoCapturer,
        )
        val onScheduler = checkNotNull(dispatcher) { "createEngine runs inside runCase" }
        val environment = CameraKEnvironment(dispatchers = CameraKDispatchers(onScheduler, onScheduler, onScheduler))
        environment.runtime = EngineRuntime(tracer = tracer, osVersion = osVersion)
        engine = AppleCameraEngine(config, environment, custom, readDeviceOrientation = { deviceOrientation }) {
            enumerations.incrementAndGet()
            lenses
        }
        return engine
    }

    override fun holdOpen() = session.holdNextStart()

    override fun deliverOpen() = session.deliverOpen()

    override fun failOpen() = session.failOpen(FakeSessionOps.avError(AVErrorDeviceAlreadyUsedByAnotherSession))

    override fun interrupt(reason: InterruptionReason) = session.interrupt(avReason(reason))

    override fun endInterruption() = session.endInterruption()

    override fun fail() = session.fail(FakeSessionOps.avError(AVErrorDeviceWasDisconnected))

    override fun changeHardware(change: HardwareChange) {
        val device = custom.currentDevice as? FakeDevice ?: return
        device.report(
            torch = change.torchMode?.let(::avTorchMode),
            zoom = change.zoomRatio?.toDouble(),
        )
    }

    // What the devices were last given, never what they report: a report is not a command.
    override fun boundSettings(): BoundSettings {
        val camera = custom.currentDevice as? FakeDevice
        val torch = camera?.lastTorchSet
        return BoundSettings(
            flashMode = when (custom.captureFlashMode) {
                AVCaptureFlashModeOn -> FlashMode.ON
                AVCaptureFlashModeAuto -> FlashMode.AUTO
                else -> FlashMode.OFF
            },
            torchMode = when (torch) {
                AVCaptureTorchModeOn -> TorchMode.ON
                AVCaptureTorchModeAuto -> TorchMode.AUTO
                else -> TorchMode.OFF
            },
            zoomRatio = (camera?.lastZoomSet ?: 1.0).toFloat(),
            cameraId = camera?.uniqueId,
        )
    }

    override fun releaseCount(): Int = session.tearDownCount

    override suspend fun settle() {
        val scheduler =
            checkNotNull(currentCoroutineContext()[TestCoroutineScheduler]) { "settle() runs inside runCase" }
        repeat(3) {
            drainSessionQueueThenMain()
            scheduler.runCurrent()
        }
    }

    override fun throwFromNextCallback() = session.throwOnNextInterruptedRead()

    private fun drainSessionQueueThenMain() {
        val drained = atomic(false)
        custom.afterQueuedSessionWork {
            platform.darwin.dispatch_async(platform.darwin.dispatch_get_main_queue()) { drained.value = true }
        }
        check(spinUntil(10.0) { drained.value }) { "the session queue, then the main queue, did not drain" }
    }

    private val devices = object : CameraDeviceProvider {
        override fun find(lens: CameraLens?, type: CameraDeviceType): CaptureDeviceOps =
            if (lens == CameraLens.FRONT) front else back

        override fun findById(id: String): CaptureDeviceOps? = listOf(back, front).firstOrNull { it.uniqueId == id }
    }

    companion object {
        const val BACK_ID = "back"
        const val FRONT_ID = "front"
        const val BACK_MAX_ZOOM = 4f

        fun lensInfo(id: String, lens: CameraLens, hasFlash: Boolean = false, maxZoom: Float = 1f) = LensInfo(
            id = id,
            deviceType = CameraDeviceType.WIDE_ANGLE,
            lens = lens,
            minZoom = 1f,
            maxZoom = maxZoom,
            hasFlash = hasFlash,
            isLogical = false,
        )

        fun avTorchMode(mode: TorchMode): AVCaptureTorchMode = when (mode) {
            TorchMode.ON -> AVCaptureTorchModeOn
            TorchMode.AUTO -> AVCaptureTorchModeAuto
            TorchMode.OFF -> AVCaptureTorchModeOff
        }

        /** The AVCaptureSessionInterruptionReason the engine maps to [reason]. */
        fun avReason(reason: InterruptionReason): Long = when (reason) {
            InterruptionReason.APP_IN_BACKGROUND ->
                AVCaptureSessionInterruptionReasonVideoDeviceNotAvailableInBackground
            InterruptionReason.AUDIO_IN_USE -> AVCaptureSessionInterruptionReasonAudioDeviceInUseByAnotherClient
            InterruptionReason.MULTITASKING ->
                AVCaptureSessionInterruptionReasonVideoDeviceNotAvailableWithMultipleForegroundApps
            InterruptionReason.SYSTEM_PRESSURE ->
                AVCaptureSessionInterruptionReasonVideoDeviceNotAvailableDueToSystemPressure
            else -> AVCaptureSessionInterruptionReasonVideoDeviceInUseByAnotherClient
        }
    }
}
