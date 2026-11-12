@file:OptIn(CameraKEngineApi::class, ExperimentalForeignApi::class)

package com.kashif.cameraK.engine.contract

import com.kashif.cameraK.capabilities.LensInfo
import com.kashif.cameraK.controller.AppleCameraEngine
import com.kashif.cameraK.controller.CameraDeviceProvider
import com.kashif.cameraK.controller.CaptureDeviceOps
import com.kashif.cameraK.controller.CoordinatorRotationSources
import com.kashif.cameraK.controller.CustomCameraController
import com.kashif.cameraK.controller.FakeDevice
import com.kashif.cameraK.controller.FakeMovieRecorder
import com.kashif.cameraK.controller.FakePhotoCapturer
import com.kashif.cameraK.controller.FakeSessionOps
import com.kashif.cameraK.controller.PhotoDimensions
import com.kashif.cameraK.controller.PreviewPointConverter
import com.kashif.cameraK.controller.RotationSourceFactory
import com.kashif.cameraK.controller.VideoRotation
import com.kashif.cameraK.controller.captureInputWithoutCamera
import com.kashif.cameraK.controller.defaultLuma
import com.kashif.cameraK.controller.orientedJpeg
import com.kashif.cameraK.controller.sampleBuffer420f
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
import com.kashif.cameraK.error.CameraFeature
import com.kashif.cameraK.error.InterruptionReason
import com.kashif.cameraK.frames.CoreVideoPixelBufferLocker
import com.kashif.cameraK.frames.FrameFormat
import com.kashif.cameraK.frames.PixelBufferLocker
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.atomicfu.atomic
import kotlinx.cinterop.ExperimentalForeignApi
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
import platform.AVFoundation.AVCaptureInput
import platform.AVFoundation.AVCaptureSessionInterruptionReasonAudioDeviceInUseByAnotherClient
import platform.AVFoundation.AVCaptureSessionInterruptionReasonVideoDeviceInUseByAnotherClient
import platform.AVFoundation.AVCaptureSessionInterruptionReasonVideoDeviceNotAvailableDueToSystemPressure
import platform.AVFoundation.AVCaptureSessionInterruptionReasonVideoDeviceNotAvailableInBackground
import platform.AVFoundation.AVCaptureSessionInterruptionReasonVideoDeviceNotAvailableWithMultipleForegroundApps
import platform.AVFoundation.AVCaptureTorchMode
import platform.AVFoundation.AVCaptureTorchModeAuto
import platform.AVFoundation.AVCaptureTorchModeOff
import platform.AVFoundation.AVCaptureTorchModeOn
import platform.AVFoundation.AVCaptureVideoPreviewLayer
import platform.AVFoundation.AVErrorDeviceAlreadyUsedByAnotherSession
import platform.AVFoundation.AVErrorDeviceWasDisconnected
import platform.CoreFoundation.CFRelease
import platform.UIKit.UIDeviceOrientation
import kotlin.concurrent.Volatile
import kotlin.math.roundToInt

/**
 * The contract kit's driver over the iOS engine on a [FakeSessionOps]. Its fake platform has two
 * cameras, back and front. With [adjustableBack] the back camera has a flash, a torch (off and on)
 * and a zoom range of 1 to 4, so every hardware step of the kit runs; with [adjustableFront] the front
 * camera has a flash, a torch (off and on) and a zoom range of 1 to 2, so the steps after a camera
 * switch run too. Without them a camera has none of these and a fixed zoom of 1 (the I1' platform).
 *
 * The devices hold their torch and zoom reports until [changeHardware], as the kit's
 * `commandsLeaveHardwareUntilThePlatformReports` needs, and [boundSettings] reads what they were last
 * given. Flash has no observer on iOS: the engine reads the photo output's mode back itself
 * ([ReadBackFlashDriver]), and the fake photo output supports [backPhotoFlashModes] or
 * [frontPhotoFlashModes] with an adjustable camera behind it, and only off otherwise.
 *
 * With [metering] the back camera focuses and meters exposure at a point with a bias of -8 to 8 EV
 * (indexes -6 to 6, CameraK's ±2 EV), and the front camera meters exposure only, with a bias of -1.5 to
 * 1.5 EV (indexes -4 to 4). Exposure has no observer on iOS: the engine reads the bias back in
 * `setExposureTargetBias`'s completion ([ReadBackExposureDriver]), which the fake devices run at once.
 * [previewPointConverting] replaces the preview layer's conversion of a layer point.
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
    /** The front camera has a flash, a torch and a zoom range too. */
    private val adjustableFront: Boolean = false,
    /** The photo sizes each camera's active format can deliver. */
    backPhotoSizes: List<PhotoDimensions> = emptyList(),
    frontPhotoSizes: List<PhotoDimensions> = emptyList(),
    /** Whether the app's Info.plist has a key, as the engine reads NSMicrophoneUsageDescription. */
    private val hasUsageDescription: (String) -> Boolean = { true },
    /** The rotation coordinators the engine makes; null keeps the production factory, which makes none for a fake camera. */
    private val rotationSources: RotationSourceFactory? = null,
    /** Locks analysis frames' pixel buffers; null keeps Core Video's. */
    private val pixelBufferLocker: PixelBufferLocker? = null,
    /** The back camera meters focus and exposure at a point, the front camera exposure; both take compensation. */
    metering: Boolean = false,
) : ThrowingCallbackDriver,
    PreOpenInterruptionDriver,
    ReadBackFlashDriver,
    ReadBackExposureDriver,
    CaptureDriver,
    RecordingDriver,
    UnsupportedConfigurationDriver {
    val back = FakeDevice(
        isFront = false,
        uniqueId = BACK_ID,
        hasTorch = adjustableBack,
        supportedTorchModes = backTorchModes,
        maxVideoZoomFactor = if (adjustableBack) BACK_MAX_ZOOM.toDouble() else 1.0,
        supportedMaxPhotoDimensions = backPhotoSizes,
        holdReports = true,
        isFocusPointOfInterestSupported = metering,
        isExposurePointOfInterestSupported = metering,
        minExposureTargetBias = if (metering) -8f else 0f,
        maxExposureTargetBias = if (metering) 8f else 0f,
    )
    val front = FakeDevice(
        isFront = true,
        uniqueId = FRONT_ID,
        hasTorch = adjustableFront,
        supportedTorchModes = setOf(AVCaptureTorchModeOff, AVCaptureTorchModeOn),
        maxVideoZoomFactor = if (adjustableFront) FRONT_MAX_ZOOM.toDouble() else 1.0,
        supportedMaxPhotoDimensions = frontPhotoSizes,
        holdReports = true,
        isExposurePointOfInterestSupported = metering,
        minExposureTargetBias = if (metering) -1.5f else 0f,
        maxExposureTargetBias = if (metering) 1.5f else 0f,
    )
    val lenses = listOf(
        lensInfo(
            BACK_ID,
            CameraLens.BACK,
            hasFlash = adjustableBack,
            maxZoom = if (adjustableBack) BACK_MAX_ZOOM else 1f,
        ),
        lensInfo(
            FRONT_ID,
            CameraLens.FRONT,
            hasFlash = adjustableFront,
            maxZoom = if (adjustableFront) FRONT_MAX_ZOOM else 1f,
        ),
    )

    /** The flash modes the photo output supports while the back camera is behind it. */
    @Volatile
    var backPhotoFlashModes: List<AVCaptureFlashMode> =
        listOf(AVCaptureFlashModeOff, AVCaptureFlashModeAuto, AVCaptureFlashModeOn)

    /** The flash modes the photo output supports while the front camera is behind it. */
    @Volatile
    var frontPhotoFlashModes: List<AVCaptureFlashMode> =
        listOf(AVCaptureFlashModeOff, AVCaptureFlashModeAuto, AVCaptureFlashModeOn)

    /** Converts a preview-layer point to a point of interest for the next engine; null keeps the layer's own. */
    var previewPointConverting: PreviewPointConverter? = null

    private val orientationNotificationLock = platform.Foundation.NSLock()
    private val orientationNotificationCalls = mutableListOf<Boolean>()

    /** Each begin (true) and end (false) of UIDevice's orientation notifications the engine asked for, in order. */
    val orientationNotifications: List<Boolean>
        get() {
            orientationNotificationLock.lock()
            try {
                return orientationNotificationCalls.toList()
            } finally {
                orientationNotificationLock.unlock()
            }
        }

    /** How the device is held, as the engine's orientation reader answers. */
    @Volatile
    var deviceOrientation: UIDeviceOrientation = UIDeviceOrientation.UIDeviceOrientationPortrait

    val photoCapturer = FakePhotoCapturer().also { capturer ->
        capturer.flashModes = {
            when {
                adjustableBack && custom.currentDevice === back -> backPhotoFlashModes
                adjustableFront && custom.currentDevice === front -> frontPhotoFlashModes
                else -> listOf(AVCaptureFlashModeOff)
            }
        }
    }

    /** The movie output's calls; recordings start and finish when a test reports them. */
    val movieRecorder = FakeMovieRecorder()

    /** The microphone's input, which the session accepts unless a test refuses it. */
    val microphone = captureInputWithoutCamera()

    /** Times the engine asked for the microphone's input. */
    val microphoneRequests: Int get() = microphoneAsks.value
    private val microphoneAsks = atomic(0)

    /** What the microphone's input answers; the microphone unless a test makes it fail. */
    @Volatile
    var microphoneAnswer: () -> Result<platform.AVFoundation.AVCaptureInput> = { Result.success(microphone) }

    private val previewTurnLock = platform.Foundation.NSLock()
    private val previewTurnList = mutableListOf<Pair<AVCaptureVideoPreviewLayer?, VideoRotation>>()
    private val boundPreviewTurnList = mutableListOf<AVCaptureVideoPreviewLayer>()
    private val boundPreviewMirroringList = mutableListOf<Pair<AVCaptureVideoPreviewLayer, Boolean>>()

    /** The mirrorings given to a layer bound to a session, in order: the layer and whether it was mirrored. */
    val boundPreviewMirrorings: List<Pair<AVCaptureVideoPreviewLayer, Boolean>>
        get() {
            previewTurnLock.lock()
            try {
                return boundPreviewMirroringList.toList()
            } finally {
                previewTurnLock.unlock()
            }
        }

    private fun recordPreviewMirroring(layer: AVCaptureVideoPreviewLayer?, mirrored: Boolean) {
        if (layer?.session == null) return
        previewTurnLock.lock()
        try {
            boundPreviewMirroringList += layer to mirrored
        } finally {
            previewTurnLock.unlock()
        }
    }

    /** The layers of the turns given while the layer was bound to a session, in order. */
    val boundPreviewTurns: List<AVCaptureVideoPreviewLayer>
        get() {
            previewTurnLock.lock()
            try {
                return boundPreviewTurnList.toList()
            } finally {
                previewTurnLock.unlock()
            }
        }

    /** Every turn of a preview layer, in order: the layer and the orientation or angle it was given. */
    val previewTurns: List<Pair<AVCaptureVideoPreviewLayer?, VideoRotation>>
        get() {
            previewTurnLock.lock()
            try {
                return previewTurnList.toList()
            } finally {
                previewTurnLock.unlock()
            }
        }

    private fun recordPreviewTurn(layer: AVCaptureVideoPreviewLayer?, rotation: VideoRotation) {
        previewTurnLock.lock()
        try {
            previewTurnList += layer to rotation
            if (layer?.session != null) boundPreviewTurnList += layer
        } finally {
            previewTurnLock.unlock()
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
            initialCameraLens = config.cameraLens ?: CameraLens.BACK,
            aspectRatio = config.aspectRatio,
            targetResolution = config.targetResolution?.let { it.width to it.height },
            sessionFactory = {
                sessions.incrementAndGet()
                ops
            },
            cameraDevices = devices,
            photoCapturer = photoCapturer,
            previewMirroring = ::recordPreviewMirroring,
            previewOrienting = { layer, orientation ->
                recordPreviewTurn(layer, VideoRotation.Orientation(orientation))
            },
            previewRotating = { layer, degrees -> recordPreviewTurn(layer, VideoRotation.Angle(degrees)) },
            previewPointConverting = previewPointConverting,
        )
        val onScheduler = checkNotNull(dispatcher) { "createEngine runs inside runCase" }
        val environment = CameraKEnvironment(dispatchers = CameraKDispatchers(onScheduler, onScheduler, onScheduler))
        environment.runtime = EngineRuntime(tracer = tracer, osVersion = osVersion)
        engine = AppleCameraEngine(
            config,
            environment,
            custom,
            readDeviceOrientation = { deviceOrientation },
            hasUsageDescription = hasUsageDescription,
            generateOrientationNotifications = { on ->
                orientationNotificationLock.lock()
                try {
                    orientationNotificationCalls += on
                } finally {
                    orientationNotificationLock.unlock()
                }
            },
            rotationSources = rotationSources ?: CoordinatorRotationSources,
            pixelBufferLocker = pixelBufferLocker ?: CoreVideoPixelBufferLocker,
        ) {
            enumerations.incrementAndGet()
            lenses
        }
        engine.movieRecorder = movieRecorder
        engine.microphoneInput = {
            microphoneAsks.incrementAndGet()
            microphoneAnswer()
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
            // CameraK's iOS index is a third of an EV.
            exposureCompensationIndex = ((camera?.lastBiasSet ?: 0f) * 3).roundToInt(),
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

    /** AVFoundation's didStartRecording for the last recording started. */
    override fun deliverRecordingStarted() {
        movieRecorder.starts.lastOrNull()?.let(movieRecorder::reportStarted)
    }

    /** AVFoundation's didFinishRecording, without an error, for the last recording started. */
    override fun deliverRecordingFinalized() {
        movieRecorder.starts.lastOrNull()?.let { movieRecorder.reportFinished(it, error = null) }
    }

    /** iOS analysis frames are 420f: an RGBA_8888 session fails its start (design §6.1, §8). */
    override fun unsupportedConfiguration(): UnsupportedCase =
        UnsupportedCase(CameraConfiguration { analysisFormat = FrameFormat.RGBA_8888 }, CameraFeature.ANALYSIS_FORMAT)

    /**
     * AVFoundation delivers one 420f sample buffer (see [sampleBuffer420f]) to the session's video data
     * output, captured through [input]: the camera input in use unless given. Returns whether an
     * attached frame output took it.
     */
    fun deliverFrame(
        input: AVCaptureInput? = (custom.currentDevice as? FakeDevice)?.input,
        width: Int = 8,
        height: Int = 4,
        timestampNanos: Long = 1_000_000,
        luma: (Int, Int) -> Int = defaultLuma(width),
    ): Boolean {
        val buffer = sampleBuffer420f(width, height, timestampNanos, luma)
        try {
            return session.deliverSampleBuffer(buffer, input)
        } finally {
            CFRelease(buffer)
        }
    }

    /** AVFoundation drops a late sample buffer at the frame output; false when none is attached. */
    fun dropFrame(): Boolean = session.dropSampleBuffer()

    // The fake photo output never finishes a capture on its own, so every capture is held.
    override fun holdNextCapture() = Unit

    /** AVFoundation delivers the last capture's photo and its last callback. */
    override fun releaseCapture() {
        val request = photoCapturer.requests.lastOrNull() ?: return
        request.callbacks.photoProcessed(orientedJpeg(width = 4, height = 3, orientation = 1), null)
        request.callbacks.captureFinished(null)
    }

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
        const val FRONT_MAX_ZOOM = 2f

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
