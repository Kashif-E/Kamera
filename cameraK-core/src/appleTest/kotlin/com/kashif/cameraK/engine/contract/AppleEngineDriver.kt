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
import platform.AVFoundation.AVCaptureFlashModeAuto
import platform.AVFoundation.AVCaptureFlashModeOn
import platform.AVFoundation.AVCaptureSessionInterruptionReasonAudioDeviceInUseByAnotherClient
import platform.AVFoundation.AVCaptureSessionInterruptionReasonVideoDeviceInUseByAnotherClient
import platform.AVFoundation.AVCaptureSessionInterruptionReasonVideoDeviceNotAvailableDueToSystemPressure
import platform.AVFoundation.AVCaptureSessionInterruptionReasonVideoDeviceNotAvailableInBackground
import platform.AVFoundation.AVCaptureSessionInterruptionReasonVideoDeviceNotAvailableWithMultipleForegroundApps
import platform.AVFoundation.AVCaptureTorchModeOn
import platform.AVFoundation.AVErrorDeviceAlreadyUsedByAnotherSession
import platform.AVFoundation.AVErrorDeviceWasDisconnected

/**
 * The contract kit's driver over the iOS engine on a [FakeSessionOps]. Until I2 its fake platform has
 * two cameras, back and front, with no flash and no torch and a fixed zoom of 1, so the enumerated
 * capabilities give OFF-only flash and torch and the kit lists the steps that need read-back.
 *
 * The engine runs on the case's test scheduler, and AVFoundation's side on the engine's own session
 * queue; [settle] drains the session queue and the main queue, then runs the scheduler's current
 * tasks, three times over, so every hop between them has landed.
 */
internal class AppleEngineDriver(private val osVersion: Int = 18, private val tracer: Tracer? = null) :
    ThrowingCallbackDriver,
    PreOpenInterruptionDriver {
    val back = FakeDevice(isFront = false, uniqueId = BACK_ID, hasTorch = false, maxVideoZoomFactor = 1.0)
    val front = FakeDevice(isFront = true, uniqueId = FRONT_ID, hasTorch = false, maxVideoZoomFactor = 1.0)
    val lenses = listOf(lensInfo(BACK_ID, CameraLens.BACK), lensInfo(FRONT_ID, CameraLens.FRONT))

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
            photoCapturer = FakePhotoCapturer(),
        )
        val onScheduler = checkNotNull(dispatcher) { "createEngine runs inside runCase" }
        val environment = CameraKEnvironment(dispatchers = CameraKDispatchers(onScheduler, onScheduler, onScheduler))
        environment.runtime = EngineRuntime(tracer = tracer, osVersion = osVersion)
        engine = AppleCameraEngine(config, environment, custom) {
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

    // Nothing to report until I2: no camera of this platform has a flash, a torch or a zoom range.
    override fun changeHardware(change: HardwareChange) = Unit

    override fun boundSettings(): BoundSettings {
        val camera = custom.currentDevice
        return BoundSettings(
            flashMode = when (custom.captureFlashMode) {
                AVCaptureFlashModeOn -> FlashMode.ON
                AVCaptureFlashModeAuto -> FlashMode.AUTO
                else -> FlashMode.OFF
            },
            torchMode = if (camera?.torchMode == AVCaptureTorchModeOn) TorchMode.ON else TorchMode.OFF,
            zoomRatio = camera?.videoZoomFactor?.toFloat() ?: 1f,
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

        fun lensInfo(id: String, lens: CameraLens) = LensInfo(
            id = id,
            deviceType = CameraDeviceType.WIDE_ANGLE,
            lens = lens,
            minZoom = 1f,
            maxZoom = 1f,
            hasFlash = false,
            isLogical = false,
        )

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
